import {
  BTC_5M_SLUG_PREFIX,
  GAMMA_HOST,
  WINDOW_SECONDS,
} from '../core/config';
import type { Btc5mMarket } from './types';

/**
 * Market discovery for the recurring 5-minute BTC series.
 *
 * Each window is its own Gamma event with a fully deterministic slug —
 * `btc-updown-5m-<unix seconds of the window open>` — so we can address the
 * next market directly instead of paging a listing that filters these out.
 */

type GammaMarket = {
  conditionId: string;
  question: string;
  slug: string;
  outcomes: string;
  clobTokenIds: string;
  orderPriceMinTickSize?: number;
  orderMinSize?: number;
  acceptingOrders?: boolean;
  enableOrderBook?: boolean;
  negRisk?: boolean;
  closed?: boolean;
};

type GammaEvent = {
  slug: string;
  title: string;
  markets: GammaMarket[];
};

/** Unix seconds of the 5-minute window containing `atMs`. */
export function windowStartFor(atMs: number = Date.now()): number {
  return Math.floor(atMs / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS;
}

export function slugForWindow(windowStart: number): string {
  return `${BTC_5M_SLUG_PREFIX}${windowStart}`;
}

function parseJsonArray(raw: string | undefined): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(String) : [];
  } catch {
    return [];
  }
}

export async function fetchMarketForWindow(
  windowStart: number,
): Promise<Btc5mMarket | null> {
  const slug = slugForWindow(windowStart);
  const url = `${GAMMA_HOST}/events?slug=${encodeURIComponent(slug)}`;
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`gamma ${res.status} for ${slug}`);

  const events = (await res.json()) as GammaEvent[];
  const event = Array.isArray(events) ? events[0] : null;
  const market = event?.markets?.[0];
  if (!event || !market) return null;

  const outcomes = parseJsonArray(market.outcomes);
  const tokenIds = parseJsonArray(market.clobTokenIds);
  if (outcomes.length < 2 || tokenIds.length < 2) return null;

  const upIndex = outcomes.findIndex((o) => o.toLowerCase() === 'up');
  const downIndex = outcomes.findIndex((o) => o.toLowerCase() === 'down');
  if (upIndex < 0 || downIndex < 0) return null;

  return {
    slug: event.slug,
    conditionId: market.conditionId,
    question: market.question ?? event.title,
    windowStart,
    windowEnd: windowStart + WINDOW_SECONDS,
    negRisk: Boolean(market.negRisk),
    tickSize: market.orderPriceMinTickSize ?? 0.01,
    minimumOrderSize: market.orderMinSize ?? 5,
    acceptingOrders:
      (market.acceptingOrders ?? true) &&
      (market.enableOrderBook ?? true) &&
      !market.closed,
    up: { label: 'Up', tokenId: tokenIds[upIndex] },
    down: { label: 'Down', tokenId: tokenIds[downIndex] },
  };
}

/**
 * The market for the window that is about to open. Polymarket pre-lists the
 * series roughly a day ahead, so this resolves well before the bell.
 */
export function nextWindowStart(atMs: number = Date.now()): number {
  return windowStartFor(atMs) + WINDOW_SECONDS;
}
