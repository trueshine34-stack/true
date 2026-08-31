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
  /**
   * Whether a fill makes a sound.
   *
   * A window is decided while the phone is in a pocket, so the report is read
   * afterwards and the moment itself is missed. Up rises, Down falls, and a
   * sale rings like a coin — three shapes that are told apart without
   * looking, which is the whole point.
   */
  chime: boolean;
  /**
   * Hold the ladder at its first rung for a side the book once wrote off.
   *
   * A side that traded under a third has been given up on, and if it comes
   * back it comes back late — while the ladder has walked up with the clock
   * and is asking ninety-six by the fourth minute, so the price the recovery
   * actually reaches is one nothing is offered at.
   */
  autoSellDipRescue: boolean;
  autoSellEnabled: boolean;
  /**
   * Sell price by step of the window, cheapest rung first.
   *
   * Ten of them, one every thirty seconds, which is what it takes to cover a
   * five-minute window at that step. Five rungs were spent by the halfway
   * mark and the ladder stopped being a ladder exactly when the window began
   * to decide.
   */
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
  /** How far ahead of each boundary the next rung takes over. */
  autoSellLeadSec: number;
  /**
   * How long each rung holds before the clock moves to the next one.
   *
   * Thirty seconds spends the five rungs by the halfway mark, asking the
   * higher prices while there is still time for the market to reach them. A
   * minute spreads them over the whole window instead.
   */
  autoSellStepSec: number;
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

/**
 * The same ladder at a finer resolution.
 *
 * The rungs used to be one a minute and are one every thirty seconds now,
 * which needs twice as many of them to reach the close. A ladder the user has
 * edited is theirs, so it is not replaced with the default — it is resampled:
 * the same curve, from the same first price to the same last one, read at
 * twice as many points.
 *
 * Rounded to the cent, because that is what the venue trades in, and kept
 * climbing: two rungs at the same price are one rung with a gap after it.
 */
export function stretchLadder(ladder: number[], count: number): number[] {
  const clean = ladder.filter((p) => Number.isFinite(p) && p > 0 && p < 1);
  if (clean.length < 2 || count < 2 || clean.length >= count) return [...clean];

  const span = clean.length - 1;
  const out: number[] = [];
  for (let i = 0; i < count; i++) {
    const at = (i / (count - 1)) * span;
    const low = Math.min(Math.floor(at), span - 1);
    const part = at - low;
    const value = clean[low] + (clean[low + 1] - clean[low]) * part;
    const cents = Math.round(value * 100) / 100;
    // Never below the rung before it, and never level with it while there is
    // room above: a ladder that stops climbing has stopped being one.
    const last = out[out.length - 1];
    out.push(last === undefined ? cents : Math.max(cents, last));
  }
  // The top is the top, whatever the rounding did on the way.
  out[out.length - 1] = clean[span];
  return out;
}

export const DEFAULT_MANUAL_SETTINGS: ManualSettings = {
  chime: true,
  autoSellDipRescue: true,
  autoSellEnabled: false,
  autoSellLadder: [
    0.77, 0.8, 0.83, 0.86, 0.88, 0.9, 0.92, 0.94, 0.96, 0.97,
  ],
  autoSellRetrySec: 3,
  autoSellWatchSec: 60,
  autoSellLeadSec: 15,
  autoSellStepSec: 30,
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
  /** The most this window may hold, and the share of the deposit that is. */
  cap: number;
  pct: number;
  /** What one more order may cost. Never negative. */
  room: number;
  /** Already at or over the line. */
  full: boolean;
};

/**
 * The share of the deposit one five-minute window may hold, by deposit.
 *
 * A quarter of fifty dollars is twelve, and losing it is an afternoon. A
 * quarter of ten thousand is two and a half thousand, and losing that on one
 * five-minute window is not the same kind of event at all — the rule has to
 * get stricter as there is more to lose, or it stops being a rule about risk
 * and becomes a rule about arithmetic.
 *
 * So the share falls as the deposit grows: a quarter up to fifty dollars, one
 * percent from ten thousand, and a straight line between them in log-log —
 * which means it falls by the same proportion for every doubling, rather than
 * by cliffs the account can be nudged over. In money it still rises the whole
 * way: $12.50 a window at $50, $31 at $500, $100 at $10,000.
 */
export const CAP_TOP_PCT = 0.25;
export const CAP_TOP_USD = 50;
export const CAP_LOW_PCT = 0.01;
export const CAP_LOW_USD = 10_000;

