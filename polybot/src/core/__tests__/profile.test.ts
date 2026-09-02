import { describe, expect, it } from 'vitest';
import { volumeNodes } from '../profile';
import type { Candle } from '../candles';

/** A candle that traded `v` between `l` and `h`. */
const bar = (l: number, h: number, v: number): Candle => [0, l, h, l, h, v];

describe('volume nodes', () => {
  it('does not depend on how much of the series is drawn', () => {
    // The same profile read over a hundred candles and over the last forty of
    // them is a different picture — which is why the chart takes it over
    // everything it holds and never over what is on screen. This is that
    // contract, stated: the answer is a function of the candles given.
    const many: Candle[] = Array.from({ length: 100 }, (_, i) =>
      i % 10 === 0 ? bar(149.9, 150.1, 50) : bar(100, 200, 1),
    );
    const first = volumeNodes(many);
    expect(volumeNodes(many)).toEqual(first);
    expect(first[0].price).toBeGreaterThan(140);
    expect(first[0].price).toBeLessThan(160);
  });

  it('finds the price everything traded at', () => {
    const candles: Candle[] = [
      bar(100, 110, 1),
      bar(100, 110, 1),
      // Four bars that never left one band, at the same price.
      bar(104.9, 105.1, 40),
      bar(104.9, 105.1, 40),
      bar(104.9, 105.1, 40),
      bar(100, 110, 1),
    ];
    const nodes = volumeNodes(candles);
    expect(nodes.length).toBeGreaterThan(0);
    // Within a band of where it all traded, which is the resolution here.
    expect(Math.abs(nodes[0].price - 105)).toBeLessThan(0.5);
    expect(nodes[0].weight).toBe(1);
  });

  it('says nothing without volume, or without candles', () => {
    expect(volumeNodes([])).toEqual([]);
    expect(volumeNodes([bar(100, 110, 0), bar(100, 110, 0)])).toEqual([]);
    // Four bars is the least that can say anything at all.
    expect(volumeNodes([bar(100, 110, 5), bar(100, 110, 5)])).toEqual([]);
  });

  it('draws the middle of a shelf, not every band of it', () => {
    // Volume spread evenly over the whole range: no band stands out, so
    // nothing is a peak worth a line.
    const flat: Candle[] = Array.from({ length: 20 }, () => bar(100, 200, 10));
    expect(volumeNodes(flat)).toEqual([]);
  });

  it('keeps only as many as asked for, strongest first', () => {
    const candles: Candle[] = [
      bar(100, 200, 1),
      bar(120.1, 120.9, 30),
      bar(140.1, 140.9, 50),
      bar(160.1, 160.9, 40),
      bar(180.1, 180.9, 20),
      bar(100, 200, 1),
    ];
    const nodes = volumeNodes(candles, 2);
    expect(nodes).toHaveLength(2);
    const band = (200 - 100) / 60;
    expect(Math.abs(nodes[0].price - 140.5)).toBeLessThan(band);
    expect(Math.abs(nodes[1].price - 160.5)).toBeLessThan(band);
    expect(nodes[0].weight).toBeGreaterThanOrEqual(nodes[1].weight);
  });
});
