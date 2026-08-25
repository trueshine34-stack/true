export type Candle = { time: number; open: number; high: number; low: number; close: number };

export type Level = {
  price: number;
  /** How many separate pivots landed on this price. */
  touches: number;
  kind: 'support' | 'resistance';
};

/**
 * Support and resistance from swing pivots.
 *
 * A pivot is a candle whose high tops — or whose low bottoms — every candle
 * within `reach` bars either side. Those are the turns the market actually
 * made. Pivots that landed near the same price are then merged, and a merged
 * group only counts as a level if the market turned there more than once:
 * a single turn is just where price happened to be, and drawing a line through
 * it would fill the chart with noise.
 *
 * Tolerance is a share of the chart's own range rather than a share of price,
 * because what makes two turns "the same level" to the eye is how far apart
 * they look, not how far apart they are in dollars.
 */
export function findLevels(
  candles: Candle[],
  {
    reach = 2,
    tolerance = 0.035,
    minTouches = 2,
    max = 4,
    includeExtremes = true,
  }: {
    reach?: number;
    tolerance?: number;
    minTouches?: number;
    max?: number;
    includeExtremes?: boolean;
  } = {},
): Level[] {
  if (candles.length < reach * 2 + 1) return [];

  const highs = candles.map((c) => c.high);
  const lows = candles.map((c) => c.low);
  const top = Math.max(...highs);
  const bottom = Math.min(...lows);
  const range = top - bottom;
  if (range <= 0) return [];

  const band = range * tolerance;
  const pivots: number[] = [];

  // Strict on the left, permissive on the right. A plateau of equal highs is
  // one turn, not one per bar, and a dead flat stretch is no turn at all —
  // comparing both sides loosely would make every bar of it a pivot and bury
  // the real levels under its own noise.
  for (let i = reach; i < candles.length - reach; i++) {
    let isHigh = true;
    let isLow = true;
    for (let j = i - reach; j < i; j++) {
      if (highs[j] >= highs[i]) isHigh = false;
      if (lows[j] <= lows[i]) isLow = false;
    }
    for (let j = i + 1; j <= i + reach; j++) {
      if (highs[j] > highs[i]) isHigh = false;
      if (lows[j] < lows[i]) isLow = false;
    }
    if (isHigh) pivots.push(highs[i]);
    if (isLow) pivots.push(lows[i]);
  }
  if (pivots.length === 0) return [];

  pivots.sort((a, b) => a - b);
  const groups: number[][] = [];
  for (const p of pivots) {
    const last = groups[groups.length - 1];
    if (last && p - last[0] <= band) last.push(p);
    else groups.push([p]);
  }

  const close = candles[candles.length - 1].close;
  const kindOf = (price: number): Level['kind'] =>
    price >= close ? 'resistance' : 'support';

  const found: Level[] = groups
    .filter((g) => g.length >= minTouches)
    .map((g) => {
      const price = g.reduce((a, b) => a + b, 0) / g.length;
      return { price, touches: g.length, kind: kindOf(price) };
    });

  // A quiet stretch may simply not revisit any price. The extremes of the range
  // are still where it turned, so rather than showing nothing, fall back to the
  // highest and lowest pivot for whichever side came up empty.
  if (includeExtremes) {
    for (const kind of ['resistance', 'support'] as const) {
      if (found.some((l) => l.kind === kind)) continue;
      const side = pivots.filter((p) => kindOf(p) === kind);
      if (side.length === 0) continue;
      const price = kind === 'resistance' ? Math.max(...side) : Math.min(...side);
      found.push({ price, touches: 1, kind });
    }
  }

  return found
    // Most-tested first, and among equals the one nearest the current price:
    // a level twenty percent away is true but useless for the next few minutes.
    .sort(
      (a, b) =>
        b.touches - a.touches || Math.abs(a.price - close) - Math.abs(b.price - close),
    )
    .slice(0, max);
}
