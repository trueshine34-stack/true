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
  autoSellPrice: number;
  autoSellRetrySec: number;
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
  autoSellPrice: 0.97,
  autoSellRetrySec: 7,
};

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
