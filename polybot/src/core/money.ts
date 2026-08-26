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

/** What a window's positions come to, sold now or left alone. */
export type Standing = {
  /** Everything paid for what is held. */
  cost: number;
  /** Selling both sides at the screen price right now, fee deducted. */
  now: number;
  /** Doing nothing and letting the window settle, if Up wins / if Down wins. */
  ifUp: number;
  ifDown: number;
  /** Held on both sides, so the result is one of those two and nothing else. */
  both: boolean;
  /** The worse of the two — what the window pays at minimum from here. */
  worst: number;
};

/**
 * Where a window stands, sold now or left to settle.
 *
 * Two very different numbers, and the position rows only ever showed the first.
 * Selling now pays the bid less the taker fee. Doing nothing pays a flat dollar
 * for every share on the winning side and nothing for the other — settlement is
 * a contract call, not a trade, so no fee is taken out of it. That is why a
 * position that looks red at the bid can still be the better one to sit on.
 *
 * With both sides held the outcome is one of exactly two numbers, both known
 * now: the market cannot surprise you, it can only pick. When the cheaper side
 * was bought well enough that both are positive, the window is already won.
 */
export function standingOf(held: Held[]): Standing {
  const clean = (v: number) => (Number.isFinite(v) ? v : 0);
  const sideSize = (name: string) =>
    held.filter((h) => h.outcome === name).reduce((a, h) => a + clean(h.size), 0);

  const cost = held.reduce((a, h) => a + clean(h.size) * clean(h.avgPrice), 0);
  const now = held.reduce(
    (a, h) => a + positionPnl(h.size, h.avgPrice, h.curPrice ?? 0).pnl,
    0,
  );

  const up = sideSize('Up');
  const down = sideSize('Down');

  return {
    cost,
    now,
    ifUp: up - cost,
    ifDown: down - cost,
    both: up > 0 && down > 0,
    worst: Math.min(up - cost, down - cost),
  };
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

/** A position as the desk knows it, for the arithmetic below. */
export type Held = {
  outcome: string;
  size: number;
  avgPrice: number;
  /** The bid on the screen; only "sell it now" needs it. */
  curPrice?: number;
};

/**
 * What the round makes if it goes your way: the better of the two settlements.
 *
 * The same arithmetic as `standingOf`, and deliberately the same code — two
 * numbers on one screen that disagree about one window are worse than either
 * of them alone. It used to add the buy fee on top of what was paid, which
 * double-counted it: the taker fee on a buy is taken in shares, so the average
 * price already carries it.
 */
export function potentialProfit(positions: Held[]): number {
  const live = positions.filter((p) => p.size > 0);
  if (live.length === 0) return 0;
  const standing = standingOf(live);
  return Math.max(standing.ifUp, standing.ifDown);
}

/** What one limit adds to that best case if it fills. */
export function limitUpside(shares: number, price: number): number {
  if (!Number.isFinite(shares) || !Number.isFinite(price)) return 0;
  return shares * (1 - price) - feePerShare(price) * shares;
}

/**
 * The rungs a laddered limit places: the price asked for, then steps below it.
 *
 * Under a cent there is nothing left to step down to, so the ladder simply
 * ends — a rung at or below zero is not a price.
 */
export function limitLadder(
  price: number,
  count: number,
  step: number,
  tick = 0.01,
): number[] {
  const rungs = [price];
  for (let i = 1; i <= Math.max(0, count); i += 1) {
    const next = Number((price - step * i).toFixed(4));
    if (next < tick) break;
    rungs.push(next);
  }
  return rungs;
}
