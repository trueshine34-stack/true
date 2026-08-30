/**
 * What the forecast said, kept so it can be marked afterwards.
 *
 * A projection that is redrawn every time the tape moves is unfalsifiable:
 * whatever price does, the line was always pointing there. So the first time
 * the forecast says anything about an interval, that number is written down
 * and never touched again — and when that interval finally prints, the chart
 * draws the old claim next to the real candle.
 *
 * "First" is the whole point. A prediction for the next candle, made a second
 * before it opens, is worth nothing; the one kept here was made a full horizon
 * earlier, which is the claim actually worth judging.
 */

/** Interval open time in seconds, to the price that was predicted for it. */
export type Trail = Record<number, number>;

/** About a day of five-minute intervals, which is far more than is drawn. */
const KEEP = 400;

/**
 * Adds the points that are new, leaves alone the ones that are not, and drops
 * the oldest once there are too many.
 *
 * Returns null when there was nothing new, so a caller that persists this can
 * tell a real change from a redraw.
 */
export function mergeTrail(
  trail: Trail,
  points: { time: number; price: number }[],
  keep = KEEP,
): Trail | null {
  let added = false;
  const next: Trail = { ...trail };
  for (const p of points) {
    if (!Number.isFinite(p.time) || !Number.isFinite(p.price)) continue;
    if (p.price <= 0) continue;
    if (next[p.time] !== undefined) continue;
    next[p.time] = p.price;
    added = true;
  }
  if (!added) return null;

  const times = Object.keys(next)
    .map(Number)
    .sort((a, b) => a - b);
  if (times.length <= keep) return next;

  const trimmed: Trail = {};
  for (const t of times.slice(times.length - keep)) trimmed[t] = next[t];
  return trimmed;
}

/** The trail's points inside a span, oldest first — what the chart draws. */
export function trailBetween(
  trail: Trail,
  fromTime: number,
  toTime: number,
): { time: number; price: number }[] {
  return Object.keys(trail)
    .map(Number)
    .filter((t) => t >= fromTime && t <= toTime)
    .sort((a, b) => a - b)
    .map((t) => ({ time: t, price: trail[t] }));
}

const keyFor = (interval: string) => `polybot.forecast.${interval}`;

const cache = new Map<string, Trail>();

export function loadTrail(interval: string): Trail {
  const held = cache.get(interval);
  if (held) return held;
  let trail: Trail = {};
  try {
    const raw = window.localStorage.getItem(keyFor(interval));
    if (raw) trail = JSON.parse(raw) as Trail;
  } catch {
    // A browser with storage switched off still gets a chart; it just cannot
    // mark the forecast afterwards.
  }
  cache.set(interval, trail);
  return trail;
}

/** Writes down anything the forecast has not claimed before. */
export function rememberTrail(
  interval: string,
  points: { time: number; price: number }[],
): Trail {
  const trail = loadTrail(interval);
  const next = mergeTrail(trail, points);
  if (!next) return trail;
  cache.set(interval, next);
  try {
    window.localStorage.setItem(keyFor(interval), JSON.stringify(next));
  } catch {
    // Out of quota or storage disabled: the chart keeps this session's trail
    // in memory and forgets it on restart.
  }
  return next;
}
