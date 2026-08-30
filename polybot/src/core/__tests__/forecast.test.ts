import { describe, expect, it } from 'vitest';
import { bookWalls, forecast, typicalRange, volumePush } from '../forecast';
import { trendOf } from '../trend';
import type { Candle } from '../candles';
import type { Level } from '../levels';

/** Five-minute candles walking a path of closes, with a ten-dollar range. */
const walk = (closes: number[], volume = 100): Candle[] =>
  closes.map((c, i) => [1_787_817_600 + i * 300, c, c + 5, c - 5, c, volume]);

const climb = (n: number, step = 10, from = 100_000) =>
  walk(Array.from({ length: n }, (_, i) => from + i * step));

/** The trend the panel would draw over the same candles. */
const lineOf = (candles: Candle[]) => trendOf(candles, 60);

describe('volumePush', () => {
  it('is one when the tape is trading as it has been', () => {
    expect(volumePush(walk(Array(24).fill(100)))).toBeCloseTo(1, 9);
  });

  it('rises when the last candles trade more than the hours behind them', () => {
    const rows = walk(Array(24).fill(100));
    for (let i = rows.length - 3; i < rows.length; i++) rows[i][5] = 300;
    expect(volumePush(rows)).toBeCloseTo(3, 9);
  });

  it('falls when a move is drifting on nobody', () => {
    const rows = walk(Array(24).fill(100));
    for (let i = rows.length - 3; i < rows.length; i++) rows[i][5] = 40;
    expect(volumePush(rows)).toBeCloseTo(0.4, 9);
  });

  it('says nothing rather than something wrong without volume', () => {
    const bare = walk(Array(24).fill(100)).map(
      ([t, o, h, l, c]) => [t, o, h, l, c] as Candle,
    );
    expect(volumePush(bare)).toBe(1);
  });
});

describe('typicalRange', () => {
  it('is what one candle usually travels', () => {
    expect(typicalRange(climb(20))).toBeCloseTo(10, 9);
  });
});

describe('bookWalls', () => {
  it('finds the price a stacked bucket sits at', () => {
    const walls = bookWalls({
      bid: 99_990,
      ask: 100_010,
      span: 0.001,
      // Nothing much on the bid; a wall three buckets up on the ask.
      bids: [1, 1, 1, 1],
      asks: [1, 1, 10, 1],
    });
    expect(walls).toHaveLength(1);
    // Mid is 100 000, reach is 100 dollars, and the heavy bucket is the third
    // of four — its middle is 62.5% of the way out.
    expect(walls[0]).toBeCloseTo(100_062.5, 6);
  });

  it('calls an even book no wall at all', () => {
    expect(
      bookWalls({ bid: 99_990, ask: 100_010, span: 0.001, bids: [1, 1, 1, 1], asks: [1, 1, 1, 1] }),
    ).toEqual([]);
  });

  it('has nothing to say without a book', () => {
    expect(bookWalls(null)).toEqual([]);
  });
});

