import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MANUAL_SETTINGS,
  limitShares,
  sharesFor,
  type ManualSettings,
} from '../manual';

/**
 * The size ladder decides how much real money one tap spends, so the bands and
 * their edges are worth pinning down exactly.
 */
describe('sharesFor', () => {
  const s = DEFAULT_MANUAL_SETTINGS;

  it('gives the cheap end more shares', () => {
    expect(sharesFor(0.1, s)).toBe(25);
    expect(sharesFor(0.3, s)).toBe(15);
    expect(sharesFor(0.5, s)).toBe(10);
    expect(sharesFor(0.8, s)).toBe(5);
  });

  it('keeps the money at risk far more level than a flat share count', () => {
    const prices = [0.1, 0.3, 0.5, 0.8];
    const spread = (xs: number[]) => Math.max(...xs) / Math.min(...xs);

    const laddered = spread(prices.map((p) => sharesFor(p, s) * p));
    const flat = spread(prices.map((p) => 5 * p));

    // Five shares at 10¢ and at 90¢ are 50¢ and $4.50 of risk — an eightfold
    // spread, which is not one strategy. The ladder brings it to twofold.
    expect(flat).toBeCloseTo(8, 5);
    expect(laddered).toBeLessThanOrEqual(2);
    expect(laddered).toBeLessThan(flat / 3);
  });

  it('treats a band ceiling as inclusive', () => {
    expect(sharesFor(0.2, s)).toBe(25);
    expect(sharesFor(0.21, s)).toBe(15);
    expect(sharesFor(0.4, s)).toBe(15);
    expect(sharesFor(0.41, s)).toBe(10);
  });

  it('is not confused by bands listed out of order', () => {
    const jumbled: ManualSettings = {
      ...s,
      sizeRules: [
        { maxPrice: 1, shares: 5 },
        { maxPrice: 0.2, shares: 25 },
        { maxPrice: 0.6, shares: 10 },
        { maxPrice: 0.4, shares: 15 },
      ],
    };
    expect(sharesFor(0.1, jumbled)).toBe(25);
    expect(sharesFor(0.5, jumbled)).toBe(10);
  });

  it('sizes from the default stake when the ladder is off', () => {
    const flat: ManualSettings = { ...s, useSizeLadder: false, defaultStakeUsd: 4 };
    expect(sharesFor(0.5, flat)).toBe(8);
    expect(sharesFor(0.2, flat)).toBe(20);
  });

  it('never asks for less than the venue accepts', () => {
    const tiny: ManualSettings = { ...s, useSizeLadder: false, defaultStakeUsd: 1 };
    // $1 at 90¢ is barely one share; the venue's floor wins.
    expect(sharesFor(0.9, tiny)).toBe(5);
    expect(sharesFor(0.9, tiny, 10)).toBe(10);
  });

  it('falls back to the stake when no band covers the price', () => {
    const gappy: ManualSettings = {
      ...s,
      defaultStakeUsd: 6,
      sizeRules: [{ maxPrice: 0.2, shares: 25 }],
    };
    // 50¢ is past every band, so $6 buys twelve shares.
    expect(sharesFor(0.5, gappy)).toBe(12);
  });

  it('refuses a nonsensical price rather than guessing', () => {
    expect(sharesFor(0, s)).toBe(0);
    expect(sharesFor(-0.1, s)).toBe(0);
    expect(sharesFor(Number.NaN, s)).toBe(0);
  });
});

describe('limitShares', () => {
  it('is five shares over the cheap threshold', () => {
    expect(limitShares(0.2)).toBe(5);
    expect(limitShares(0.5)).toBe(5);
    expect(limitShares(0.95)).toBe(5);
  });

  it('sizes cheap prices by money instead', () => {
    // Five shares at 5c is 25c of exposure for the same tap; a dollar is not.
    expect(limitShares(0.05)).toBe(20);
    expect(limitShares(0.1)).toBe(10);
  });

  it('still respects the venue floor when a dollar buys too little', () => {
    expect(limitShares(0.19)).toBeCloseTo(1 / 0.19, 9);
    expect(limitShares(0.5, 10)).toBe(10);
  });

  it('does not divide by a nonsense price', () => {
    expect(limitShares(0)).toBe(5);
    expect(limitShares(Number.NaN)).toBe(5);
  });
});
