import { describe, expect, it } from 'vitest';
import { btc, depthShape } from '../depth';

describe('depthShape', () => {
  it('runs each side out from the middle', () => {
    const shape = depthShape([1, 1], [1, 1], 200, 100)!;
    // Bids step left from the centre, offers step right.
    expect(shape.bidPath.startsWith('M100.0 100.0')).toBe(true);
    expect(shape.bidPath).toContain('L50.0');
    expect(shape.askPath).toContain('L150.0');
    expect(shape.askPath).toContain('L200.0');
  });

  it('draws both sides to one scale, so a lean is visible', () => {
    // Three times the bids: the offer side must not be redrawn as if it were
    // as deep, which is exactly what scaling each side to itself would do.
    const shape = depthShape([3, 3], [1, 1], 200, 100)!;
    expect(shape.peak).toBe(6);
    expect(shape.bidTotal).toBe(6);
    expect(shape.askTotal).toBe(2);
    // The offer side tops out a third of the way up: y = 100 - 2/6 * 100.
    expect(shape.askPath).toContain('66.7');
  });

  it('accumulates outwards rather than plotting each bucket alone', () => {
    const shape = depthShape([1, 1, 1, 1], [], 200, 100)!;
    // Four equal buckets: quarter, half, three quarters, all.
    expect(shape.bidPath).toContain('75.0');
    expect(shape.bidPath).toContain('50.0');
    expect(shape.bidPath).toContain('25.0');
    expect(shape.bidPath).toContain('0.0');
  });

  it('closes each side back to the floor', () => {
    const shape = depthShape([1], [1], 200, 100)!;
    expect(shape.bidPath.endsWith('Z')).toBe(true);
    expect(shape.askPath.endsWith('Z')).toBe(true);
  });

  it('has nothing to draw from an empty or dead book', () => {
    expect(depthShape([], [], 200, 100)).toBe(null);
    expect(depthShape([0, 0], [0, 0], 200, 100)).toBe(null);
  });

  it('ignores buckets the stream could not price', () => {
    const shape = depthShape([1, NaN, 1], [], 200, 100)!;
    expect(shape.bidTotal).toBe(2);
  });

  it('draws one side alone when the other is empty', () => {
    const shape = depthShape([2, 1], [], 200, 100)!;
    expect(shape.askPath).toBe('');
    expect(shape.bidPath).not.toBe('');
  });
});

describe('btc', () => {
  it('shows a size at the precision it is read at', () => {
    expect(btc(126.7)).toBe('127');
    expect(btc(12.34)).toBe('12.3');
    expect(btc(0.456)).toBe('0.46');
  });
});
