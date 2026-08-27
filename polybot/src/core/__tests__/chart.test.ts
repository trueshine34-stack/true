import { describe, expect, it } from 'vitest';
import { shapeWindow, signedPrice, WINDOW_SEC } from '../chart';

const START = 1_787_817_600;
const at = (sec: number, value: number): [number, number] => [
  (START + sec) * 1000,
  value,
];

describe('shapeWindow', () => {
  it('spreads a finished window across the whole width', () => {
    const shape = shapeWindow(
      [at(0, 100), at(150, 110), at(WINDOW_SEC, 105)],
      START,
      100,
      300,
      100,
    )!;
    expect(shape.path.startsWith('M0.0 ')).toBe(true);
    expect(shape.last!.x).toBeCloseTo(300, 5);
  });

  it('leaves a window in progress short of the right edge', () => {
    // Two minutes into five is two fifths of the width, and the gap after it
    // is the time still to trade.
    const shape = shapeWindow([at(0, 100), at(120, 101)], START, 100, 300, 100)!;
    expect(shape.last!.x).toBeCloseTo(120, 5);
  });

  it('keeps the target inside the drawn range when price runs away', () => {
    const shape = shapeWindow(
      [at(0, 79_000), at(60, 79_400), at(120, 79_800)],
      START,
      79_000,
      300,
      100,
    )!;
    expect(shape.targetY).toBeGreaterThan(0);
    expect(shape.targetY).toBeLessThan(100);
    expect(shape.low).toBeLessThan(79_000);
    expect(shape.high).toBeGreaterThan(79_800);
  });

  it('puts a higher price higher up the chart', () => {
    const shape = shapeWindow([at(0, 100), at(60, 120)], START, 110, 300, 100)!;
    const [, first] = shape.path.split('L')[0].slice(1).split(' ');
    expect(Number(first)).toBeGreaterThan(shape.last!.y);
  });

  it('gives a flat window somewhere to sit rather than dividing by nothing', () => {
    const shape = shapeWindow([at(0, 100), at(60, 100)], START, 100, 300, 100)!;
    expect(Number.isFinite(shape.targetY)).toBe(true);
    expect(shape.targetY).toBeCloseTo(50, 5);
  });

  it('closes the fill down to the floor', () => {
    const shape = shapeWindow([at(0, 100), at(60, 110)], START, 100, 300, 100)!;
    expect(shape.area.endsWith('Z')).toBe(true);
    expect(shape.area).toContain('100');
  });

  it('draws the target alone before the first reading arrives', () => {
    const shape = shapeWindow([], START, 79_000, 300, 100)!;
    expect(shape.last).toBe(null);
    expect(shape.path).toBe('');
    expect(shape.targetY).toBeCloseTo(50, 5);
  });

  it('has nothing to draw with neither readings nor a target', () => {
    expect(shapeWindow([], START, 0, 300, 100)).toBe(null);
  });

  it('drops readings the feed could not price', () => {
    const shape = shapeWindow(
      [at(0, 100), [NaN, 105], at(60, 110), at(90, 0)],
      START,
      100,
      300,
      100,
    )!;
    expect(shape.last!.value).toBe(110);
  });
});

describe('signedPrice', () => {
  it('says which way and by how much', () => {
    expect(signedPrice(128.4)).toBe('+128');
    expect(signedPrice(-54.6)).toBe('−55');
    expect(signedPrice(0)).toBe('0');
  });
});
