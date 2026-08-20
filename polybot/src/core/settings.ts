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
  /** Park a resting sell on the position once it is filled. */
  exitEnabled: boolean;
  /**
   * Delay between the buy filling and the sell going out. Shares are not
   * sellable the instant the buy matches, so an immediate sell is rejected.
   */
  exitDelaySec: number;
  /** Sell price by elapsed second of the window, cheapest rung first. */
  exitLadder: ExitStep[];
};

/** One rung: from `fromSec` into the window, rest the sell at `price`. */
export type ExitStep = { fromSec: number; price: number };

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
  exitDelaySec: 21,
  exitLadder: [
    { fromSec: 0, price: 0.89 },
    { fromSec: 180, price: 0.93 },
    { fromSec: 240, price: 0.96 },
    { fromSec: 270, price: 0.99 },
  ],
};
