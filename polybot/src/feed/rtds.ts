import { RTDS_WS } from '../core/config';

/**
 * Chainlink BTC/USD relayed by Polymarket's Real-Time Data Service.
 *
 * This is the same stream the 5-minute markets settle against, so the strike
 * we record here is the strike the resolver will use — no basis risk against a
 * spot exchange feed.
 */

export type Tick = {
  /** Milliseconds, as stamped by the oracle (not by us). */
  timestamp: number;
  value: number;
};

type Listener = (tick: Tick) => void;
type StatusListener = (status: FeedStatus) => void;

export type FeedStatus = 'connecting' | 'live' | 'stalled' | 'closed';

const PING_INTERVAL_MS = 5_000;
const STALL_AFTER_MS = 15_000;
const MAX_BACKOFF_MS = 30_000;

export class ChainlinkFeed {
  private ws: WebSocket | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private stallTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;
  private stopped = false;

  private listeners = new Set<Listener>();
  private statusListeners = new Set<StatusListener>();

  private _status: FeedStatus = 'closed';
  private _last: Tick | null = null;
  /** Rolling tick history, newest last. Bounded to ~20 minutes at 1 Hz. */
  private history: Tick[] = [];
  private static readonly MAX_HISTORY = 1200;

  constructor(private readonly symbol = 'btc/usd') {}

  get status(): FeedStatus {
    return this._status;
  }

  get last(): Tick | null {
    return this._last;
  }

  onTick(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  onStatus(fn: StatusListener): () => void {
    this.statusListeners.add(fn);
    return () => this.statusListeners.delete(fn);
  }

  /** Ticks with `timestamp` in [fromMs, toMs], oldest first. */
  ticksBetween(fromMs: number, toMs: number): Tick[] {
    return this.history.filter(
      (t) => t.timestamp >= fromMs && t.timestamp <= toMs,
    );
  }

  /** First tick at or after `atMs` — the strike of a window that just opened. */
  firstTickAtOrAfter(atMs: number): Tick | null {
    for (const t of this.history) if (t.timestamp >= atMs) return t;
    return null;
  }

  /** Last tick at or before `atMs`. */
  lastTickAtOrBefore(atMs: number): Tick | null {
    for (let i = this.history.length - 1; i >= 0; i--) {
      if (this.history[i].timestamp <= atMs) return this.history[i];
    }
    return null;
  }

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.clearTimers();
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.ws?.close();
    this.ws = null;
    this.setStatus('closed');
  }

  private setStatus(s: FeedStatus): void {
    if (this._status === s) return;
    this._status = s;
    for (const fn of this.statusListeners) fn(s);
  }

  private clearTimers(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    if (this.stallTimer) clearInterval(this.stallTimer);
    this.pingTimer = null;
    this.stallTimer = null;
  }

  private connect(): void {
    if (this.stopped) return;
    this.setStatus('connecting');

    let ws: WebSocket;
    try {
      ws = new WebSocket(RTDS_WS);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      this.attempt = 0;
      ws.send(
        JSON.stringify({
          action: 'subscribe',
          subscriptions: [
            {
              topic: 'crypto_prices_chainlink',
              type: '*',
              filters: JSON.stringify({ symbol: this.symbol }),
            },
          ],
        }),
      );

      this.clearTimers();
      this.pingTimer = setInterval(() => {
        try {
          ws.send('PING');
        } catch {
          /* the close handler will pick this up */
        }
      }, PING_INTERVAL_MS);

      this.stallTimer = setInterval(() => {
        const age = this._last ? Date.now() - this._last.timestamp : Infinity;
        if (age > STALL_AFTER_MS) this.setStatus('stalled');
        else this.setStatus('live');
      }, 2_000);
    };

    ws.onmessage = (ev) => this.handleMessage(ev.data);

    ws.onerror = () => {
      /* onclose always follows; reconnect is handled there */
    };

    ws.onclose = () => {
      this.clearTimers();
      if (!this.stopped) this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    this.setStatus('connecting');
    const delay = Math.min(1_000 * 2 ** this.attempt, MAX_BACKOFF_MS);
    this.attempt = Math.min(this.attempt + 1, 6);
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  private handleMessage(data: unknown): void {
    if (typeof data !== 'string' || data.length === 0) return;
    if (data === 'PONG' || data === 'PING') return;

    let msg: {
      topic?: string;
      type?: string;
      payload?: {
        symbol?: string;
        timestamp?: number;
        value?: number;
        data?: { timestamp: number; value: number }[];
      };
    };
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }

    const payload = msg.payload;
    if (!payload) return;

    // On subscribe the service replays recent history in one batch before it
    // switches to per-second updates.
    if (Array.isArray(payload.data)) {
      for (const t of payload.data) {
        if (typeof t?.timestamp === 'number' && typeof t?.value === 'number') {
          this.push({ timestamp: t.timestamp, value: t.value }, false);
        }
      }
      if (this._last) this.emit(this._last);
      return;
    }

    if (payload.symbol && payload.symbol !== this.symbol) return;
    if (typeof payload.timestamp !== 'number' || typeof payload.value !== 'number') {
      return;
    }
    this.push({ timestamp: payload.timestamp, value: payload.value }, true);
  }

  private push(tick: Tick, emit: boolean): void {
    const prev = this.history[this.history.length - 1];
    if (prev && tick.timestamp <= prev.timestamp) {
      // Out-of-order or duplicate replay; keep the series monotonic.
      if (tick.timestamp === prev.timestamp) return;
      const idx = this.history.findIndex((t) => t.timestamp > tick.timestamp);
      if (idx >= 0) this.history.splice(idx, 0, tick);
      return;
    }

    this.history.push(tick);
    if (this.history.length > ChainlinkFeed.MAX_HISTORY) {
      this.history.splice(0, this.history.length - ChainlinkFeed.MAX_HISTORY);
    }
    this._last = tick;
    if (emit) {
      this.setStatus('live');
      this.emit(tick);
    }
  }

  private emit(tick: Tick): void {
    for (const fn of this.listeners) fn(tick);
  }
}
