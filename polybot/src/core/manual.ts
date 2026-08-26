/**
 * Settings for the manual desk.
 *
 * The size ladder exists because a fixed share count means something different
 * at 10¢ than at 90¢: the same five shares risk 50¢ on one and $4.50 on the
 * other, while the cheap one has far more room to run. Sizing by price band
 * keeps the money at risk roughly level across the book.
 */
export type SizeRule = {
  /** Applies when the price is at or below this, in dollars. */
  maxPrice: number;
  shares: number;
};

export type ManualSettings = {
  /** Used when no band matches, and as the basis for the "на сумму" chip. */
  defaultStakeUsd: number;
  /** Turn the ladder off to size every click from `defaultStakeUsd`. */
  useSizeLadder: boolean;
  sizeRules: SizeRule[];
  autoSellEnabled: boolean;
  /** Sell price by minute of the window, cheapest rung first. */
  autoSellLadder: number[];
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
  /** Inside this much of the close, any profit will do. */
  autoSellPanicSec: number;
  /** Buy the same size back if the price falls far enough after a sale. */
  autoRebuyEnabled: boolean;
  /** How far below the sale price the buy-back triggers, as a fraction. */
  autoRebuyDropPct: number;
  /** Pause between buy-back slices, so a deeper dip can still be caught. */
  autoRebuySlicePauseSec: number;
  /** Size a click off the wallet instead of the price ladder. */
  useBalanceShare: boolean;
  /** Share of the balance one click spends, as a fraction. */
  balanceSharePct: number;
  /** Refuse to have more than a set share of the deposit at risk at once. */
  exposureGuard: boolean;
  /** That share, as a fraction of the deposit. */
  exposureCapPct: number;
};

export const DEFAULT_MANUAL_SETTINGS: ManualSettings = {
  defaultStakeUsd: 2,
  useSizeLadder: true,
  sizeRules: [
    { maxPrice: 0.2, shares: 25 },
    { maxPrice: 0.4, shares: 15 },
    { maxPrice: 0.6, shares: 10 },
    { maxPrice: 1, shares: 5 },
  ],
  autoSellEnabled: false,
  autoSellLadder: [0.77, 0.84, 0.89, 0.93, 0.97],
  autoSellRetrySec: 7,
  autoSellWatchSec: 60,
  autoSellLeadSec: 15,
  autoSellPercentMode: false,
  autoSellProfitPct: 0.2,
  autoSellSliceGapSec: 2,
  autoSellPanicSec: 60,
  autoRebuyEnabled: false,
  autoRebuyDropPct: 0.2,
  autoRebuySlicePauseSec: 3,
  useBalanceShare: false,
  balanceSharePct: 0.25,
  exposureGuard: true,
  exposureCapPct: 0.5,
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
 * Shares a click buys when sizing off the wallet.
 *
 * Returns null when the share of the balance cannot even reach the venue's
 * floor — the caller has to decide whether to round up and say so or refuse,
 * and silently spending more than the set share would be the wrong call to make
 * here.
 */
export function balanceShares(
  price: number,
  balanceUsd: number,
  sharePct: number,
  minimumOrderSize = 5,
): number | null {
  if (!Number.isFinite(price) || price <= 0) return null;
  if (!Number.isFinite(balanceUsd) || balanceUsd <= 0) return null;

  const shares = (spendableBalance(balanceUsd, price) * sharePct) / price;
  return shares >= minShares(price, minimumOrderSize) ? shares : null;
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
 * Shares to buy at this price. The first band whose ceiling the price is under
 * wins, so the list reads top to bottom as "cheap gets more".
 */
export function sharesFor(
  price: number,
  settings: ManualSettings,
  minimumOrderSize = 5,
): number {
  if (!Number.isFinite(price) || price <= 0) return 0;

  const floor = minShares(price, minimumOrderSize);
  const fromStake = settings.defaultStakeUsd / price;
  if (!settings.useSizeLadder) return Math.max(fromStake, floor);

  const bands = [...settings.sizeRules].sort((a, b) => a.maxPrice - b.maxPrice);
  const band = bands.find((r) => price <= r.maxPrice + 1e-9);
  return Math.max(band ? band.shares : fromStake, floor);
}

/** The least a sell is trimmed by, whatever the fee works out to. */
export const SELL_HEADROOM = 0.03;

/**
 * How much smaller than the reported size a sell order has to be.
 *
 * Polymarket takes its fee out of what the trade pays out, so a buy delivers
 * fewer shares than it asked for: `rate x p x (1 - p)` per share in dollars is
 * `rate x (1 - p)` of the share count. At 50c that is three and a half percent,
 * which is why a position reported as 15.7 cannot sell 15.7. Three percent is
 * the floor; where the fee is larger — anything under about 57c — the fee wins,
 * because trimming less would be refused exactly as before.
 */
export function sellHeadroom(price?: number): number {
  if (price == null || !Number.isFinite(price) || price <= 0 || price >= 1) {
    return SELL_HEADROOM;
  }
  return Math.max(SELL_HEADROOM, TAKER_FEE_RATE * (1 - price));
}

/**
 * How many shares a position can actually be sold for.
 *
 * Two things make the reported size unsellable. The fee above is one. The other
 * is display rounding: rounding a size of 15.69 to a tenth gives 15.7, more than
 * is held, and the venue simply refuses it. So the trim comes off first and the
 * result always rounds *down*.
 *
 * A position too small to trim is offered whole: selling slightly too much is
 * refused, but so is selling nothing, and the untrimmed size is the one with a
 * chance of clearing the venue floor.
 */
export function sellableShares(
  size: number,
  price?: number,
  step = 0.1,
): number {
  if (!Number.isFinite(size) || size <= 0) return 0;
  const trimmed = size * (1 - sellHeadroom(price));
  // Round before flooring: 15.7 / 0.1 comes through as 156.999... in floats,
  // and flooring that alone would quietly drop another whole tenth.
  const units = Math.floor(Number((trimmed / step).toFixed(6)));
  const snapped = units * step;
  return snapped > 0
    ? Number(snapped.toFixed(4))
    : Number((Math.floor(Number((size / step).toFixed(6))) * step).toFixed(4));
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
  const raw = balanceShares(price, balanceUsd, sharePct, minimumOrderSize);
  if (raw == null) return null;

  const floor = minShares(price, minimumOrderSize);
  const whole = Math.floor(raw);
  return whole >= floor ? whole : Math.ceil(floor * 100) / 100;
}

/**
 * How much of the deposit is at risk, and how much more may be.
 *
 * "Half the deposit" cannot mean half the balance: the balance falls as you
 * buy, so a cap read off it slides down with every purchase and never actually
 * binds. The deposit is what is on the exchange *plus* what is already in the
 * market — cash and positions are the same money in different shapes — and the
 * cap is measured against that.
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
  cap: number;
  /** What one more order may cost. Never negative. */
  room: number;
  /** Already at or over the line. */
  full: boolean;
};

export function exposureFor(
  balance: number,
  committed: number,
  capPct: number,
): Exposure {
  const cash = Number.isFinite(balance) && balance > 0 ? balance : 0;
  const held = Number.isFinite(committed) && committed > 0 ? committed : 0;
  const equity = cash + held;
  const cap = equity * Math.max(0, Math.min(1, capPct));
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
