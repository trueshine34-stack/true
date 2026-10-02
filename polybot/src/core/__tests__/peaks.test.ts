import { describe, expect, it } from 'vitest';
import { swingPeaks } from '../peaks';
import type { Candle } from '../candles';

/** A candle at one price range, at a minute per bar. */
const bar = (i: number, high: number, low: number): Candle => [
  i * 60,
  (high + low) / 2,
  high,
  low,
  (high + low) / 2,
];

describe('the chart read back as numbers', () => {
  it('finds the top of a rise and fall', () => {
    const candles = [
      bar(0, 10, 9),
      bar(1, 11, 10),
      bar(2, 15, 12),
      bar(3, 11, 10),
      bar(4, 10, 9),
    ];
    const peaks = swingPeaks(candles);
    expect(peaks.some((p) => p.kind === 'high' && p.price === 15)).toBe(true);
  });

  it('finds the bottom of a dip, and stamps it with its candle', () => {
    const candles = [
      bar(0, 20, 19),
      bar(1, 19, 18),
      bar(2, 16, 14),
      bar(3, 19, 18),
      bar(4, 20, 19),
    ];
    const peaks = swingPeaks(candles);
    const bottom = peaks.find((p) => p.kind === 'low');
    expect(bottom?.price).toBe(14);
    // The time, so the mark follows its own candle through a zoom.
    expect(bottom?.time).toBe(120);
  });

  it('will not call the edges a turn, having nothing to compare them with', () => {
    const rising = [0, 1, 2, 3, 4].map((i) => bar(i, 10 + i, 9 + i));
    // Every bar is higher than the last, so the highest is the final one — and
    // the final one has nothing after it to be higher than.
    expect(swingPeaks(rising).some((p) => p.price === 14)).toBe(false);
  });

  it('has nothing to say about too few candles', () => {
    expect(swingPeaks([bar(0, 10, 9), bar(1, 11, 10)])).toEqual([]);
  });

  it('keeps the furthest from the middle when it has to choose', () => {
    // Three tops: 30, 20 and 21. Asked for one, it keeps the 30.
    const candles = [
      bar(0, 10, 9),
      bar(1, 30, 9),
      bar(2, 10, 9),
      bar(3, 20, 9),
      bar(4, 10, 9),
      bar(5, 21, 9),
      bar(6, 10, 9),
    ];
    const peaks = swingPeaks(candles, 1, 1);
    expect(peaks).toHaveLength(1);
    expect(peaks[0]?.price).toBe(30);
  });
});
