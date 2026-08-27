/**
 * Settings for the manual desk, and the arithmetic the buttons rest on.
 *
 * What one tap buys is no longer among them: the size lives in the row above
 * the buttons where the trade is, and everything here is a rule about what
 * happens afterwards.
 */
/**
 * What one tap buys when nothing else is chosen.
 *
 * Five shares, and not a setting. The size that matters is the one in the row
 * above the buttons, chosen for the trade in front of you; a second answer
 * kept in a fold three taps away only ever disagreed with it.
 */
export const DEFAULT_CLICK_SHARES = 5;

export type ManualSettings = {
  autoSellEnabled: boolean;
  /** Sell price by minute of the window, cheapest rung first. */
  autoSellLadder: number[];
  /**
   * How long before a refused sell is tried again.
   *
   * Three seconds, and not a setting any more. The venue either has the shares
   * ready or it has not; the only useful answer to "not yet" is to ask again
   * shortly, and a number the user has to guess at was a number that could be
   * set wrong.
   */
  autoSellRetrySec: number;
  /** How long to keep trying on one purchase before giving up. */
  autoSellWatchSec: number;
  /** How far ahead of each minute the next rung takes over. */
  autoSellLeadSec: number;
  /** Price the exit off what the position cost rather than off the clock. */
  autoSellPercentMode: boolean;
  /** The margin to hold out for, net of the fee. */
  autoSellProfitPct: number;
  /** Pause between slices when a position was built out of several buys. */
  autoSellSliceGapSec: number;
  /** Inside this much of the close, the floor below replaces the margin. */
  autoSellPanicSec: number;
  /** The least the last minute will sell for, in dollars. */
  autoSellCloseFloor: number;
  /** The least the stretch before that will sell for. */
  autoSellLateFloor: number;
  /** How long that stretch runs, ending where the last minute begins. */
  autoSellLateBandSec: number;
  /** Buy the same size back if the price falls far enough after a sale. */
  autoRebuyEnabled: boolean;
  /** How far below the sale price the buy-back triggers, as a fraction. */
  autoRebuyDropPct: number;
  /** Pause between buy-back slices, so a deeper dip can still be caught. */
  autoRebuySlicePauseSec: number;
  /** A hand-placed limit also goes out at lower prices, same size. */
  limitLadder: boolean;
  /** How far apart the rungs are. How many there are is not a choice. */
  limitLadderStep: number;
  /** Keep the container's share out of reach of any order. */
  exposureGuard: boolean;
};

/**
 * Extra rungs a laddered limit puts out below the price asked for.
 *
 * Three, always. It was a choice between two, three and four, which is a
 * setting nobody changes and a row of buttons in the way of the ones they do.
 */
export const LIMIT_LADDER_COUNT = 3;

export const DEFAULT_MANUAL_SETTINGS: ManualSettings = {
  autoSellEnabled: false,
  autoSellLadder: [0.77, 0.84, 0.89, 0.93, 0.97],
  autoSellRetrySec: 3,
  autoSellWatchSec: 60,
  autoSellLeadSec: 15,
  autoSellPercentMode: false,
  autoSellProfitPct: 0.2,
  autoSellSliceGapSec: 2,
  autoSellPanicSec: 60,
  autoSellCloseFloor: 0.9,
  autoSellLateFloor: 0.77,
  autoSellLateBandSec: 50,
  autoRebuyEnabled: false,
  autoRebuyDropPct: 0.2,
  autoRebuySlicePauseSec: 3,
  limitLadder: false,
  limitLadderStep: 0.03,
  exposureGuard: true,
};

/** Never spend the last of the balance, whatever the fee works out to. */
export const BALANCE_HEADROOM = 0.02;

/** Polymarket's taker fee: rate x p x (1 - p) per share, charged to the taker. */
const TAKER_FEE_RATE = 0.07;

/**
 * How much of the wallet an order may actually be worth.
 *
 * The taker fee is charged *on top of* the order amount, not out of it, so an
 * order for the whole balance leaves nothing to pay the fee with and is simply
 * refused — which is why 100% never worked, and why 50% failed too whenever
 * something else was already reserving collateral.
 *
 * Two percent is the floor. The real fee is `rate x (1 - p)` of the order's
 * value, which at 20c is nearly six percent — reserving a flat two there would
 * fail exactly the same way, so the larger of the two wins.
 */
export function spendableBalance(balanceUsd: number, price: number): number {
  if (!Number.isFinite(balanceUsd) || balanceUsd <= 0) return 0;
  const feeShare =
    Number.isFinite(price) && price > 0 && price < 1
      ? TAKER_FEE_RATE * (1 - price)
      : BALANCE_HEADROOM;
  return balanceUsd * (1 - Math.max(BALANCE_HEADROOM, feeShare));
}

/**
 * Default size for a hand-placed limit buy.
 *
 * Five shares is the venue's floor and a sensible unit most of the book. Under
 * 20c it stops being sensible — five shares there is under a dollar of exposure
 * for the same tap — so cheap prices are sized by money instead.
 */
export function limitShares(price: number, minimumOrderSize = 5, cheapStakeUsd = 1): number {
  if (!Number.isFinite(price) || price <= 0) return minimumOrderSize;
  const base = price < 0.2 ? cheapStakeUsd / price : 5;
  return Math.max(base, minShares(price, minimumOrderSize));
}

