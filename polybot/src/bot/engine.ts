import { Wallet } from 'ethers';
import { WINDOW_SECONDS } from '../core/config';
import { log } from '../core/logger';
import { ChainlinkFeed, type Tick } from '../feed/rtds';
import {
  calculateMarketPrice,
  getBook,
  getMarket,
  postMarketOrder,
} from '../polymarket/clob';
import { fetchMarketForWindow, windowStartFor } from '../polymarket/gamma';
import type {
  ApiCreds,
  Btc5mMarket,
  PostOrderResult,
  SignatureType,
} from '../polymarket/types';
import {
  decide,
  fairValue,
  type Decision,
  type FairValue,
  type StrategySettings,
} from './strategy';

export type Session = {
  wallet: Wallet;
  creds: ApiCreds;
  funderAddress: string;
  signatureType: SignatureType;
};

export type CycleState =
  | 'waiting'
  | 'armed'
  | 'entered'
  | 'skipped'
  | 'settled'
  | 'failed';

export type Entry = {
  side: 'Up' | 'Down';
  price: number;
  shares: number;
  costUsd: number;
  orderId?: string;
  dryRun: boolean;
};

export type Cycle = {
  windowStart: number;
  windowEnd: number;
  market: Btc5mMarket | null;
  strike: number | null;
  spotAtEntry: number | null;
  fair: FairValue | null;
  decision: Decision | null;
  entry: Entry | null;
  winner: 'Up' | 'Down' | null;
  pnlUsd: number | null;
  state: CycleState;
  note?: string;
};

export type EngineStats = {
  trades: number;
  wins: number;
  losses: number;
  consecutiveLosses: number;
  realisedPnlUsd: number;
  stakedUsd: number;
};

export type EngineSnapshot = {
  running: boolean;
  haltReason: string | null;
  current: Cycle | null;
  history: Cycle[];
  stats: EngineStats;
  lastTick: Tick | null;
  feedStatus: string;
};

const SETTLE_DELAY_MS = 20_000;
const SETTLE_RETRIES = 12;
const SETTLE_RETRY_MS = 10_000;
const STRIKE_GRACE_MS = 15_000;

const EMPTY_STATS: EngineStats = {
  trades: 0,
  wins: 0,
  losses: 0,
  consecutiveLosses: 0,
  realisedPnlUsd: 0,
  stakedUsd: 0,
};

export class BotEngine {
  readonly feed = new ChainlinkFeed('btc/usd');

  private session: Session | null = null;
  private settings: StrategySettings;
  private running = false;
  private haltReason: string | null = null;

  private current: Cycle | null = null;
  private history: Cycle[] = [];
  private stats: EngineStats = { ...EMPTY_STATS };

  private loopTimer: ReturnType<typeof setInterval> | null = null;
  private marketFetchInFlight = false;
  private entryInFlight = false;
  private listeners = new Set<(s: EngineSnapshot) => void>();

  constructor(settings: StrategySettings) {
    this.settings = settings;
    this.feed.onStatus(() => this.emit());
    this.feed.onTick(() => this.emit());
  }

  subscribe(fn: (s: EngineSnapshot) => void): () => void {
    this.listeners.add(fn);
    fn(this.snapshot());
    return () => this.listeners.delete(fn);
  }

  snapshot(): EngineSnapshot {
    return {
      running: this.running,
      haltReason: this.haltReason,
      current: this.current,
      history: this.history,
      stats: this.stats,
      lastTick: this.feed.last,
      feedStatus: this.feed.status,
    };
  }

  private emit(): void {
    const s = this.snapshot();
    for (const fn of this.listeners) fn(s);
  }

  setSettings(settings: StrategySettings): void {
    this.settings = settings;
    this.emit();
  }

  setSession(session: Session | null): void {
    this.session = session;
    this.emit();
  }

  /** Feed runs even when the bot is idle, so the screen always shows a price. */
  startFeed(): void {
    this.feed.start();
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.haltReason = null;
    this.feed.start();
    if (!this.loopTimer) {
      this.loopTimer = setInterval(() => void this.tick(), 250);
    }
    log.info('Бот запущен');
    this.emit();
  }

