/**
 * Money, as opposed to shares.
 *
 * A share count only means something once it is multiplied by a price, and on
 * a screen you glance at between two five-minute windows there is no time to do
 * that in your head. Everything the desk shows is worked out here, once, so the
 * figure on a button and the figure in a list can never disagree.
 *
 * The fee is part of every one of these numbers. Polymarket charges the taker
 * `rate x p x (1 - p)` per share out of what the trade pays out, so a position
 * marked at 60c does not hand over 60c a share — and a "profit" worked out
 * without it is a profit that is not there.
 */
export const FEE_RATE = 0.07;

/** Fee on one share at this price, in dollars. */
export const feePerShare = (price: number): number =>
  Number.isFinite(price) && price > 0 && price < 1 ? FEE_RATE * price * (1 - price) : 0;

/** What one share actually pays out when sold at this price. */
export const netSellPrice = (price: number): number => price - feePerShare(price);

export type Pnl = {
  /** Marked value, before the fee — what the exchange shows. */
  value: number;
  /** What selling right now would actually pay. */
  net: number;
  cost: number;
  /** Net minus cost: the money, not the mark. */
  pnl: number;
  /** As a share of what was paid. */
  pct: number;
};

/**
 * A position's standing at the price on the screen.
 *
 * Marked value and takeable value are both here because they answer different
 * questions — what it is worth, and what it would pay — and the difference
 * between them is exactly the fee.
 */
export function positionPnl(size: number, avgPrice: number, curPrice: number): Pnl {
  const clean = (v: number) => (Number.isFinite(v) ? v : 0);
  const s = clean(size);
  const value = s * clean(curPrice);
  const net = s * netSellPrice(clean(curPrice));
  const cost = s * clean(avgPrice);
  const pnl = net - cost;
  return { value, net, cost, pnl, pct: cost > 0 ? pnl / cost : 0 };
}

/**
 * The price to sell at for a given gain over the buy price, after the fee.
 *
 * Asking for "twenty percent" and resting at `avg x 1.2` quietly delivers less
 * than twenty: the fee comes out of the proceeds. Solving for the price whose
 * *net* is `avg x (1 + gain)` is a quadratic — `0.07p² + 0.93p = target` — and
 * it has a closed form, so the number on the button is the number you get.
 */
export function targetPrice(avgPrice: number, gain: number, tick = 0.01): number {
  if (!Number.isFinite(avgPrice) || avgPrice <= 0) return tick;
  const wanted = avgPrice * (1 + Math.max(gain, 0));
  const a = FEE_RATE;
  const b = 1 - FEE_RATE;
  const price = (-b + Math.sqrt(b * b + 4 * a * wanted)) / (2 * a);
  return ceilToTick(price, tick);
}

/** The cheapest price that still comes out ahead once the fee is paid. */
export function breakEvenPrice(avgPrice: number, tick = 0.01): number {
  const price = targetPrice(avgPrice, 0, tick);
  // Strictly ahead: a price whose net lands exactly on the cost is not a profit.
  return netSellPrice(price) > avgPrice ? price : ceilToTick(price + tick, tick);
}

/** Round up onto the venue's grid, without floating-point dust. */
export function ceilToTick(price: number, tick = 0.01): number {
  if (!(tick > 0)) return price;
  const snapped = Math.ceil(price / tick - 1e-9) * tick;
  return Math.min(Math.max(Number(snapped.toFixed(4)), tick), 1 - tick);
}

/** Dollars, the way every figure on the desk is written. */
export const usd = (value: number): string =>
  `${value < 0 ? '−' : ''}${Math.abs(value).toFixed(2)} $`;

/** Signed dollars, for a number whose sign is the point. */
export const signedUsd = (value: number): string =>
  `${value >= 0 ? '+' : '−'}${Math.abs(value).toFixed(2)} $`;

export const signedPct = (fraction: number): string =>
  `${fraction >= 0 ? '+' : '−'}${Math.abs(fraction * 100).toFixed(0)}%`;

/** The gains the sell form offers, as fractions. */
export const SELL_GAINS = [0.25, 0.5, 1, 2, 3];
