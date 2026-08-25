import { describe, expect, it } from 'vitest';
import { findLevels, type Candle } from '../levels';

const bar = (i: number, low: number, high: number): Candle => ({
  time: i * 60,
  open: (low + high) / 2,
  high,
  low,
  close: (low + high) / 2,
});

/** A flat series with spikes to the given highs at the given indices. */
function series(spikes: Record<number, [number, number]>, n = 30): Candle[] {
  const out: Candle[] = [];
  for (let i = 0; i < n; i++) {
    const s = spikes[i];
    out.push(s ? bar(i, s[0], s[1]) : bar(i, 99, 101));
  }
  return out;
}

describe('findLevels', () => {
  it('finds a price the market turned at twice', () => {
    const levels = findLevels(series({ 5: [99, 110], 20: [99, 110] }));
    const resistance = levels.find((l) => l.kind === 'resistance');

    expect(resistance).toBeDefined();
    expect(resistance!.price).toBeCloseTo(110, 5);
    expect(resistance!.touches).toBe(2);
  });

  it('ignores a price touched only once when better levels exist', () => {
    // Two turns at 108 and one stray spike to 110: the stray is not a level.
    const levels = findLevels(
      series({ 4: [99, 108], 12: [99, 108], 20: [99, 110] }, 30),
      { includeExtremes: false },
    );
    expect(levels.every((l) => Math.abs(l.price - 110) > 1)).toBe(true);
    expect(levels.some((l) => Math.abs(l.price - 108) < 1)).toBe(true);
  });

  it('falls back to the range extreme when a side has no repeated turn', () => {
    // A quiet stretch may never revisit a price. Showing nothing at all would
    // be worse than showing where it actually turned.
    const levels = findLevels(series({ 5: [99, 110], 15: [90, 101] }, 30));
    expect(levels.some((l) => l.kind === 'resistance')).toBe(true);
    expect(levels.some((l) => l.kind === 'support')).toBe(true);
  });

  it('prefers a repeated level over the extreme on the same side', () => {
    const levels = findLevels(series({ 4: [99, 108], 12: [99, 108], 20: [99, 115] }, 30));
    const resistance = levels.filter((l) => l.kind === 'resistance');
    expect(resistance[0].price).toBeCloseTo(108, 5);
    expect(resistance[0].touches).toBe(2);
  });

  it('separates support from resistance by where price is now', () => {
    const candles = series({ 4: [99, 112], 10: [88, 101], 16: [99, 112], 22: [88, 101] });
    const levels = findLevels(candles);

    const above = levels.filter((l) => l.kind === 'resistance');
    const below = levels.filter((l) => l.kind === 'support');
    expect(above.length).toBeGreaterThan(0);
    expect(below.length).toBeGreaterThan(0);
    expect(Math.min(...above.map((l) => l.price))).toBeGreaterThan(
      Math.max(...below.map((l) => l.price)),
    );
  });

  it('merges turns that are near enough to read as one level', () => {
    // 110 and 110.2 are a fifth of a percent apart on a 24-point range.
    const levels = findLevels(series({ 5: [99, 110], 15: [99, 110.2], 25: [99, 110] }));
    const near = levels.filter((l) => Math.abs(l.price - 110) < 1);

    expect(near).toHaveLength(1);
    expect(near[0].touches).toBe(3);
  });

  it('ranks the most-tested level first', () => {
    const levels = findLevels(
      series({ 4: [99, 108], 10: [99, 108], 16: [99, 108], 22: [99, 115] }, 34),
    );
    expect(levels[0].price).toBeCloseTo(108, 5);
    expect(levels[0].touches).toBe(3);
  });

  it('returns nothing rather than guessing on too little data', () => {
    expect(findLevels([])).toEqual([]);
    expect(findLevels(series({}, 3))).toEqual([]);
  });

  it('survives a dead flat series', () => {
    // No range at all: every bar identical. Must not divide by zero.
    const flat = Array.from({ length: 20 }, (_, i) => bar(i, 100, 100));
    expect(findLevels(flat)).toEqual([]);
  });

  it('never returns more levels than asked for', () => {
    const spikes: Record<number, [number, number]> = {};
    for (let i = 3; i < 40; i += 3) spikes[i] = [99, 105 + (i % 5)];
    expect(findLevels(series(spikes, 44), { max: 3 }).length).toBeLessThanOrEqual(3);
  });
});
