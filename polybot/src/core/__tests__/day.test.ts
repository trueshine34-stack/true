import { describe, expect, it } from 'vitest';
import {
  DAY_MULTIPLE,
  buyingStopped,
  dayReached,
  dayTarget,
  isLocked,
  markHit,
  needsBaseline,
  startDay,
  todayKey,
  untilMidnight,
  untilMidnightText,
} from '../day';

/** Local noon, so the day key never straddles a timezone boundary in tests. */
const noon = (day: number) => new Date(2026, 7, day, 12, 0, 0).getTime();
const evening = (day: number, h: number) => new Date(2026, 7, day, h, 0, 0).getTime();

describe('the day', () => {
  it('is asked for once per calendar day', () => {
    const goal = startDay(20, noon(25));

    expect(needsBaseline(null, noon(25))).toBe(true);
    expect(needsBaseline(goal, evening(25, 23))).toBe(false);
    // Past midnight it is a new day and a new number.
    expect(needsBaseline(goal, noon(26))).toBe(true);
  });

  it('aims at ten times where it started', () => {
    expect(dayTarget(startDay(20, noon(25)))).toBe(20 * DAY_MULTIPLE);
    expect(dayReached(startDay(20, noon(25)), 199)).toBe(false);
    expect(dayReached(startDay(20, noon(25)), 200)).toBe(true);
  });

  it('does not stop buying when the stop is switched off', () => {
    const hit = markHit(startDay(20, noon(25)), evening(25, 18));

    expect(buyingStopped(hit, true, evening(25, 23))).toBe(true);
    // The day is still counted and still marked as taken — only the block on
    // buying goes away.
    expect(buyingStopped(hit, false, evening(25, 23))).toBe(false);
    expect(hit.hitAt).toBeGreaterThan(0);
    expect(isLocked(hit, evening(25, 23))).toBe(true);
  });

  it('stops buying for the rest of that day, and only that day', () => {
    const hit = markHit(startDay(20, noon(25)), evening(25, 18));

    expect(isLocked(hit, evening(25, 23))).toBe(true);
    // Midnight lifts it without anyone doing anything.
    expect(isLocked(hit, noon(26))).toBe(false);
  });

  it('does not lift when the balance falls back', () => {
    const hit = markHit(startDay(20, noon(25)), evening(25, 18));
    // The day was won; trading it back is the thing being prevented.
    expect(isLocked(hit, evening(25, 20))).toBe(true);
  });

  it('is not locked before it lands', () => {
    expect(isLocked(startDay(20, noon(25)), noon(25))).toBe(false);
    expect(isLocked(null, noon(25))).toBe(false);
  });

  it('marks the moment once', () => {
    const first = markHit(startDay(20, noon(25)), evening(25, 18));
    const again = markHit(first, evening(25, 19));
    expect(again.hitAt).toBe(first.hitAt);
  });

  it('counts down to midnight, not to a fixed hour', () => {
    expect(untilMidnight(evening(25, 23))).toBe(60 * 60_000);
    expect(untilMidnightText(evening(25, 21))).toBe('3 ч 0 мин');
    expect(todayKey(noon(25))).toBe('2026-08-25');
  });
});
