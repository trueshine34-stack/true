import { describe, expect, it } from 'vitest';
import {
  adjustedPoints,
  appendAdjustment,
  appendBalance,
  movedBy,
  totalWithdrawn,
  pathFor,
  sliceFor,
  statsFor,
  type BalancePoint,
} from '../balance';

const at = (minutes: number) => new Date('2026-08-25T12:00:00Z').getTime() + minutes * 60_000;

describe('appendBalance', () => {
  it('keeps a reading that moved', () => {
    const one = appendBalance([], 100, at(0));
    const two = appendBalance(one, 101, at(1));
    expect(two).toHaveLength(2);
  });

  it('drops a repeat, and returns the same array so nothing repaints', () => {
    const one = appendBalance([], 100, at(0));
    const two = appendBalance(one, 100, at(1));
    expect(two).toBe(one);
  });

  it('still marks time when nothing moves for a while', () => {
    const one = appendBalance([], 100, at(0));
    const two = appendBalance(one, 100, at(10));
    expect(two).toHaveLength(2);
  });

  it('refuses a reading that is not a balance', () => {
    const one = appendBalance([], 100, at(0));
    expect(appendBalance(one, Number.NaN, at(1))).toBe(one);
    expect(appendBalance(one, -5, at(1))).toBe(one);
  });

  it('forgets the oldest points rather than growing without bound', () => {
    let history: BalancePoint[] = [];
    for (let i = 0; i < 700; i += 1) history = appendBalance(history, 100 + i, at(i));
    expect(history.length).toBeLessThanOrEqual(600);
    // What survives is the recent end.
    expect(history[history.length - 1].usd).toBe(799);
  });
});

describe('sliceFor', () => {
  const history = [
    { at: at(0), usd: 100 },
    { at: at(30), usd: 110 },
    { at: at(90), usd: 120 },
  ];

  it('pulls in the point before the window as the starting height', () => {
    // An hour back from t=90 starts at t=30, which is outside the window — but
    // without it "за час" would measure from 120 to 120 and show no change.
    const hour = sliceFor(history, 60 * 60_000, at(90));
    expect(hour).toHaveLength(2);
    expect(hour[0].usd).toBe(110);
    expect(statsFor(hour)?.change).toBe(10);
  });

  it('returns everything when the span covers everything', () => {
    expect(sliceFor(history, Number.POSITIVE_INFINITY, at(90))).toBe(history);
  });
});

describe('statsFor', () => {
  it('measures the ends and the extremes', () => {
    const s = statsFor([
      { at: at(0), usd: 100 },
      { at: at(1), usd: 130 },
      { at: at(2), usd: 90 },
      { at: at(3), usd: 120 },
    ]);
    expect(s?.first).toBe(100);
    expect(s?.last).toBe(120);
    expect(s?.min).toBe(90);
    expect(s?.max).toBe(130);
    expect(s?.change).toBe(20);
    expect(s?.changePct).toBeCloseTo(0.2, 6);
  });

  it('has nothing to say about nothing', () => {
    expect(statsFor([])).toBeNull();
  });
});

describe('pathFor', () => {
  it('draws inside the box', () => {
    const path = pathFor(
      [
        { at: at(0), usd: 100 },
        { at: at(1), usd: 130 },
        { at: at(2), usd: 90 },
      ],
      320,
      132,
    );
    expect(path).not.toBeNull();
    const numbers = (path as { line: string }).line
      .match(/-?\d+(\.\d+)?/g)!
      .map(Number);
    expect(Math.min(...numbers)).toBeGreaterThanOrEqual(0);
    expect(Math.max(...numbers)).toBeLessThanOrEqual(320);
  });

  it('puts a flat series down the middle, not on the floor', () => {
    const path = pathFor(
      [
        { at: at(0), usd: 50 },
        { at: at(1), usd: 50 },
      ],
      320,
      132,
    );
    expect((path as { line: string }).line).toContain('66.0');
  });

  it('has nothing to draw with no points', () => {
    expect(pathFor([], 320, 132)).toBeNull();
  });
});

describe('withdrawals', () => {
  const points = [
    { at: at(0), usd: 20 },
    { at: at(10), usd: 40 },
    // 30 taken out here.
    { at: at(20), usd: 10 },
    { at: at(30), usd: 16 },
  ];
  const out = [{ at: at(15), usd: 30, kind: 'withdraw' as const }];

  it('does not read a withdrawal as a loss', () => {
    const adjusted = adjustedPoints(points, out);

    // The line keeps climbing: 20 → 40 → 40 → 46.
    expect(adjusted.map((p) => p.usd)).toEqual([20, 40, 40, 46]);
    expect(statsFor(adjusted)?.change).toBe(26);
  });

  it('leaves the series alone when nothing has moved', () => {
    expect(adjustedPoints(points, [])).toBe(points);
  });

  it('does not read a deposit as a profit either', () => {
    const adjusted = adjustedPoints(
      [
        { at: at(0), usd: 20 },
        { at: at(20), usd: 45 },
      ],
      [{ at: at(10), usd: 25, kind: 'deposit' }],
    );

    // Twenty five was added, not earned: the line is flat.
    expect(adjusted.map((p) => p.usd)).toEqual([20, 20]);
  });

  it('counts only what moved before the moment asked about', () => {
    expect(movedBy(out, at(10))).toBe(0);
    expect(movedBy(out, at(20))).toBe(30);
  });

  it('refuses an amount that is not one', () => {
    expect(appendAdjustment([], 0, 'withdraw')).toHaveLength(0);
    expect(appendAdjustment([], Number.NaN, 'withdraw')).toHaveLength(0);
    expect(appendAdjustment([], 12.5, 'withdraw')).toHaveLength(1);
  });

  it('adds up what has been taken out', () => {
    expect(totalWithdrawn([...out, { at: at(40), usd: 5, kind: 'deposit' }])).toBe(30);
  });
});
