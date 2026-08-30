import type { Candle } from './candles';
import type { Level } from './levels';
import type { Trend } from './trend';

/**
 * Where price is likely to go next, drawn rather than guessed at.
 *
 * A forecast on a chart is only worth having if it can be wrong in public, so
 * this one is made of things that can be checked afterwards: the line the
 * chart is already fitting, the volume behind it, the prices the market has
 * turned at before, and the resting size in the book right now. Nothing here
 * predicts news. What it says is "if the last hour keeps meaning what it has
 * been meaning, this is the path" — and the panel keeps the old ones on screen
 * so that claim can be judged against what actually happened.
 *
 * Four things shape the path:
 *
 *  - **The trend.** Its slope is the drift, one candle at a time. But a slope
 *    measured over an hour does not survive an hour, so each step keeps a
 *    little less of it than the one before.
 *  - **Volume.** A move on rising volume extends and decays slowly; the same
 *    move on drying volume is a move that has run out of people. Volume sets
 *    both how far each step goes and how fast the drift fades.
 *  - **Mean reversion.** Price that has run well ahead of its own fitted line
 *    tends to come back to it. The residual is paid back a fraction at a time,
 *    which is what puts the hook in the front of the path.
 *  - **Barriers.** Prices the market has already turned at, and the heaviest
 *    resting size in the book. A path that arrives at one stalls there unless
 *    it has the volume to argue — which is exactly the "reversal expected
 *    here" line, said as a shape instead of a number.
 *
 * And the thing that is easy to leave out and matters most: the path is not a
 * promise. Every point carries a band that widens with the square root of
 * time, because uncertainty compounds that way, so five candles out the honest
 * answer is a cone and not a line.
 */

export interface ForecastPoint {
  /** Open time of the interval this point is about, in seconds. */
  time: number;
  price: number;
  /** The band: where the path could reasonably be by then. */
  hi: number;
  lo: number;
}

export interface Forecast {
  points: ForecastPoint[];
  way: 'up' | 'down' | 'flat';
  /** How much of the drift survived the checks, 0..1. */
  confidence: number;
  /** Where the path ends up. */
  target: number;
  /** The price it expects to run out at, when something is in the way. */
  wall: number | null;
}

/** The order book as the depth panel already reads it. */
export interface BookRead {
  bid: number;
  ask: number;
  /** How far from the mid the buckets reach, as a fraction of price. */
  span: number;
  bids: number[];
  asks: number[];
}

/** How much of the drift one step passes to the next, at ordinary volume. */
const DECAY = 0.88;

/** And how much of the way back to the line each step pays off. */
const REVERT = 0.25;

/** Never pay back more than this much of the residual over the whole path. */
const REVERT_CAP = 0.6;

/** How wide the band is at one step out, against a typical candle's range. */
const SPREAD = 0.45;

/** Overshoot allowed past a barrier, against a typical candle's range. */
const PIERCE = 0.25;

/** What the drift is worth once the path has stalled at a barrier. */
const STALLED = 0.35;

/** A bucket has to be this much heavier than the average to be a wall. */
const WALL_WEIGHT = 1.8;

const mean = (xs: number[]) =>
  xs.length === 0 ? 0 : xs.reduce((a, b) => a + b, 0) / xs.length;

const clamp = (v: number, lo: number, hi: number) =>
  Math.min(hi, Math.max(lo, v));

/**
 * How hard the recent tape is pushing, against its own normal.
 *
 * One is ordinary. Above one, the last few candles traded more than the hours
 * behind them and a move has people behind it; below one, it is drifting on
 * nobody.
 */
export function volumePush(candles: Candle[], recent = 3, over = 12): number {
  const vols = candles.map((c) => c[5] ?? 0).filter((v) => v > 0);
  if (vols.length < recent + 2) return 1;
  const now = mean(vols.slice(-recent));
  const base = mean(vols.slice(-over - recent, -recent));
  if (!(base > 0) || !(now > 0)) return 1;
  return clamp(now / base, 0.3, 3);
}

/** What one candle of this series usually travels. */
export function typicalRange(candles: Candle[], over = 12): number {
  const spans = candles
    .filter(([, , h, l]) => h > 0 && l > 0 && h >= l)
    .slice(-over)
    .map(([, , h, l]) => h - l);
  return mean(spans);
}

/**
 * The prices where the book is stacked, if it is stacked anywhere.
 *
 * The buckets walk away from the mid, so a heavy one is a price a move has to
 * eat through before it can go on. Only the heaviest on each side is worth
 * drawing a conclusion from — the rest is the ordinary shape of a book.
 */
