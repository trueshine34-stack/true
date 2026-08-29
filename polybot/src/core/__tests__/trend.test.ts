import { describe, expect, it } from 'vitest';
import { levelAhead, ratePerHour, trendOf } from '../trend';
import type { Candle } from '../candles';

/** One-minute candles walking a path of closes. */
const walk = (closes: number[]): Candle[] =>
  closes.map((c, i) => [
    1_787_817_600 + i * 60,
    c,
    c + 1,
    c - 1,
    c,
  ]);

describe('trendOf', () => {
  it('calls a steady climb a climb, and says how fast', () => {
    // A dollar a minute for half an hour is sixty dollars an hour.
    const t = trendOf(walk(Array.from({ length: 30 }, (_, i) => 100 + i)), 30)!;
    expect(t.way).toBe('up');
    expect(t.perHour).toBeCloseTo(60, 5);
    expect(t.fit).toBeCloseTo(1, 5);
  });

  it('calls a fall a fall', () => {
    const t = trendOf(walk(Array.from({ length: 30 }, (_, i) => 100 - i)), 30)!;
    expect(t.way).toBe('down');
    expect(t.perHour).toBeCloseTo(-60, 5);
  });

  it('refuses to call chop a direction', () => {
    // Up, down, up, down around one price: the line through it is flat and
    // explains nothing, which is the honest answer.
    const t = trendOf(
      walk(Array.from({ length: 30 }, (_, i) => 100 + (i % 2 ? 3 : -3))),
      30,
    )!;
    expect(t.way).toBe('flat');
    expect(t.fit).toBeLessThan(0.25);
  });

  it('refuses a drift too small for the range it happened in', () => {
    // Half a dollar of climb inside a twenty-dollar swing is not a trend.
    const closes = Array.from({ length: 30 }, (_, i) =>
      100 + i * 0.02 + (i % 3 === 0 ? 10 : i % 3 === 1 ? -10 : 0),
    );
    expect(trendOf(walk(closes), 30)!.way).toBe('flat');
  });

  it('only looks at the span it was asked for', () => {
    // An hour of falling, then ten minutes of climbing: asked for ten
    // minutes, the answer is the climb.
    const closes = [
      ...Array.from({ length: 60 }, (_, i) => 200 - i),
      ...Array.from({ length: 10 }, (_, i) => 140 + i * 2),
    ];
    expect(trendOf(walk(closes), 10)!.way).toBe('up');
    expect(trendOf(walk(closes), 70)!.way).toBe('down');
  });

  it('reports where its line starts, so it can be drawn', () => {
    const t = trendOf(walk(Array.from({ length: 40 }, (_, i) => 100 + i)), 20)!;
    expect(t.fromIndex).toBe(20);
    expect(t.to).toBeGreaterThan(t.from);
  });

  it('has nothing to say about too little history', () => {
    expect(trendOf(walk([100, 101]), 30)).toBe(null);
    expect(trendOf([], 30)).toBe(null);
  });
});

describe('levelAhead', () => {
  const levels = [{ price: 120 }, { price: 105 }, { price: 90 }, { price: 80 }];

  it('going up, it is the nearest one overhead', () => {
    expect(levelAhead(levels, 100, 'up')).toBe(105);
  });

  it('going down, the nearest one underneath', () => {
    expect(levelAhead(levels, 100, 'down')).toBe(90);
  });

  it('sideways there is nothing to head into', () => {
    expect(levelAhead(levels, 100, 'flat')).toBe(null);
  });

  it('and nothing when the way is clear', () => {
    expect(levelAhead(levels, 200, 'up')).toBe(null);
    expect(levelAhead([], 100, 'up')).toBe(null);
  });
});

describe('ratePerHour', () => {
  it('says which way and how fast', () => {
    expect(ratePerHour(142.4)).toBe('+142$/ч');
    expect(ratePerHour(-60)).toBe('−60$/ч');
  });
});
