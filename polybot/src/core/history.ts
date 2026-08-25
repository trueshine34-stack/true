import { Preferences } from '@capacitor/preferences';

/**
 * A position as it stood when its window closed.
 *
 * The desk only shows the window being traded — a five-minute series mints a
 * new pair of outcomes every five minutes, and yesterday's rows would bury
 * today's. What is closed still matters afterwards, so it is kept here instead
 * of thrown away.
 */
export type PositionRecord = {
  /** When the window this position belonged to opened, in seconds. */
  windowStart: number;
  conditionId: string;
  outcome: string;
  size: number;
  avgPrice: number;
  lastPrice: number;
  pnlUsd: number;
};

const KEY = 'poshistory.v1';
const MAX = 120;

export async function loadPositionHistory(): Promise<PositionRecord[]> {
  const { value } = await Preferences.get({ key: KEY });
  if (!value) return [];
  try {
    return JSON.parse(value) as PositionRecord[];
  } catch {
    return [];
  }
}

/**
 * Newest first, and one row per outcome per window: a window that was traded
 * several times should read as the position it ended up as, not as a stack of
 * every intermediate size the poller happened to catch.
 */
export async function appendPositionHistory(
  records: PositionRecord[],
): Promise<PositionRecord[]> {
  if (records.length === 0) return loadPositionHistory();

  const existing = await loadPositionHistory();
  const merged = [...records, ...existing];
  const seen = new Set<string>();
  const deduped: PositionRecord[] = [];
  for (const r of merged) {
    const id = `${r.windowStart}:${r.conditionId}:${r.outcome}`;
    if (seen.has(id)) continue;
    seen.add(id);
    deduped.push(r);
  }

  const trimmed = deduped
    .sort((a, b) => b.windowStart - a.windowStart)
    .slice(0, MAX);
  await Preferences.set({ key: KEY, value: JSON.stringify(trimmed) });
  return trimmed;
}

export async function clearPositionHistory(): Promise<void> {
  await Preferences.remove({ key: KEY });
}