export function bookWalls(book: BookRead | null): number[] {
  if (!book || !(book.bid > 0) || !(book.ask > 0)) return [];
  const mid = (book.bid + book.ask) / 2;
  const reach = mid * (book.span > 0 ? book.span : 0.0008);
  const out: number[] = [];

  const heaviest = (sizes: number[], sign: number) => {
    const use = sizes.filter((s) => Number.isFinite(s) && s >= 0);
    if (use.length < 3) return;
    const average = mean(use);
    if (!(average > 0)) return;
    let at = 0;
    for (let i = 1; i < use.length; i++) if (use[i] > use[at]) at = i;
    if (use[at] < average * WALL_WEIGHT) return;
    // The bucket's own distance from the mid, at its middle.
    out.push(mid + sign * reach * ((at + 0.5) / use.length));
  };

  heaviest(book.bids, -1);
  heaviest(book.asks, +1);
  return out;
}

export function forecast(
  candles: Candle[],
  trend: Trend | null,
  levels: Level[],
  book: BookRead | null,
  steps: number,
): Forecast | null {
  const clean = candles.filter(
    ([t, o, h, l, c]) => t > 0 && o > 0 && h > 0 && l > 0 && c > 0,
  );
  if (clean.length < 8 || steps < 1) return null;

  const stepSec = clean[1][0] - clean[0][0];
  if (!(stepSec > 0)) return null;

  const lastBar = clean[clean.length - 1];
  const last = lastBar[4];
  const typical = typicalRange(clean);
  if (!(typical > 0)) return null;

  const push = volumePush(clean);
  // Rising volume both lengthens each step and slows the fade; drying volume
  // does the opposite. Kept inside sane bounds so one freak candle cannot make
  // the path a straight ramp.
  const drive = clamp(0.6 + 0.5 * push, 0.6, 1.4);
  const decay = clamp(DECAY - 0.1 + 0.12 * push, 0.72, 0.95);

  const perStep =
    trend && trend.way !== 'flat' ? (trend.perHour * stepSec) / 3600 : 0;

  // How far price has run ahead of its own fitted line. Paid back a fraction
  // at a time, which is the hook at the front of the path.
  let residual = trend ? clamp(last - trend.to, -typical * 3, typical * 3) : 0;
  let owed = Math.abs(residual) * REVERT_CAP;

  const barriers = [
    ...levels.map((l) => l.price),
    ...bookWalls(book),
  ].filter((p) => Number.isFinite(p) && p > 0);

  const fit = trend ? clamp(trend.fit, 0, 1) : 0;
  const confidence = clamp(fit * clamp(push, 0.5, 1.5), 0, 1);
  // How much argument the move has for pushing through a price that has
  // stopped it before.
  const energy = clamp(confidence * drive, 0.15, 1.4);
  const pierce = typical * PIERCE * energy;

  let price = last;
  let stalled = false;
  let wall: number | null = null;
  const points: ForecastPoint[] = [];

  for (let i = 1; i <= steps; i++) {
    const fade = Math.pow(decay, i - 1) * (stalled ? STALLED : 1);
    const drift = perStep * drive * fade;

    // Pay back part of what price owes its own line, while there is any left.
    let pull = 0;
    if (owed > 0 && residual !== 0) {
      pull = -Math.sign(residual) * Math.min(owed, Math.abs(residual) * REVERT);
      owed -= Math.abs(pull);
    }

    let next = price + drift + pull;

    // Anything the step would cross is in the way; the nearest one is the one
    // it meets first.
    const crossed = barriers
      .filter((b) => (next > price ? b > price && b <= next : b < price && b >= next))
      .sort((a, b) => Math.abs(a - price) - Math.abs(b - price))[0];

    if (crossed !== undefined && Math.abs(next - crossed) > pierce) {
      next = crossed + Math.sign(next - price) * pierce;
      stalled = true;
      wall = crossed;
    }

    // And it stays stopped. Without this the drift, merely reduced, keeps
    // adding up step after step and the path drifts far past the price it was
    // supposed to have stalled at — which is the opposite of what the level is
    // there to say.
    if (stalled && wall !== null) {
      next =
        wall > last
          ? Math.min(next, wall + pierce)
          : Math.max(next, wall - pierce);
    }

    price = next;
    const spread = typical * SPREAD * Math.sqrt(i);
    points.push({
      time: lastBar[0] + stepSec * i,
      price,
      hi: price + spread,
      lo: price - spread,
    });
  }

  const move = points[points.length - 1].price - last;
  // A path that ends within a quarter of one candle of where it started has
  // not called a direction, whatever its middle did.
  const way: Forecast['way'] =
    Math.abs(move) < typical * 0.25 ? 'flat' : move > 0 ? 'up' : 'down';

  return {
    points,
    way,
    confidence,
    target: points[points.length - 1].price,
    wall,
  };
}
