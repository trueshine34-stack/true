import { describe, expect, it } from 'vitest';
import {
  breakEvenPrice,
  ceilToTick,
  feePerShare,
  netSellPrice,
  positionPnl,
  signedPct,
  signedUsd,
  targetPrice,
} from '../money';

describe('the fee', () => {
  it('is largest in the middle of the book', () => {
    expect(feePerShare(0.5)).toBeCloseTo(0.0175, 6);
    expect(feePerShare(0.9)).toBeCloseTo(0.0063, 6);
    expect(feePerShare(0.1)).toBeCloseTo(0.0063, 6);
  });

  it('is nothing at the edges, where nothing can be traded anyway', () => {
    expect(feePerShare(0)).toBe(0);
    expect(feePerShare(1)).toBe(0);
  });
});

describe('positionPnl', () => {
  it('separates what a position is marked at from what it would pay', () => {
    const p = positionPnl(10, 0.4, 0.6);
    expect(p.value).toBeCloseTo(6, 6);
    expect(p.net).toBeCloseTo(6 - 10 * 0.0168, 6);
    expect(p.cost).toBeCloseTo(4, 6);
    // The mark says +2.00; the fee makes it less, and that is the real figure.
    expect(p.pnl).toBeLessThan(2);
    expect(p.pnl).toBeCloseTo(1.832, 3);
    expect(p.pct).toBeCloseTo(0.458, 3);
  });

  it('is honest about a loss', () => {
    const p = positionPnl(10, 0.6, 0.4);
    expect(p.pnl).toBeLessThan(0);
    expect(p.pct).toBeLessThan(0);
  });

  it('has no opinion about a position with no cost', () => {
    expect(positionPnl(10, 0, 0.5).pct).toBe(0);
  });
});

describe('targetPrice', () => {
  it('reaches the asked-for gain after the fee, not before it', () => {
    const avg = 0.4;
    const price = targetPrice(avg, 0.2);
    // What the sale actually pays has to clear cost plus twenty percent.
    expect(netSellPrice(price)).toBeGreaterThanOrEqual(avg * 1.2);
    // And a tick lower would not have.
    expect(netSellPrice(price - 0.01)).toBeLessThan(avg * 1.2);
  });

  it('asks for more than the naive multiple, because of the fee', () => {
    expect(targetPrice(0.4, 0.2)).toBeGreaterThan(0.4 * 1.2);
  });

  it('lands on the venue grid and never on a price that cannot trade', () => {
    for (const gain of [0.25, 0.5, 1, 2, 3]) {
      const p = targetPrice(0.3, gain);
      expect(Math.abs(p * 100 - Math.round(p * 100))).toBeLessThan(1e-6);
      expect(p).toBeLessThanOrEqual(0.99);
      expect(p).toBeGreaterThanOrEqual(0.01);
    }
  });

  it('caps at the top of the book when the gain cannot be had', () => {
    expect(targetPrice(0.9, 3)).toBeCloseTo(0.99, 6);
  });
});

describe('breakEvenPrice', () => {
  it('is above the buy price, by at least the fee', () => {
    for (const avg of [0.05, 0.2, 0.5, 0.77, 0.9]) {
      const be = breakEvenPrice(avg);
      expect(netSellPrice(be)).toBeGreaterThan(avg);
      expect(be).toBeGreaterThan(avg);
    }
  });

  it('leaves a real profit, not a rounding one', () => {
    const be = breakEvenPrice(0.5);
    expect(netSellPrice(be) - 0.5).toBeGreaterThan(0);
  });
});

describe('formatting', () => {
  it('writes ticks and dollars the way the desk reads them', () => {
    expect(ceilToTick(0.4321)).toBeCloseTo(0.44, 6);
    expect(ceilToTick(0.44)).toBeCloseTo(0.44, 6);
    expect(signedUsd(1.5)).toBe('+1.50 $');
    expect(signedUsd(-1.5)).toBe('−1.50 $');
    expect(signedPct(0.2)).toBe('+20%');
  });
});
