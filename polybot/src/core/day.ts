import { Preferences } from '@capacitor/preferences';

/**
 * The day's goal, and the stop that follows it.
 *
 * A round has a target and a session has one; a day needs one too, because the
 * day is what actually gets given back. Ten times the starting balance is a day
 * that is over — so when it lands, buying stops until the clock rolls past
 * midnight and a new day is counted from a new number.
 *
 * The starting number is asked for rather than assumed: money moves in and out
 * of the wallet between sessions, and a target measured from the wrong base is
 * either unreachable or already met.
 */
export type DayGoal = {
  /** Local calendar day this baseline belongs to, yyyy-mm-dd. */
  day: string;
  baseline: number;
  startedAt: number;
  /** When ten times the baseline was first seen. */
  hitAt?: number;
};

const KEY = 'daygoal.v1';

/** What the day is aiming at, as a multiple of where it started. */
export const DAY_MULTIPLE = 10;

/** The local calendar day, which is when the stop lifts. */
export function todayKey(at = Date.now()): string {
  const d = new Date(at);
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

export async function loadDayGoal(): Promise<DayGoal | null> {
  const { value } = await Preferences.get({ key: KEY });
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as DayGoal;
    return Number.isFinite(parsed?.baseline) && parsed.baseline > 0 ? parsed : null;
  } catch {
    return null;
  }
}

export async function saveDayGoal(goal: DayGoal): Promise<void> {
  await Preferences.set({ key: KEY, value: JSON.stringify(goal) });
}

export function startDay(baseline: number, at = Date.now()): DayGoal {
  return { day: todayKey(at), baseline, startedAt: at };
}

/** Is there a baseline for the day the clock is in? */
export function needsBaseline(goal: DayGoal | null, at = Date.now()): boolean {
  return goal == null || goal.day !== todayKey(at);
}

export const dayTarget = (goal: DayGoal): number => goal.baseline * DAY_MULTIPLE;

export function dayReached(goal: DayGoal | null, balance: number): boolean {
  if (!goal || !Number.isFinite(balance)) return false;
  return balance >= dayTarget(goal) - 1e-9;
}

/**
 * Is buying stopped?
 *
 * Only for the day it was hit on: at midnight the key changes and the stop
 * lifts on its own. A balance that falls back below the target does not lift it
 * — the day was won, and trading it back is the thing being prevented.
 */
export function isLocked(goal: DayGoal | null, at = Date.now()): boolean {
  return goal != null && goal.hitAt != null && goal.day === todayKey(at);
}

export function markHit(goal: DayGoal, at = Date.now()): DayGoal {
  return goal.hitAt != null ? goal : { ...goal, hitAt: at };
}

/** Milliseconds until the stop lifts. */
export function untilMidnight(at = Date.now()): number {
  const d = new Date(at);
  const midnight = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
  return Math.max(0, midnight - at);
}

/** "3 ч 12 мин" — long enough that minutes are the useful unit. */
export function untilMidnightText(at = Date.now()): string {
  const ms = untilMidnight(at);
  const hours = Math.floor(ms / 3_600_000);
  const mins = Math.floor((ms % 3_600_000) / 60_000);
  return hours > 0 ? `${hours} ч ${mins} мин` : `${mins} мин`;
}
