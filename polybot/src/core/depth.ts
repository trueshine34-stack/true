/**
 * Binance's book, drawn as the cost of moving price.
 *
 * A depth curve is the running total of what is bid or offered as you walk
 * away from the mid: how far the two sides lean, and how much money it would
 * take to push through them. On a five-minute Up or Down that is the closest
 * thing to a reason the price should move at all.
 *
 * Sizes arrive bucketed by distance from the mid, nearest bucket first — the
 * curve is their running total, which is why nothing here needs the prices
 * themselves, only how far out each step is.
 */

export interface DepthShape {
  /** Bids, stepping left from the mid. */
  bidPath: string;
  /** Offers, stepping right from it. */
  askPath: string;
  /** The taller of the two totals — both sides are drawn to one scale. */
  peak: number;
  /** What is bid, and what is offered, inside the whole span. */
  bidTotal: number;
  askTotal: number;
}

/**
 * Turns one side into a staircase running out from the mid.
 *
 * Steps rather than a smooth line because that is what a book is: a level
 * holds until the next price, and a curve drawn through the corners invents
 * liquidity that is not there.
 */
function side(
  sizes: number[],
  peak: number,
  centre: number,
  step: number,
  height: number,
  dir: 1 | -1,
): string {
  if (sizes.length === 0 || !(peak > 0)) return '';
  const y = (cum: number) => height - (cum / peak) * height;
  const x = (i: number) => centre + dir * i * step;

  let cum = 0;
  let path = `M${centre.toFixed(1)} ${height.toFixed(1)}`;
  for (let i = 0; i < sizes.length; i++) {
    cum += Math.max(0, sizes[i] || 0);
    const level = y(cum).toFixed(1);
    path += `L${x(i).toFixed(1)} ${level}L${x(i + 1).toFixed(1)} ${level}`;
  }
  return `${path}L${x(sizes.length).toFixed(1)} ${height.toFixed(1)}Z`;
}

export function depthShape(
  bids: number[],
  asks: number[],
  width: number,
  height: number,
): DepthShape | null {
  const buckets = Math.max(bids.length, asks.length);
  if (buckets === 0) return null;

  const total = (xs: number[]) =>
    xs.reduce((a, v) => a + (Number.isFinite(v) && v > 0 ? v : 0), 0);
  const bidTotal = total(bids);
  const askTotal = total(asks);
  const peak = Math.max(bidTotal, askTotal);
  if (!(peak > 0)) return null;

  const centre = width / 2;
  const step = centre / buckets;

  return {
    bidPath: side(bids, peak, centre, step, height, -1),
    askPath: side(asks, peak, centre, step, height, 1),
    peak,
    bidTotal,
    askTotal,
  };
}

/** A size in BTC, at the precision a book is actually read at. */
export function btc(size: number): string {
  if (!Number.isFinite(size)) return '—';
  if (size >= 100) return size.toFixed(0);
  if (size >= 10) return size.toFixed(1);
  return size.toFixed(2);
}

/**
 * A dollar price, spaced as the desk shows every other price.
 *
 * Whole dollars by default, which is bitcoin; a coin whose whole move fits
 * inside a dollar asks for the decimals it moves in, or every label on its
 * chart is the same number.
 */
export function priceLabel(value: number, digits = 0): string {
  return value.toLocaleString('ru-RU', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}
