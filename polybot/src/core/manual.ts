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
  autoRebuyEnabled: false,
  autoRebuyDropPct: 0.2,
  autoRebuySlicePauseSec: 3,
  useBalanceShare: false,
  balanceSharePct: 0.25,
};

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

  const shares = (balanceUsd * sharePct) / price;
  return shares >= minimumOrderSize ? shares : null;
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
  return Math.max(base, minimumOrderSize);
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

  const fromStake = settings.defaultStakeUsd / price;
  if (!settings.useSizeLadder) return Math.max(fromStake, minimumOrderSize);

  const bands = [...settings.sizeRules].sort((a, b) => a.maxPrice - b.maxPrice);
  const band = bands.find((r) => price <= r.maxPrice + 1e-9);
  return Math.max(band ? band.shares : fromStake, minimumOrderSize);
}
