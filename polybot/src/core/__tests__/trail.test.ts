import { describe, expect, it } from 'vitest';
import { mergeTrail, trailBetween, type Trail } from '../trail';

describe('mergeTrail', () => {
  it('writes down a claim the first time it is made', () => {
    const next = mergeTrail({}, [{ time: 100, price: 5 }])!;
    expect(next).toEqual({ 100: 5 });
  });

  it('never revises a claim it has already made', () => {
    // This is the whole point: a forecast that can be edited after the fact
    // is a forecast that was always right.
    const trail: Trail = { 100: 5 };
    expect(mergeTrail(trail, [{ time: 100, price: 9 }])).toBeNull();
  });

  it('says nothing changed when nothing did', () => {
    expect(mergeTrail({ 100: 5 }, [{ time: 100, price: 5 }])).toBeNull();
    expect(mergeTrail({ 100: 5 }, [])).toBeNull();
  });

  it('adds the new points beside the old ones', () => {
    const next = mergeTrail({ 100: 5 }, [
      { time: 100, price: 9 },
      { time: 200, price: 7 },
    ])!;
    expect(next).toEqual({ 100: 5, 200: 7 });
  });

  it('drops the oldest once there are too many', () => {
    const many = Array.from({ length: 6 }, (_, i) => ({
      time: i,
      price: i + 1,
    }));
    expect(mergeTrail({}, many, 3)).toEqual({ 3: 4, 4: 5, 5: 6 });
  });

  it('ignores a point that is not a price', () => {
    expect(mergeTrail({}, [{ time: 1, price: 0 }])).toBeNull();
    expect(mergeTrail({}, [{ time: Number.NaN, price: 5 }])).toBeNull();
  });
});

describe('trailBetween', () => {
  it('returns the span, oldest first', () => {
    const trail: Trail = { 300: 3, 100: 1, 200: 2, 400: 4 };
    expect(trailBetween(trail, 150, 350)).toEqual([
      { time: 200, price: 2 },
      { time: 300, price: 3 },
    ]);
  });

  it('is empty when the chart is looking somewhere else', () => {
    expect(trailBetween({ 100: 1 }, 500, 900)).toEqual([]);
  });
});
