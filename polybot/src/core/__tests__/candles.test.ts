import { describe, expect, it } from 'vitest';
import { candleShape, signedPct, type Candle } from '../candles';

const at = (i: number, o: number, h: number, l: number, c: number): Candle => [
  1_787_817_600 + i * 300,
  o,
  h,
  l,
  c,
];

describe('candleShape', () => {
  it('keeps the open time of each candle, which is what it is', () => {
    // The marks drawn on the chart — where a five-minute window began — are
    // about time, so a bar that only knows where it sits cannot carry them.
    const shape = candleShape([at(0, 10, 12, 9, 11), at(1, 11, 13, 10, 12)], 200, 100)!;
    expect(shape.bars[0].time).toBe(1_787_817_600);
    expect(shape.bars[1].time - shape.bars[0].time).toBe(300);
  });

  it('spaces the candles evenly across the width', () => {
    const shape = candleShape(
      [at(0, 10, 12, 9, 11), at(1, 11, 13, 10, 12)],
      200,
      100,
    )!;
    expect(shape.bars).toHaveLength(2);
    expect(shape.bars[0].x).toBeCloseTo(50, 5);
    expect(shape.bars[1].x).toBeCloseTo(150, 5);
  });

  it('puts a rising candle up and says so', () => {
    const shape = candleShape([at(0, 10, 12, 9, 11)], 100, 100)!;
    const bar = shape.bars[0];
    expect(bar.up).toBe(true);
    // The body runs from the close down to the open, and the wick past both.
    expect(bar.top).toBeLessThan(bar.bottom);
    expect(bar.high).toBeLessThan(bar.top);
    expect(bar.low).toBeGreaterThan(bar.bottom);
  });

  it('marks a candle that closed where it opened as rising, not falling', () => {
    expect(candleShape([at(0, 10, 11, 9, 10)], 100, 100)!.bars[0].up).toBe(true);
  });

  it('draws a candle that did not move as a line rather than nothing', () => {
    const shape = candleShape([at(0, 10, 10, 10, 10)], 100, 100)!;
    const bar = shape.bars[0];
    expect(bar.bottom - bar.top).toBeGreaterThan(0);
    expect(Number.isFinite(bar.top)).toBe(true);
  });

  it('scales to the whole run, not to each candle', () => {
    const shape = candleShape(
      [at(0, 10, 12, 9, 11), at(1, 11, 20, 11, 19)],
      200,
      100,
    )!;
    expect(shape.low).toBe(9);
    expect(shape.high).toBe(20);
    // The tallest candle's high sits above the shortest one's.
    expect(shape.bars[1].high).toBeLessThan(shape.bars[0].high);
  });

  it('reports the interval in progress: where it opened and where it is', () => {
    // Not the move across the whole chart — the move inside the candle the
    // desk is currently trading, which for five minutes is this window's own.
    const shape = candleShape(
      [at(0, 100, 105, 99, 104), at(1, 200, 212, 199, 210)],
      200,
      100,
    )!;
    expect(shape.open).toBe(200);
    expect(shape.last).toBe(210);
    expect(shape.sinceOpen).toBeCloseTo(5, 5);
  });

  it('drops candles the stream could not price', () => {
    const shape = candleShape(
      [at(0, 10, 12, 9, 11), at(1, 0, 0, 0, 0), at(2, 11, 13, 10, 12)],
      200,
      100,
    )!;
    expect(shape.bars).toHaveLength(2);
  });

  it('has nothing to draw with no candles', () => {
    expect(candleShape([], 200, 100)).toBe(null);
  });
});

describe('signedPct', () => {
  it('says which way and by how much', () => {
    expect(signedPct(1.294)).toBe('+1.29%');
    expect(signedPct(-0.5)).toBe('−0.50%');
    expect(signedPct(0)).toBe('0.00%');
  });
});