  stop(reason?: string): void {
    if (!this.running) return;
    this.running = false;
    if (this.loopTimer) clearInterval(this.loopTimer);
    this.loopTimer = null;
    this.haltReason = reason ?? null;
    log.info(reason ? `Бот остановлен: ${reason}` : 'Бот остановлен');
    this.emit();
  }

  resetStats(): void {
    this.stats = { ...EMPTY_STATS };
    this.history = [];
    this.emit();
  }

  private async tick(): Promise<void> {
    if (!this.running) return;

    const now = Date.now();
    const ws = windowStartFor(now);

    if (!this.current || this.current.windowStart !== ws) {
      if (this.current) this.rollOver(this.current);
      this.current = {
        windowStart: ws,
        windowEnd: ws + WINDOW_SECONDS,
        market: null,
        strike: null,
        spotAtEntry: null,
        fair: null,
        decision: null,
        entry: null,
        winner: null,
        pnlUsd: null,
        state: 'waiting',
      };
      this.marketFetchInFlight = false;
      this.entryInFlight = false;
      this.emit();
    }

    const cycle = this.current;

    if (cycle.state === 'waiting' && !this.marketFetchInFlight) {
      void this.loadMarket(cycle);
    }

    if (cycle.strike === null) {
      const tick = this.feed.firstTickAtOrAfter(cycle.windowStart * 1000);
      if (tick) {
        cycle.strike = tick.value;
        log.info(
          `Окно ${formatWindow(cycle.windowStart)} — страйк ${tick.value.toFixed(2)} $`,
        );
        this.emit();
      } else if (now > cycle.windowStart * 1000 + STRIKE_GRACE_MS) {
        cycle.state = 'skipped';
        cycle.note = 'нет тика Chainlink на открытии окна';
        log.warn(`Окно ${formatWindow(cycle.windowStart)} пропущено: ${cycle.note}`);
        this.emit();
      }
    }

    const entryAt =
      cycle.windowStart * 1000 + this.settings.entryDelaySec * 1000;
    const tooLate = now > cycle.windowEnd * 1000 - 30_000;

    if (
      cycle.state === 'armed' &&
      cycle.strike !== null &&
      now >= entryAt &&
      !this.entryInFlight
    ) {
      if (tooLate) {
        cycle.state = 'skipped';
        cycle.note = 'слишком поздно для входа в этом окне';
        this.emit();
      } else {
        void this.attemptEntry(cycle);
      }
    }
  }

  private async loadMarket(cycle: Cycle): Promise<void> {
    this.marketFetchInFlight = true;
    try {
      const market = await fetchMarketForWindow(cycle.windowStart);
      if (this.current !== cycle) return;

      if (!market) {
        cycle.state = 'skipped';
        cycle.note = 'рынок этого окна не найден на Gamma';
        log.warn(`Окно ${formatWindow(cycle.windowStart)}: рынок не найден`);
      } else if (!market.acceptingOrders) {
        cycle.market = market;
        cycle.state = 'skipped';
        cycle.note = 'рынок не принимает ордера';
        log.warn(`Окно ${formatWindow(cycle.windowStart)}: ордера не принимаются`);
      } else {
        cycle.market = market;
        cycle.state = 'armed';
      }
    } catch (err) {
      if (this.current !== cycle) return;
      cycle.state = 'skipped';
      cycle.note = `ошибка загрузки рынка: ${errText(err)}`;
      log.error('Не удалось загрузить рынок', err);
    } finally {
      this.marketFetchInFlight = false;
      this.emit();
    }
  }

  private preTradeBlock(): string | null {
    if (!this.session) return 'кошелёк не подключён';
    if (this.settings.mode === 'off') return 'режим "не торговать"';
    if (this.feed.status !== 'live') return `фид Chainlink: ${this.feed.status}`;
    if (this.stats.realisedPnlUsd <= -Math.abs(this.settings.dailyLossLimitUsd)) {
      return `достигнут лимит убытка ${this.settings.dailyLossLimitUsd} $`;
    }
    if (this.stats.consecutiveLosses >= this.settings.maxConsecutiveLosses) {
      return `${this.stats.consecutiveLosses} убытков подряд`;
    }
    return null;
  }