export function windowCapPct(equity: number): number {
  if (!Number.isFinite(equity) || equity <= CAP_TOP_USD) return CAP_TOP_PCT;
  if (equity >= CAP_LOW_USD) return CAP_LOW_PCT;
  const t = Math.log(equity / CAP_TOP_USD) / Math.log(CAP_LOW_USD / CAP_TOP_USD);
  return CAP_TOP_PCT * (CAP_LOW_PCT / CAP_TOP_PCT) ** t;
}

/**
 * Room the cap never takes away.
 *
 * A window that has filled its share is a reason to size down, not a reason to
 * be unable to trade it at all — and on a small deposit a quarter is less than
 * the venue's own smallest order, which would mean the cap forbidding every
 * trade there is. So there is always this much to work with, as long as the
 * money is actually there.
 */
export const MIN_ROOM_USD = 3.5;

export function exposureFor(
  balance: number,
  committed: number,
  /** Overrides the share the deposit would otherwise earn. */
  capPct?: number,
): Exposure {
  const cash = Number.isFinite(balance) && balance > 0 ? balance : 0;
  const held = Number.isFinite(committed) && committed > 0 ? committed : 0;
  const equity = cash + held;
  const wanted = capPct ?? windowCapPct(equity);
  const share = Number.isFinite(wanted) ? Math.max(0, Math.min(1, wanted)) : CAP_TOP_PCT;
  const cap = equity * share;
  // At least the floor, never more than there is in cash.
  const room = Math.max(0, Math.min(Math.max(cap - held, MIN_ROOM_USD), cash));
  return { committed: held, balance: cash, equity, cap, pct: share, room, full: room <= 1e-9 };
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

/**
 * The dearest a buy may be this early in the window.
 *
 * A side that already costs 63c in the first minute is being paid for a move
 * that has barely started: five minutes is long enough for it to come back,
 * and the shares bought at that price have little left to gain and most of a
 * dollar to lose. The ceiling lifts as the window runs out of time to reverse
 * — 77c through the third minute, and nothing after it, when a dear side is
 * dear because it has nearly won.
 *
 * It is a floor on judgement, not a strategy: nothing in the app, by hand or
 * by rule, sends a buy above it.
 */
export const CAP_FIRST_MIN_SEC = 60;
export const CAP_EARLY_SEC = 180;
export const CAP_LAST_MIN_SEC = 240;
export const CAP_FIRST_MIN = 0.63;
export const CAP_EARLY = 0.77;
export const CAP_LAST_MIN = 0.91;

export function buyCeiling(elapsedSec: number): number {
  if (!Number.isFinite(elapsedSec)) return CAP_FIRST_MIN;
  if (elapsedSec < CAP_FIRST_MIN_SEC) return CAP_FIRST_MIN;
  if (elapsedSec < CAP_EARLY_SEC) return CAP_EARLY;
  if (elapsedSec < CAP_LAST_MIN_SEC) return 1;
  return CAP_LAST_MIN;
}

/** Whether a buy at this price is barred by the ceiling above. */
export function buyBarred(price: number, elapsedSec: number): boolean {
  return price > buyCeiling(elapsedSec) + 1e-9;
}

/**
 * How far the window has come from the price it has to beat, written the way
 * Polymarket writes it over its own chart.
 *
 * The venue prints two prices and one arrow: the target, which is where the
 * window opened, the current price, and the whole-dollar distance between
 * them. That last figure is the bet — Up wins if it is positive at the close
 * — so it is the one that gets the arrow and the colour, and it is rounded
 * exactly as the venue rounds it so the two screens never disagree by a cent.
 */
export interface OpenMark {
  way: 'up' | 'down' | 'flat';
  /** ▲, ▼, or an en dash when the rounded change is zero. */
  arrow: string;
  /** The change, in whole dollars, unsigned: "$11". */
  text: string;
  /** The signed change itself, for anything that needs the number. */
  change: number;
}

export function openMark(
  target: number | null | undefined,
  price: number | null | undefined,
): OpenMark | null {
  if (target == null || price == null) return null;
  if (!Number.isFinite(target) || !Number.isFinite(price)) return null;
  if (target <= 0 || price <= 0) return null;

  const change = price - target;
  const whole = Math.round(Math.abs(change));
  // Under half a dollar the venue shows nothing either way, and an arrow on a
  // change that rounds to zero would claim a direction the price has not
  // taken.
  const way = whole === 0 ? 'flat' : change > 0 ? 'up' : 'down';
  return {
    way,
    arrow: way === 'up' ? '▲' : way === 'down' ? '▼' : '–',
    text: `$${whole.toLocaleString('ru-RU')}`,
    change,
  };
}

/** A dollar price with the thousands grouped, as the venue prints it. */
export function bigPrice(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value <= 0) return '—';
  return value.toLocaleString('ru-RU', { maximumFractionDigits: 0 });
}