describe('forecast', () => {
  it('carries a climb forward', () => {
    const candles = climb(24);
    const f = forecast(candles, lineOf(candles), [], null, 8)!;
    expect(f.way).toBe('up');
    expect(f.points).toHaveLength(8);
    expect(f.target).toBeGreaterThan(candles[candles.length - 1][4]);
  });

  it('carries a fall forward', () => {
    const candles = climb(24, -10);
    const f = forecast(candles, lineOf(candles), [], null, 8)!;
    expect(f.way).toBe('down');
    expect(f.target).toBeLessThan(candles[candles.length - 1][4]);
  });

  it('runs out of steam rather than ramping forever', () => {
    const candles = climb(24);
    const f = forecast(candles, lineOf(candles), [], null, 12)!;
    const first = f.points[0].price - candles[candles.length - 1][4];
    const last = f.points[11].price - f.points[10].price;
    // The last step is a fraction of the first: a slope measured over an hour
    // does not survive an hour.
    expect(last).toBeLessThan(first * 0.5);
  });

  it('stalls at the level it runs into', () => {
    const candles = climb(24);
    const last = candles[candles.length - 1][4];
    const level: Level = { price: last + 12, touches: 3, kind: 'resistance' };
    const f = forecast(candles, lineOf(candles), [level], null, 10)!;
    expect(f.wall).toBeCloseTo(level.price, 9);
    // It may lean on the level, but it does not sail through it.
    expect(f.target).toBeLessThan(level.price + typicalRange(candles) * 0.3);
  });

  it('ignores a level the path never reaches', () => {
    const candles = climb(24);
    const far: Level = { price: candles[candles.length - 1][4] + 5_000, touches: 3, kind: 'resistance' };
    expect(forecast(candles, lineOf(candles), [far], null, 8)!.wall).toBeNull();
  });

  it('goes further on volume than without it', () => {
    const quiet = climb(24);
    const loud = climb(24);
    for (let i = loud.length - 3; i < loud.length; i++) loud[i][5] = 400;
    const a = forecast(quiet, lineOf(quiet), [], null, 8)!;
    const b = forecast(loud, lineOf(loud), [], null, 8)!;
    expect(b.target).toBeGreaterThan(a.target);
  });

  it('trusts a move that is drifting on nobody less', () => {
    const steady = climb(24);
    const drying = climb(24);
    for (let i = drying.length - 3; i < drying.length; i++) drying[i][5] = 30;
    const a = forecast(steady, lineOf(steady), [], null, 8)!;
    const b = forecast(drying, lineOf(drying), [], null, 8)!;
    expect(b.confidence).toBeLessThan(a.confidence);
    expect(b.target).toBeLessThan(a.target);
  });

  it('widens the band with the square root of time', () => {
    const candles = climb(24);
    const f = forecast(candles, lineOf(candles), [], null, 9)!;
    const at = (i: number) => f.points[i].hi - f.points[i].price;
    // Nine steps out is three times as wide as one step out.
    expect(at(8) / at(0)).toBeCloseTo(3, 6);
  });

  it('pulls a price that has run ahead of its line back toward it', () => {
    // A quiet market with one candle far above it: no drift to argue with, so
    // what is left is the pull back toward the line.
    const candles = walk(Array.from({ length: 24 }, (_, i) => 100_000 + (i % 2) * 4));
    const spike = 100_120;
    candles.push([candles[candles.length - 1][0] + 300, spike, spike + 5, spike - 5, spike, 100]);
    const f = forecast(candles, lineOf(candles), [], null, 6)!;
    expect(f.points[0].price).toBeLessThan(spike);
    // And it keeps coming back, rather than stepping down once.
    expect(f.points[3].price).toBeLessThan(f.points[0].price);
  });

  it('calls a path that ends where it started flat', () => {
    // Chop: no line, so no drift, so no direction claimed.
    const flat = walk(Array.from({ length: 24 }, (_, i) => 100_000 + (i % 2) * 4));
    expect(forecast(flat, lineOf(flat), [], null, 8)!.way).toBe('flat');
  });

  it('refuses to forecast off a handful of candles', () => {
    expect(forecast(climb(4), null, [], null, 8)).toBeNull();
    expect(forecast(climb(24), null, [], null, 0)).toBeNull();
  });

  it('still draws a path with no trend to lean on', () => {
    const flat = walk(Array.from({ length: 24 }, (_, i) => 100_000 + (i % 2) * 4));
    const f = forecast(flat, null, [], null, 5)!;
    expect(f.points).toHaveLength(5);
    expect(f.confidence).toBe(0);
  });

  it('lands each point on the interval it is about', () => {
    const candles = climb(24);
    const f = forecast(candles, lineOf(candles), [], null, 3)!;
    const lastTime = candles[candles.length - 1][0];
    expect(f.points.map((p) => p.time)).toEqual([
      lastTime + 300,
      lastTime + 600,
      lastTime + 900,
    ]);
  });
});
