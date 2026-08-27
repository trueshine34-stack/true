/**
 * Binance's five-minute candles, laid out for drawing.
 *
 * The window's own chart is about one five minutes against one price; this is
 * the hours before it. A candle carries four numbers and a direction, and the
 * only work here is turning them into a body and a wick each — but it is work
 * that has to be right at the edges, where a flat candle would otherwise draw
 * as nothing at all.
 */

/** Open time in seconds, then open, high, low, close. */
export type Candle = [number, number, number, number, number];

export interface Bar {
  /** Centre of the candle. */
  x: number;
  /** Half-width of the body. */
  half: number;
  /** The body, top first. */
  top: number;
  bottom: number;
  /** The wick, top first. */
  high: number;
  low: number;
  up: boolean;
}

export interface CandleShape {
  bars: Bar[];
  low: number;
  high: number;
  /** The drawn range, padded — what the top and bottom of the box are worth. */
  top: number;
  floor: number;
  /** The newest close. */
  last: number;
  /** Where the candle in progress opened, and how far it has come from it. */
  open: number;
  sinceOpen: number;
}

/** A body this thin still has to be visible: a doji is a line, not a gap. */
const MIN_BODY = 0.8;

export function candleShape(
  candles: Candle[],
  width: number,
  height: number,
): CandleShape | null {
  const clean = candles.filter(
    ([t, o, h, l, c]) =>
      Number.isFinite(t) && o > 0 && h > 0 && l > 0 && c > 0 && h >= l,
  );
  if (clean.length === 0) return null;

  let low = Infinity;
  let high = -Infinity;
  for (const [, , h, l] of clean) {
    if (l < low) low = l;
    if (h > high) high = h;
  }
  const span = high - low;
  // A stretch of price that never moved would divide by nothing.
  const pad = span > 1e-9 ? span * 0.06 : Math.max(1, high * 1e-5);
  const lo = low - pad;
  const hi = high + pad;
  const y = (value: number) => ((hi - value) / (hi - lo)) * height;

  const slot = width / clean.length;
  const half = Math.max(0.6, (slot * 0.62) / 2);

  const bars = clean.map(([, open, hiPx, loPx, close], i): Bar => {
    const up = close >= open;
    let top = y(Math.max(open, close));
    let bottom = y(Math.min(open, close));
    if (bottom - top < MIN_BODY) {
      const mid = (top + bottom) / 2;
      top = mid - MIN_BODY / 2;
      bottom = mid + MIN_BODY / 2;
    }
    return {
      x: slot * (i + 0.5),
      half,
      top,
      bottom,
      high: y(hiPx),
      low: y(loPx),
      up,
    };
  });

  // The candle in progress is the interval the desk is actually inside: for
  // the five-minute chart that is this window's own open, which is the price
  // a five-minute bet is settled against.
  const current = clean[clean.length - 1];
  const open = current[1];
  const last = current[4];
  return {
    bars,
    low,
    high,
    top: hi,
    floor: lo,
    last,
    open,
    sinceOpen: open > 0 ? ((last - open) / open) * 100 : 0,
  };
}

/** A move, the way a chart header says it. */
export function signedPct(pct: number): string {
  if (!Number.isFinite(pct)) return '—';
  const sign = pct > 0 ? '+' : pct < 0 ? '−' : '';
  return `${sign}${Math.abs(pct).toFixed(2)}%`;
}
