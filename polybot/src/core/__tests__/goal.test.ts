import { describe, expect, it } from 'vitest';
import {
  GOAL_GAIN,
  WITHDRAW_SHARE,
  goalProgress,
  restartRun,
  shouldRemind,
  snoozeGoal,
  startRun,
} from '../goal';

const t0 = new Date('2026-08-25T12:00:00Z').getTime();
const hours = (n: number) => t0 + n * 60 * 60_000;

describe('goalProgress', () => {
  const run = startRun(20, t0);

  it('is the baseline doubled', () => {
    expect(goalProgress(run, 20).target).toBe(20 * (1 + GOAL_GAIN));
  });

  it('counts what is still needed while short of it', () => {
    const p = goalProgress(run, 32);
    expect(p.reached).toBe(false);
    expect(p.profit).toBe(12);
    expect(p.gain).toBeCloseTo(0.6, 6);
    expect(p.remaining).toBe(8);
  });

  it('suggests three quarters of the profit, and says what is left', () => {
    const p = goalProgress(run, 40);
    expect(p.reached).toBe(true);
    expect(p.profit).toBe(20);
    expect(p.suggested).toBeCloseTo(20 * WITHDRAW_SHARE, 6);
    expect(p.leftAfter).toBeCloseTo(25, 6);
    // The run comes out bigger than it started even after taking money out.
    expect(p.leftAfter).toBeGreaterThan(p.baseline);
  });

  it('suggests nothing out of a loss', () => {
    const p = goalProgress(run, 12);
    expect(p.profit).toBe(-8);
    expect(p.suggested).toBe(0);
    expect(p.remaining).toBe(28);
  });
});

describe('shouldRemind', () => {
  const run = startRun(20, t0);

  it('says nothing before the balance doubles', () => {
    expect(shouldRemind(run, 39.99, t0)).toBe(false);
    expect(shouldRemind(run, 40, t0)).toBe(true);
  });

  it('has nothing to say without a run', () => {
    expect(shouldRemind(null, 100, t0)).toBe(false);
  });

  it('stays quiet once put off', () => {
    const snoozed = snoozeGoal(run, 40, t0);
    expect(shouldRemind(snoozed, 40, hours(1))).toBe(false);
    expect(shouldRemind(snoozed, 41, hours(1))).toBe(false);
  });

  it('comes back when the balance has climbed appreciably further', () => {
    const snoozed = snoozeGoal(run, 40, t0);
    // Another tenth of the baseline on top of where it was put off.
    expect(shouldRemind(snoozed, 42, hours(1))).toBe(true);
  });

  it('comes back after enough of the day has passed', () => {
    const snoozed = snoozeGoal(run, 40, t0);
    expect(shouldRemind(snoozed, 40, hours(5))).toBe(false);
    expect(shouldRemind(snoozed, 40, hours(6))).toBe(true);
  });
});

describe('restartRun', () => {
  it('starts again from what is left after the money is out', () => {
    const run = startRun(20, t0);
    const next = restartRun(run, 25, hours(2));

    expect(next.baseline).toBe(25);
    expect(next.rounds).toBe(1);
    expect(next.startedAt).toBe(hours(2));
    // And the next goal means the same thing as the last one did.
    expect(goalProgress(next, 50).reached).toBe(true);
    expect(goalProgress(next, 49).reached).toBe(false);
  });

  it('forgets that the old reminder was put off', () => {
    const run = snoozeGoal(startRun(20, t0), 40, t0);
    const next = restartRun(run, 25, hours(2));

    expect(next.snoozedAt).toBeUndefined();
    expect(shouldRemind(next, 50, hours(3))).toBe(true);
  });
});
