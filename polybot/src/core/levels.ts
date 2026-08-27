import type { Candle } from './candles';

/**
 * Where price keeps stopping.
 *
 * A level is not a line someone drew: it is a price the market has turned at
 * more than once. So the work is to find the turns — a candle whose high is
 * the highest of its neighbours, or whose low is the lowest — and then to
 * notice that several of them happened at the same price. One turn is an
 * accident; two at the same price is where the orders are.
 *
 * Highs and lows are pooled rather than kept apart, because a price that
 * stopped a fall once and a rally later is the same price and the stronger for
 * having worked both ways — which is also why a level is called support or
 * resistance by where price is *now*, not by which kind of turn made it.
 */

export interface Level {
  price: number;
  /** How many turns happened here. Two is a level; five is a wall. */
  touches: number;
  kind: 'support' | 'resistance';
}

/** A turn is the extreme of this many candles either side of it. */
const REACH = 2;

/** Two turns are the same level if they are this close, as a share of the range. */
const TOLERANCE = 0.02;

/** Levels are worth reading up to about this many; past that it is a grid. */
const KEEP = 3;

/**
 * How much being recent is worth against being tested.
 *
 * A price touched twice an hour ago and a price touched once ten minutes ago
 * are both levels, and in a market that has just run a long way the fresh one
 * is the one price is about to meet. At one and a half, a brand new single
 * turn outranks an ancient double and loses to a recent one.
 */
const FRESHNESS = 1.5;

interface Turn {
  price: number;
  at: number;
}

/** Collects the prices at which the series turned, and when. */
function pivots(candles: Candle[]): Turn[] {
  const out: Turn[] = [];
  for (let i = REACH; i < candles.length - REACH; i++) {
    let isHigh = true;
    let isLow = true;
    for (let j = i - REACH; j <= i + REACH; j++) {
      if (j === i) continue;
      // Strict on the left, forgiving on the right: a run of equal highs is
      // one turn, and this counts it at the candle that made it rather than
      // dropping it for being level with the next one.
      if (j < i ? candles[j][2] >= candles[i][2] : candles[j][2] > candles[i][2]) {
        isHigh = false;
      }
      if (j < i ? candles[j][3] <= candles[i][3] : candles[j][3] < candles[i][3]) {
        isLow = false;
      }
    }
    if (isHigh) out.push({ price: candles[i][2], at: i });
    if (isLow) out.push({ price: candles[i][3], at: i });
  }
  return out;
}

interface Cluster {
  price: number;
  touches: number;
  strength: number;
}

export function findLevels(
  candles: Candle[],
  last: number,
  keep = KEEP,
): Level[] {
  const clean = candles.filter(([, o, h, l, c]) => o > 0 && h > 0 && l > 0 && c > 0);
  if (clean.length < REACH * 2 + 1 || !(last > 0)) return [];

  let low = Infinity;
  let high = -Infinity;
  for (const [, , h, l] of clean) {
    if (l < low) low = l;
    if (h > high) high = h;
  }
  const range = high - low;
  if (!(range > 0)) return [];
  const near = range * TOLERANCE;

  // Sorted by price, so a level is a run of neighbours rather than a search.
  const turns = pivots(clean).sort((a, b) => a.price - b.price);
  const groups: Turn[][] = [];
  for (const turn of turns) {
    const open = groups[groups.length - 1];
    if (open && turn.price - open[0].price <= near) open.push(turn);
    else groups.push([turn]);
  }

  const span = Math.max(1, clean.length - 1);
  const clusters: Cluster[] = groups.map((g) => ({
    price: g.reduce((a, t) => a + t.price, 0) / g.length,
    touches: g.length,
    strength:
      g.length + (FRESHNESS * Math.max(...g.map((t) => t.at))) / span,
  }));

  const nearest = (side: Cluster[]) =>
    [...side].sort(
      (a, b) =>
        Math.abs(a.price - last) - Math.abs(b.price - last) ||
        b.strength - a.strength,
    );

  const above = nearest(clusters.filter((c) => c.price > last));
  const below = nearest(clusters.filter((c) => c.price <= last));

  // The two that matter first: what a rally has to get through, and what a
  // fall has to break. A chart drawn only from the most-tested prices puts
  // both of them an hour behind price after any real move.
  const chosen: Cluster[] = [];
  // Far enough apart to read as two lines rather than a smudge.
  const room = (level: Cluster) =>
    !chosen.some((c) => Math.abs(c.price - level.price) < near * 3);

  for (const first of [above[0], below[0]]) {
    if (first && room(first)) chosen.push(first);
  }

  // Then the strongest of the rest — but a line for a price that turned only
  // once, somewhere in the middle, is noise.
  const rest = clusters
    .filter((c) => c.touches >= 2 && !chosen.includes(c))
    .sort((a, b) => b.strength - a.strength);
  for (const level of rest) {
    if (chosen.length >= keep) break;
    if (room(level)) chosen.push(level);
  }

  return chosen
    .map(({ price, touches }) => ({
      price,
      touches,
      kind: (price > last ? 'resistance' : 'support') as Level['kind'],
    }))
    .sort((a, b) => b.price - a.price);
}
