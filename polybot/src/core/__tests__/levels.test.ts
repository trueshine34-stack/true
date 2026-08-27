import { describe, expect, it } from 'vitest';
import { findLevels } from '../levels';
import type { Candle } from '../candles';

let clock = 1_787_817_600;
const bar = (o: number, h: number, l: number, c: number): Candle => [
  (clock += 300),
  o,
  h,
  l,
  c,
];

/**
 * A run that tops out at `top` twice and bottoms at 94 twice, ending between
 * them — two levels, each made by two separate turns.
 */
const twiceEach = (top: number): Candle[] => {
  clock = 1_787_817_600;
  return [
    bar(100, 102, 99, 101),
    bar(101, 103, 100, 102),
    bar(102, 104, 101, 103),
    bar(103, top, 102, top - 1),
    bar(top - 1, top - 1, 104, 105),
    bar(105, 106, 98, 99),
    bar(99, 100, 94, 95),
    bar(95, 101, 95, 100),
    bar(100, 106, 99, 105),
    bar(105, top, 104, top - 2),
    bar(top - 2, top - 2, 103, 104),
    bar(104, 105, 97, 98),
    bar(98, 99, 94, 95),
    bar(95, 99, 94.5, 97),
    bar(97, 98, 96, 97),
  ];
};

describe('findLevels', () => {
  it('finds the price a rally stopped at twice', () => {
    const levels = findLevels(twiceEach(110), 97);
    expect(levels.length).toBeGreaterThan(0);
    const top = levels[0];
    expect(top.price).toBeCloseTo(110, 0);
    expect(top.touches).toBe(2);
  });

  it('calls a level above price resistance and one below it support', () => {
    const levels = findLevels(twiceEach(110), 97);
    expect(levels.find((l) => l.price > 105)!.kind).toBe('resistance');
    // The same run bottoms out at 94 twice, under the current price.
    const below = levels.find((l) => l.price < 97);
    expect(below?.kind).toBe('support');
  });

  it('has nothing to draw on a run that never turned', () => {
    clock = 1_787_817_600;
    const climb: Candle[] = Array.from({ length: 12 }, (_, i) =>
      bar(100 + i, 101 + i, 99 + i, 100.5 + i),
    );
    expect(findLevels(climb, 110)).toEqual([]);
  });

  it('always shows what is overhead and what is underneath', () => {
    // After a run, the tested prices are all far behind: the level price is
    // about to meet is a fresh turn, and leaving it off makes the chart say
    // nothing about where price actually is.
    const levels = findLevels(twiceEach(110), 100);
    expect(levels.some((l) => l.kind === 'resistance')).toBe(true);
    expect(levels.some((l) => l.kind === 'support')).toBe(true);
  });

  it('draws the near side of a price sitting under a level', () => {
    const levels = findLevels(twiceEach(110), 109);
    const overhead = levels.find((l) => l.kind === 'resistance')!;
    expect(overhead.price).toBeCloseTo(110, 0);
  });

  it('never draws the same level twice', () => {
    const levels = findLevels(twiceEach(110), 97);
    for (let i = 1; i < levels.length; i++) {
      expect(Math.abs(levels[i].price - levels[i - 1].price)).toBeGreaterThan(0);
    }
  });

  it('keeps the strongest few rather than every wiggle', () => {
    const levels = findLevels(twiceEach(110), 97, 2);
    expect(levels.length).toBeLessThanOrEqual(2);
  });

  it('returns them from the top down, as a chart is read', () => {
    const levels = findLevels(twiceEach(110), 97);
    const prices = levels.map((l) => l.price);
    expect([...prices].sort((a, b) => b - a)).toEqual(prices);
  });

  it('has nothing to say about too short a history or no price', () => {
    expect(findLevels([], 100)).toEqual([]);
    expect(findLevels(twiceEach(110), 0)).toEqual([]);
  });

  it('has nothing to say about a series that never moved', () => {
    clock = 1_787_817_600;
    const flat: Candle[] = Array.from({ length: 12 }, () => bar(100, 100, 100, 100));
    expect(findLevels(flat, 100)).toEqual([]);
  });
});
