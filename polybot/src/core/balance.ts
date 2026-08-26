import { Preferences } from '@capacitor/preferences';

/**
 * The balance, remembered.
 *
 * One number on the header says what there is; it says nothing about whether
 * the day is going well. A line does, and the app is already reading the
 * balance every half minute — the only thing missing was keeping it.
 *
 * Samples are thinned as they are taken rather than trimmed later: a reading
 * that repeats the last one to the cent carries nothing, and half a day of
 * identical points would crowd out the part of the line that moves.
 */
export type BalancePoint = {
  /** Milliseconds. */
  at: number;
  usd: number;
};

const KEY = 'balhistory.v1';
const MAX = 600;

/** Below this the reading is the same reading, and not worth a point. */
const NOISE = 0.005;

/** Keep a point every few minutes even when nothing moves, so the line has a floor. */
const HEARTBEAT_MS = 5 * 60_000;

export async function loadBalanceHistory(): Promise<BalancePoint[]> {
  const { value } = await Preferences.get({ key: KEY });
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as BalancePoint[];
    return Array.isArray(parsed) ? parsed.filter((p) => Number.isFinite(p.usd)) : [];
  } catch {
    return [];
  }
}

/**
 * Add a reading, and say what the series became.
 *
 * Returns the series rather than nothing so the caller can render without a
 * second read — and returns the *same* array when the reading was dropped, so
 * a repeat cannot cause a repaint.
 */
export function appendBalance(
  history: BalancePoint[],
  usd: number,
  at = Date.now(),
): BalancePoint[] {
  if (!Number.isFinite(usd) || usd < 0) return history;

  const last = history[history.length - 1];
  if (last && Math.abs(last.usd - usd) < NOISE && at - last.at < HEARTBEAT_MS) {
    return history;
  }

  const next = [...history, { at, usd }];
  return next.length > MAX ? next.slice(next.length - MAX) : next;
}

export async function saveBalanceHistory(history: BalancePoint[]): Promise<void> {
  await Preferences.set({ key: KEY, value: JSON.stringify(history) });
}

export async function clearBalanceHistory(): Promise<void> {
  await Preferences.remove({ key: KEY });
}

export type BalanceSpan = '1ч' | '6ч' | '24ч' | 'всё';

export const SPANS: { label: BalanceSpan; ms: number }[] = [
  { label: '1ч', ms: 60 * 60_000 },
  { label: '6ч', ms: 6 * 60 * 60_000 },
  { label: '24ч', ms: 24 * 60 * 60_000 },
  { label: 'всё', ms: Number.POSITIVE_INFINITY },
];

/**
 * The window of the series a span covers.
 *
 * A span with one point in it is a straight line with nothing to say, so the
 * point before the window is pulled in as the line's starting height — that is
 * what makes "за час" mean the change over the hour rather than the change
 * between whatever happened to be recorded inside it.
 */
export function sliceFor(history: BalancePoint[], ms: number, now = Date.now()): BalancePoint[] {
  if (!Number.isFinite(ms)) return history;
  const from = now - ms;
  const inside = history.filter((p) => p.at >= from);
  if (inside.length === history.length) return history;
  // A point sitting exactly on the boundary already is the starting height.
  if (inside.length > 0 && inside[0].at <= from) return inside;

  const before = history[history.length - inside.length - 1];
  return before ? [{ ...before, at: from }, ...inside] : inside;
}

export type BalanceStats = {
  first: number;
  last: number;
  min: number;
  max: number;
  change: number;
  changePct: number;
};

export function statsFor(points: BalancePoint[]): BalanceStats | null {
  if (points.length === 0) return null;
  const values = points.map((p) => p.usd);
  const first = values[0];
  const last = values[values.length - 1];
  return {
    first,
    last,
    min: Math.min(...values),
    max: Math.max(...values),
    change: last - first,
    changePct: first > 0 ? (last - first) / first : 0,
  };
}

/**
 * The series as a path through a box, plus the same path closed for a fill.
 *
 * A flat line would otherwise sit on the floor of the box, where it reads as
 * missing data; a series with no range is drawn down the middle instead.
 */
export function pathFor(
  points: BalancePoint[],
  width: number,
  height: number,
  pad = 6,
): { line: string; area: string } | null {
  if (points.length === 0) return null;

  const values = points.map((p) => p.usd);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min;
  const t0 = points[0].at;
  const t1 = points[points.length - 1].at;
  const dt = t1 - t0;

  const x = (at: number) => (dt > 0 ? ((at - t0) / dt) * (width - pad * 2) + pad : width / 2);
  const y = (usd: number) =>
    span > 0
      ? height - pad - ((usd - min) / span) * (height - pad * 2)
      : height / 2;

  const line = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.at).toFixed(1)} ${y(p.usd).toFixed(1)}`)
    .join(' ');
  const area =
    `${line} L${x(t1).toFixed(1)} ${height} L${x(t0).toFixed(1)} ${height} Z`;

  return { line, area };
}

/**
 * Money that moved for a reason other than trading.
 *
 * A withdrawal drops the balance without losing anything, and a deposit lifts
 * it without earning anything. Left unrecorded, both are read as performance:
 * the line falls off a cliff the day you take profit out, and the run looks
 * like it gave back everything it made.
 */
export type Adjustment = {
  at: number;
  /** Always positive; `kind` carries the direction. */
  usd: number;
  kind: 'withdraw' | 'deposit';
};

const ADJ_KEY = 'baladjust.v1';

export async function loadAdjustments(): Promise<Adjustment[]> {
  const { value } = await Preferences.get({ key: ADJ_KEY });
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as Adjustment[];
    return Array.isArray(parsed) ? parsed.filter((a) => Number.isFinite(a.usd)) : [];
  } catch {
    return [];
  }
}

export async function saveAdjustments(list: Adjustment[]): Promise<void> {
  await Preferences.set({ key: ADJ_KEY, value: JSON.stringify(list) });
}

export function appendAdjustment(
  list: Adjustment[],
  usd: number,
  kind: Adjustment['kind'],
  at = Date.now(),
): Adjustment[] {
  if (!Number.isFinite(usd) || usd <= 0) return list;
  return [...list, { at, usd, kind }];
}

/** What has been taken out, less what has been put in, up to a moment. */
export function movedBy(adjustments: Adjustment[], at: number): number {
  return adjustments
    .filter((a) => a.at <= at)
    .reduce((sum, a) => sum + (a.kind === 'withdraw' ? a.usd : -a.usd), 0);
}

/**
 * The series as it would read if nothing had been taken out or put in.
 *
 * Every point carries the withdrawals made up to that moment back into the
 * balance, so a withdrawal is a step the line does not take: what is left is
 * the shape of the trading alone, and the end of the line is what the run has
 * actually made since it started.
 */
export function adjustedPoints(
  points: BalancePoint[],
  adjustments: Adjustment[],
): BalancePoint[] {
  if (adjustments.length === 0) return points;
  return points.map((p) => ({ at: p.at, usd: p.usd + movedBy(adjustments, p.at) }));
}

export const totalWithdrawn = (adjustments: Adjustment[]): number =>
  adjustments.filter((a) => a.kind === 'withdraw').reduce((s, a) => s + a.usd, 0);
