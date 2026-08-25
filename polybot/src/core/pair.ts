import type { PairSettings } from '../native/polybot';

/**
 * Defaults for the pair strategy.
 *
 * A pair of Up and Down settles to exactly $1 whichever way the window goes,
 * so the whole strategy is about assembling pairs for less than that. These
 * numbers follow the worked example: 5-share lots on the side under 50¢ every
 * 10–20 seconds, and a pair that is never allowed to cost more than 95¢.
 */
export const DEFAULT_PAIR_SETTINGS: PairSettings = {
  dryRun: true,
  lotShares: 5,
  minIntervalSec: 10,
  maxIntervalSec: 20,
  maxSeedPrice: 0.5,
  maxPairAvg: 0.95,
  minPairProfitPct: 0.03,
  rotateProfitPct: 0.1,
  cheapLegUnder: 0.5,
  cheapRotateProfitPct: 0.05,
  rotateFraction: 0.5,
  takerEntry: false,
  maxExposureUsd: 20,
  maxImbalanceShares: 20,
  flattenSec: 40,
};
