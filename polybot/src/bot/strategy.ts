import { TWAP_WINDOW_SECONDS } from '../core/config';
import { clamp, normalCdf, perSecondVolatility } from '../core/stats';
import type { Tick } from '../feed/rtds';

export type StrategyMode = 'edge' | 'momentum' | 'contrarian' | 'off';

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
  /** Never pay less than this per share (a quote this cheap is usually stale). */
  minPrice: number;
  /** Raise the stake when $stake would buy fewer than the venue's minimum. */
  autoBumpToMinimum: boolean;
  /** Sign and log orders without sending them. */
  dryRun: boolean;
  /** Stop trading once the session is down this much, in USDC. */
  dailyLossLimitUsd: number;
  /** Stop trading after this many losing settlements in a row. */
  maxConsecutiveLosses: number;
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
};

export type MarketQuote = {
  /** Cheapest price at which the full stake can be bought, per side. */
  upAsk: number | null;
  downAsk: number | null;
};

export type Decision =
  | { act: false; reason: string; pUp: number | null }
  | {
      act: true;
      side: 'Up' | 'Down';
      price: number;
      pUp: number;
      edge: number;
      reason: string;
    };

export type FairValueInput = {
  strike: number;
  spot: number;
  /** Milliseconds until the window closes. */
  msToClose: number;
  ticks: Tick[];
};

export type FairValue = {
  pUp: number;
  sigmaPerSec: number;
  /** Standard deviation of the settlement price, in USD. */
  sigmaHorizon: number;
  drift: number;
};

/**
 * Probability that the settlement TWAP lands at or above the strike.
 *
 * The market resolves on a 30-second average taken at the end of the window,
 * so the effective horizon runs to the centre of that average rather than to
 * the close. Averaging also damps the terminal variance; over a window this
 * short the correction is small, and a factor of sqrt(1 - w/(3T)) captures it
 * closely enough for a pricing screen.
 */
export function fairValue(input: FairValueInput): FairValue | null {
  const sigmaPerSec = perSecondVolatility(input.ticks);
  if (sigmaPerSec === null) return null;

  const secondsToClose = input.msToClose / 1000;
  const horizon = Math.max(secondsToClose - TWAP_WINDOW_SECONDS / 2, 1);
  const twapDamping = Math.sqrt(
    clamp(1 - TWAP_WINDOW_SECONDS / (3 * Math.max(secondsToClose, 1)), 0.25, 1),
  );

  const sigmaHorizon =
    sigmaPerSec * Math.sqrt(horizon) * input.spot * twapDamping;
  if (!(sigmaHorizon > 0)) return null;

  const drift = input.spot - input.strike;
  const pUp = clamp(normalCdf(drift / sigmaHorizon), 0.0001, 0.9999);

  return { pUp, sigmaPerSec, sigmaHorizon, drift };
}

export function decide(
  settings: StrategySettings,
  fv: FairValue | null,
  quote: MarketQuote,
): Decision {
  if (settings.mode === 'off') {
    return { act: false, reason: 'режим "не торговать"', pUp: fv?.pUp ?? null };
  }
  if (!fv) {
    return {
      act: false,
      reason: 'мало тиков Chainlink для оценки волатильности',
      pUp: null,
    };
  }

  const inBand = (p: number | null): p is number =>
    p !== null && p >= settings.minPrice && p <= settings.maxPrice;

  const candidates: { side: 'Up' | 'Down'; price: number; edge: number }[] = [];
  if (inBand(quote.upAsk)) {
    candidates.push({ side: 'Up', price: quote.upAsk, edge: fv.pUp - quote.upAsk });
  }
  if (inBand(quote.downAsk)) {
    candidates.push({
      side: 'Down',
      price: quote.downAsk,
      edge: 1 - fv.pUp - quote.downAsk,
    });
  }

  if (candidates.length === 0) {
    return {
      act: false,
      reason: `нет котировок в коридоре ${settings.minPrice}–${settings.maxPrice}`,
      pUp: fv.pUp,
    };
  }

  if (settings.mode === 'momentum' || settings.mode === 'contrarian') {
    const leading: 'Up' | 'Down' = fv.drift >= 0 ? 'Up' : 'Down';
    const wanted =
      settings.mode === 'momentum'
        ? leading
        : leading === 'Up'
          ? 'Down'
          : 'Up';
    const pick = candidates.find((c) => c.side === wanted);
    if (!pick) {
      return {
        act: false,
        reason: `сторона ${wanted} вне ценового коридора`,
        pUp: fv.pUp,
      };
    }
    return {
      act: true,
      side: pick.side,
      price: pick.price,
      pUp: fv.pUp,
      edge: pick.edge,
      reason:
        settings.mode === 'momentum'
          ? `по тренду: BTC ${fv.drift >= 0 ? 'выше' : 'ниже'} страйка на ${fv.drift.toFixed(2)}$`
          : `против тренда: берём отстающую сторону`,
    };
  }

  const best = candidates.sort((a, b) => b.edge - a.edge)[0];
  if (best.edge < settings.minEdge) {
    return {
      act: false,
      reason: `лучшее преимущество ${(best.edge * 100).toFixed(1)}% < порога ${(settings.minEdge * 100).toFixed(1)}%`,
      pUp: fv.pUp,
    };
  }

  return {
    act: true,
    side: best.side,
    price: best.price,
    pUp: fv.pUp,
    edge: best.edge,
    reason: `модель даёт ${((best.side === 'Up' ? fv.pUp : 1 - fv.pUp) * 100).toFixed(1)}% против цены ${(best.price * 100).toFixed(0)}¢`,
  };
}