  private async attemptEntry(cycle: Cycle): Promise<void> {
    this.entryInFlight = true;
    try {
      const market = cycle.market;
      const strike = cycle.strike;
      if (!market || strike === null) return;

      const block = this.preTradeBlock();
      if (block) {
        cycle.state = 'skipped';
        cycle.note = block;
        log.warn(`Окно ${formatWindow(cycle.windowStart)} пропущено: ${block}`);
        if (
          block.startsWith('достигнут лимит') ||
          block.includes('убытков подряд')
        ) {
          this.stop(block);
        }
        return;
      }

      const spot = this.feed.last?.value ?? null;
      if (spot === null) {
        cycle.state = 'skipped';
        cycle.note = 'нет текущей цены';
        return;
      }
      cycle.spotAtEntry = spot;

      const ticks = this.feed.ticksBetween(Date.now() - 600_000, Date.now());
      cycle.fair = fairValue({
        strike,
        spot,
        msToClose: cycle.windowEnd * 1000 - Date.now(),
        ticks,
      });

      const [upBook, downBook] = await Promise.all([
        getBook(market.up.tokenId),
        getBook(market.down.tokenId),
      ]);

      const stake = this.settings.stakeUsd;
      const quote = {
        upAsk: calculateMarketPrice(upBook, 'BUY', stake, 'FOK'),
        downAsk: calculateMarketPrice(downBook, 'BUY', stake, 'FOK'),
      };

      const decision = decide(this.settings, cycle.fair, quote);
      cycle.decision = decision;

      if (!decision.act) {
        cycle.state = 'skipped';
        cycle.note = decision.reason;
        log.info(
          `Окно ${formatWindow(cycle.windowStart)}: не входим — ${decision.reason}`,
        );
        return;
      }

      const sized = this.sizeOrder(decision.price, stake, market.minimumOrderSize);
      if (!sized) {
        cycle.state = 'skipped';
        cycle.note = `${stake} $ по цене ${decision.price} даёт меньше минимума в ${market.minimumOrderSize} долей`;
        log.warn(`Окно ${formatWindow(cycle.windowStart)}: ${cycle.note}`);
        return;
      }
      if (sized.amountUsd !== stake) {
        log.info(
          `Ставка поднята с ${stake.toFixed(2)} $ до ${sized.amountUsd.toFixed(2)} $ — минимум ${market.minimumOrderSize} долей`,
        );
      }

      const token =
        decision.side === 'Up' ? market.up.tokenId : market.down.tokenId;

      if (this.settings.dryRun) {
        cycle.entry = {
          side: decision.side,
          price: decision.price,
          shares: sized.shares,
          costUsd: sized.amountUsd,
          dryRun: true,
        };
        cycle.state = 'entered';
        this.stats.trades += 1;
        this.stats.stakedUsd += sized.amountUsd;
        log.trade(
          `[ТЕСТ] ${decision.side} · ${sized.shares.toFixed(2)} долей по ${(decision.price * 100).toFixed(0)}¢ = ${sized.amountUsd.toFixed(2)} $ — ${decision.reason}`,
        );
        return;
      }

      const session = this.session!;
      let result: PostOrderResult;
      try {
        result = await postMarketOrder({
          wallet: session.wallet,
          creds: session.creds,
          funder: session.funderAddress,
          signatureType: session.signatureType,
          tokenId: token,
          side: 'BUY',
          amount: sized.amountUsd,
          price: decision.price,
          tickSize: market.tickSize,
          negRisk: market.negRisk,
          orderType: 'FOK',
        });
      } catch (err) {
        cycle.state = 'failed';
        cycle.note = `ордер отклонён: ${errText(err)}`;
        log.error(`Ордер не прошёл (${formatWindow(cycle.windowStart)})`, err);
        return;
      }

      if (result.success === false || result.errorMsg) {
        cycle.state = 'failed';
        cycle.note = result.errorMsg ?? 'CLOB отклонил ордер';
        log.error(`Ордер отклонён: ${cycle.note}`, result);
        return;
      }

      const filledShares = result.takingAmount
        ? Number(result.takingAmount)
        : sized.shares;
      const filledCost = result.makingAmount
        ? Number(result.makingAmount)
        : sized.amountUsd;

      cycle.entry = {
        side: decision.side,
        price: filledShares > 0 ? filledCost / filledShares : decision.price,
        shares: filledShares,
        costUsd: filledCost,
        orderId: result.orderID,
        dryRun: false,
      };
      cycle.state = 'entered';
      this.stats.trades += 1;
      this.stats.stakedUsd += filledCost;

      log.trade(
        `${decision.side} · ${filledShares.toFixed(2)} долей за ${filledCost.toFixed(2)} $ (${result.status ?? 'ok'}) — ${decision.reason}`,
      );
    } catch (err) {
      cycle.state = 'failed';
      cycle.note = errText(err);
      log.error('Сбой при входе в позицию', err);
    } finally {
      this.entryInFlight = false;
      this.emit();
    }
  }

