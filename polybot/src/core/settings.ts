export type StrategyMode = 'edge' | 'momentum' | 'contrarian' | 'off';

/**
 * Trading configuration. The model that consumes it runs in the native
 * service; this is only the shape the UI edits and persists.
 */
export type StrategySettings = {
  mode: StrategyMode;
  /** USDC per trade. */
  stakeUsd: number;
  /** Seconds after the window opens before the decision is taken. */
  entryDelaySec: number;
  /** Minimum modelled edge, in probability points, for `edge` mode. */
  minEdge: number;
  /** Never pay more than this per share. */
  maxPrice: number;
  /** Never pay less than this per share — a quote this cheap is usually stale. */
  minPrice: number;
  /** Raise the stake when $stake would buy fewer than the venue's minimum. */
  autoBumpToMinimum: boolean;
  /** Sign and log orders without sending them. */
  dryRun: boolean;
  /** Stop trading once the session is down this much, in USDC. */
  dailyLossLimitUsd: number;
  /** Stop trading after this many losing settlements in a row. */
  maxConsecutiveLosses: number;
  /** Park a resting sell on the position as soon as it is filled. */
  exitEnabled: boolean;
  /** Price of the resting sell for most of the window. */
  exitPriceEarly: number;
  /** Price it is moved to for the closing stretch. */
  exitPriceLate: number;
  /** Seconds before the close at which the sell is repriced. */
  exitSwitchSec: number;
};

export const DEFAULT_SETTINGS: StrategySettings = {
  mode: 'edge',
  stakeUsd: 2,
  entryDelaySec: 20,
  minEdge: 0.04,
  maxPrice: 0.9,
  minPrice: 0.05,
  autoBumpToMinimum: true,
  dryRun: true,
  dailyLossLimitUsd: 20,
  maxConsecutiveLosses: 6,
  exitEnabled: true,
  exitPriceEarly: 0.97,
  exitPriceLate: 0.99,
  exitSwitchSec: 30,
};
