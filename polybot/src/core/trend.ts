import type { Candle } from './candles';

/**
 * Where the last half hour has actually been going.
 *
 * Eyes are bad at this. A run of green candles inside a range looks like a
 * trend, and a slow drift under noisy wicks does not look like one at all —
 * so the line is fitted rather than judged: least squares through the closes
 * of the span, which is the direction the whole of it agrees on.
 *
 * Two numbers come out of it and both matter. The slope says which way and how
 * fast, in dollars an hour, which is a rate a five-minute bet can be measured
 * against. The fit says whether the word "trend" applies at all: price that
 * wanders around the line as much as it follows it is chop, and calling chop a
 * direction is worse than saying nothing.
 */
export interface Trend {
  /** Signed dollars an hour. */
  perHour: number;
  /** Which way, with chop called chop. */
  way: 'up' | 'down' | 'flat';
  /** How much of the movement the line explains, 0..1. */
  fit: number;
  /** The fitted line's price at the start and at the end of the span. */
  from: number;
  to: number;
  /** The first candle the line covers. */
  fromIndex: number;
}

/**
 * A line has to explain this much of the movement, and carry the span this far
 * against its own noise, before it is a direction rather than a drift.
 */
const MIN_FIT = 0.15;
const MIN_TRAVEL = 0.2;

/**
 * How long each chart's line looks back, in minutes.
 *
 * An hour on the five-minute chart, which is twelve points. Three hours is a
 * steadier fit, but it is a fit to the session, and by the time it has turned
 * the move it was describing is over — a five-minute bet is decided by what
 * price is doing now, not by where the afternoon started.
 */
export const WIDE_MINUTES = 30;

/**
 * And the close view: a quarter of an hour.
 *
 * It was half an hour, and half an hour is three windows of history deciding
 * one window's bet — by the time a turn shows up in a thirty-minute fit, five
 * minutes of it have already happened. Fifteen points is still enough to fit
 * a line through, and few enough that the line is about now. The wide view is
 * half an hour for the same reason: it is there to say whether the wider
 * frame disagrees, and for that it has to be looking at the same tape.
 */
export const NEAR_MINUTES = 15;

export function trendOf(candles: Candle[], overMinutes: number): Trend | null {
  const clean = candles.filter(([t, o, h, l, c]) => t > 0 && o > 0 && h > 0 && l > 0 && c > 0);
  if (clean.length < 4) return null;

  const step = clean.length > 1 ? clean[1][0] - clean[0][0] : 60;
  if (!(step > 0)) return null;

  const want = Math.max(4, Math.round((overMinutes * 60) / step));
  const use = clean.slice(-Math.min(clean.length, want));
  const n = use.length;

  // Least squares over the closes, with x as the candle's place in the span.
  let sx = 0;
  let sy = 0;
  for (let i = 0; i < n; i++) {
    sx += i;
    sy += use[i][4];
  }
  const mx = sx / n;
  const my = sy / n;

  let sxy = 0;
  let sxx = 0;
  for (let i = 0; i < n; i++) {
    sxy += (i - mx) * (use[i][4] - my);
    sxx += (i - mx) ** 2;
  }
  if (!(sxx > 0)) return null;

  const slope = sxy / sxx;
  const intercept = my - slope * mx;

  // How much of the movement the line accounts for.
  let ssTot = 0;
  let ssRes = 0;
  for (let i = 0; i < n; i++) {
    const fitted = intercept + slope * i;
    ssTot += (use[i][4] - my) ** 2;
    ssRes += (use[i][4] - fitted) ** 2;
  }
  const fit = ssTot > 0 ? Math.max(0, 1 - ssRes / ssTot) : 0;

  const from = intercept;
  const to = intercept + slope * (n - 1);
  const travel = to - from;

  // Against the range it happened in: a dollar of drift inside a fifty-dollar
  // range is not a direction, and the same dollar inside a two-dollar one is.
  let low = Infinity;
  let high = -Infinity;
  for (const [, , h, l] of use) {
    if (l < low) low = l;
    if (h > high) high = h;
  }
  const range = high - low;
  const strong = fit >= MIN_FIT && range > 0 && Math.abs(travel) >= range * MIN_TRAVEL;

  return {
    perHour: (slope * 3600) / step,
    way: !strong ? 'flat' : travel > 0 ? 'up' : 'down',
    fit,
    from,
    to,
    fromIndex: clean.length - n,
  };
}

/**
 * The level price is heading into.
 *
 * Going up, that is the nearest one overhead; going down, the nearest one
 * underneath. Sideways there is no "into" — both are just levels — and saying
 * one of them is the target would be inventing a direction the fit refused to
 * find.
 */
export function levelAhead(
  levels: { price: number }[],
  last: number,
  way: Trend['way'],
): number | null {
  if (way === 'flat' || !(last > 0)) return null;
  const side =
    way === 'up'
      ? levels.filter((l) => l.price > last)
      : levels.filter((l) => l.price < last);
  if (side.length === 0) return null;
  return side.reduce((best, l) =>
    Math.abs(l.price - last) < Math.abs(best.price - last) ? l : best,
  ).price;
}

/** A rate the way a desk says it: signed, whole dollars an hour. */
export function ratePerHour(perHour: number): string {
  const sign = perHour > 0 ? '+' : perHour < 0 ? '−' : '';
  return `${sign}${Math.round(Math.abs(perHour)).toLocaleString('ru-RU')}$/ч`;
}