/**
 * The smallest order the venue will take at this price.
 *
 * Two floors apply and the larger wins. The venue has a share count — five —
 * and an order value of a dollar. Under 20c the value floor is the binding one
 * and it bites hard: five shares at 5c is 25c, which is simply rejected. Sizing
 * by share count alone therefore fails silently at exactly the prices where the
 * cheap side is worth buying.
 */
export function minShares(
  price: number,
  venueMinShares = 5,
  minValueUsd = 1,
): number {
  if (!Number.isFinite(price) || price <= 0) return venueMinShares;
  return Math.max(venueMinShares, minValueUsd / price);
}

/**
 * How many shares a position can actually be offered: all of them.
 *
 * The percentage that used to come off here was covering for the wrong thing.
 * "Not enough balance" on a sell is almost always the shares already sitting
 * under a resting offer of our own, and that is now pulled before the sale
 * goes out — so there is nothing left for a haircut to protect against, and a
 * position asked to close closes.
 *
 * The rounding stays and is not cosmetic: 15.694 shown as 15.7 is more than is
 * held, and the venue refuses the order outright. Flooring at its own size
 * step is what makes "sell the position" mean the position.
 */
export function sellableShares(size: number, step = 0.01): number {
  if (!Number.isFinite(size) || size <= 0) return 0;
  // Round before flooring: 15.7 / 0.01 comes through as 1569.999... in floats,
  // and flooring that alone would quietly drop another whole step.
  const units = Math.floor(Number((size / step).toFixed(6)));
  return Number((units * step).toFixed(4));
}

/**
 * Shares for a share-of-the-balance click, rounded down to whole shares.
 *
 * Rounding down is the point: a fractional size that spends the exact percentage
 * reads as noise on a button that says 50%, and rounding up would put the order
 * over the balance the fee already narrowed.
 */
export function stakeShares(
  price: number,
  balanceUsd: number,
  sharePct: number,
  minimumOrderSize = 5,
): number | null {
  if (!Number.isFinite(price) || price <= 0) return null;
  if (!Number.isFinite(balanceUsd) || balanceUsd <= 0) return null;

  const floor = minShares(price, minimumOrderSize);
  // The fee is charged on top of the order, so the last slice of the balance
  // is never available to buy with.
  const raw = (spendableBalance(balanceUsd, price) * sharePct) / price;
  if (raw < floor) return null;
  const whole = Math.floor(raw);
  return whole >= floor ? whole : Math.ceil(floor * 100) / 100;
}

/**
 * How much of the deposit is at risk, and how much more may be.
 *
 * A five-minute window is one bet, however many orders it is made of, and the
 * rule is that no more than a quarter of the deposit may be in it. It resets
 * with the window: the next five minutes start from nothing, which is what
 * makes it a rule you can trade under rather than a budget that runs out.
 *
 * Measured against the deposit — cash plus what is already in the market —
 * because cash alone slides down with every purchase, and a cap read off it
 * never actually binds.
 *
 * What counts as at risk: shares held, at what they cost, and buy orders still
 * resting, at what they would cost. A resting buy is committed money; that it
 * has not filled yet is a timing detail, and leaving it out is how a stack of
 * limits quietly becomes the whole deposit.
 */
export type Exposure = {
  /** Money in the market: positions at cost, plus resting buys. */
  committed: number;
  /** Free cash on the exchange. */
  balance: number;
  /** Both together — the deposit the cap is a share of. */
  equity: number;
  /** The most this window may hold. */
  cap: number;
  /** What one more order may cost. Never negative. */
  room: number;
  /** Already at or over the line. */
  full: boolean;
};

/** The share of the deposit one five-minute window may hold. */
export const WINDOW_CAP_PCT = 0.25;

export function exposureFor(
  balance: number,
  committed: number,
  capPct: number = WINDOW_CAP_PCT,
): Exposure {
  const cash = Number.isFinite(balance) && balance > 0 ? balance : 0;
  const held = Number.isFinite(committed) && committed > 0 ? committed : 0;
  const equity = cash + held;
  const share = Number.isFinite(capPct) ? Math.max(0, Math.min(1, capPct)) : WINDOW_CAP_PCT;
  const cap = equity * share;
  // Never more than there is: at a quarter this cannot bind, but a share set
  // to the whole deposit would otherwise promise money already spent.
  const room = Math.max(0, Math.min(cap - held, cash));
  return { committed: held, balance: cash, equity, cap, room, full: room <= 1e-9 };
}

/**
 * The most of an order the guard will allow, in shares.
 *
 * Clamped rather than refused: a tap that buys a little less is a tap that
 * still works, and the button says what it will actually do. Null means even
 * the venue's smallest order would not fit, and the button says that instead.
 */
export function cappedShares(
  shares: number,
  price: number,
  room: number,
  minimumOrderSize = 5,
): number | null {
  if (!Number.isFinite(shares) || shares <= 0) return null;
  if (!Number.isFinite(price) || price <= 0) return null;

  const perShare = price + TAKER_FEE_RATE * price * (1 - price);
  if (shares * perShare <= room + 1e-9) return shares;

  const floor = minShares(price, minimumOrderSize);
  const fits = Math.floor((room / perShare) * 100) / 100;
  return fits >= floor ? fits : null;
}

/** What an order at this price and size actually costs, fee included. */
export function orderCost(shares: number, price: number): number {
  if (!Number.isFinite(shares) || !Number.isFinite(price)) return 0;
  return shares * price + TAKER_FEE_RATE * price * (1 - price) * shares;
}