  /**
   * The venue enforces a minimum order size in shares, which a $2 stake misses
   * whenever a share costs more than $0.40. Raising the stake to exactly the
   * minimum keeps the bot trading; refusing keeps the stake honest.
   */
  private sizeOrder(
    price: number,
    stakeUsd: number,
    minShares: number,
  ): { shares: number; amountUsd: number } | null {
    const shares = stakeUsd / price;
    if (shares >= minShares) return { shares, amountUsd: stakeUsd };
    if (!this.settings.autoBumpToMinimum) return null;

    // Round up to a whole cent so the venue's own rounding cannot drop us back
    // under the minimum.
    const amountUsd = Math.ceil(minShares * price * 100) / 100;
    return { shares: amountUsd / price, amountUsd };
  }

  private rollOver(cycle: Cycle): void {
    this.history = [cycle, ...this.history].slice(0, 200);
    if (cycle.entry) void this.settle(cycle);
    this.emit();
  }

  private async settle(cycle: Cycle): Promise<void> {
    const market = cycle.market;
    const entry = cycle.entry;
    if (!market || !entry) return;

    await sleep(Math.max(0, cycle.windowEnd * 1000 + SETTLE_DELAY_MS - Date.now()));

    let winner: 'Up' | 'Down' | null = null;

    for (let i = 0; i < SETTLE_RETRIES && winner === null; i++) {
      try {
        const m = await getMarket(market.conditionId);
        const won = m.tokens?.find((t) => t.winner);
        if (won) winner = won.outcome === 'Up' ? 'Up' : 'Down';
      } catch {
        /* transient; retry below */
      }
      if (winner === null) await sleep(SETTLE_RETRY_MS);
    }

    if (winner === null) {
      // Fall back to the same feed the resolver uses: the average of the last
      // 30 seconds of the window against the strike.
      winner = this.settleFromFeed(cycle);
      if (winner !== null) {
        cycle.note = [cycle.note, 'исход посчитан по фиду, а не по CLOB']
          .filter(Boolean)
          .join('; ');
      }
    }

    if (winner === null) {
      cycle.note = [cycle.note, 'исход не определён'].filter(Boolean).join('; ');
      this.emit();
      return;
    }

    const won = winner === entry.side;
    const payout = won ? entry.shares : 0;
    const pnl = payout - entry.costUsd;

    cycle.winner = winner;
    cycle.pnlUsd = pnl;
    cycle.state = 'settled';

    if (!entry.dryRun || this.settings.dryRun) {
      this.stats.realisedPnlUsd += pnl;
      if (won) {
        this.stats.wins += 1;
        this.stats.consecutiveLosses = 0;
      } else {
        this.stats.losses += 1;
        this.stats.consecutiveLosses += 1;
      }
    }

    log.trade(
      `${formatWindow(cycle.windowStart)} → ${winner}. ${won ? 'Выигрыш' : 'Проигрыш'} ${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)} $`,
    );
    this.emit();
  }

  private settleFromFeed(cycle: Cycle): 'Up' | 'Down' | null {
    if (cycle.strike === null) return null;
    const endMs = cycle.windowEnd * 1000;
    const ticks = this.feed.ticksBetween(endMs - 30_000, endMs);
    if (ticks.length < 5) return null;
    const twap = ticks.reduce((a, t) => a + t.value, 0) / ticks.length;
    return twap >= cycle.strike ? 'Up' : 'Down';
  }
}

function formatWindow(windowStart: number): string {
  const d = new Date(windowStart * 1000);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function errText(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, Math.max(0, ms)));
}
