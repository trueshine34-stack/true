import { describe, expect, it } from 'vitest';
import {
  limitShares,
  minShares,
  cappedShares,
  exposureFor,
  MIN_ROOM_USD,
  orderCost,
  sellableShares,
  spendableBalance,
  stakeShares,
} from '../manual';

/**
 * The size ladder decides how much real money one tap spends, so the bands and
 * their edges are worth pinning down exactly.
 */
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

describe('minShares', () => {
  it('is the venue share count at ordinary prices', () => {
    expect(minShares(0.5)).toBe(5);
    expect(minShares(0.2)).toBe(5);
  });

  it('becomes the dollar floor once shares stop reaching it', () => {
    // Five shares at 5c is 25c — simply rejected. A dollar is twenty shares.
    expect(minShares(0.05)).toBe(20);
    expect(minShares(0.1)).toBe(10);
    expect(minShares(0.02)).toBe(50);
  });

  it('crosses over exactly where a dollar buys the share minimum', () => {
    expect(minShares(0.2)).toBe(5);
    expect(minShares(0.19)).toBeCloseTo(1 / 0.19, 9);
  });

  it('never asks for a nonsense size', () => {
    expect(minShares(0)).toBe(5);
    expect(minShares(Number.NaN)).toBe(5);
  });
});

describe('sizing respects the dollar floor', () => {
  it('a limit at a cheap price is sized by money', () => {
    expect(limitShares(0.05)).toBe(20);
    expect(limitShares(0.02)).toBe(50);
  });
});

describe('spendableBalance', () => {
  it('holds back at least two percent', () => {
    expect(spendableBalance(100, 0.9)).toBeCloseTo(98, 9);
    expect(spendableBalance(100, 0.95)).toBeCloseTo(98, 9);
  });

  it('holds back the real fee where it is larger than two percent', () => {
    // At 20c the taker fee is 5.6% of the order's value; two would not cover it.
    expect(spendableBalance(100, 0.2)).toBeCloseTo(94.4, 9);
    expect(spendableBalance(100, 0.5)).toBeCloseTo(96.5, 9);
  });

  it('always leaves enough to pay the fee it will be charged', () => {
    for (const price of [0.05, 0.2, 0.5, 0.8, 0.95]) {
      const spend = spendableBalance(100, price);
      const shares = spend / price;
      const fee = 0.07 * price * (1 - price) * shares;
      expect(spend + fee).toBeLessThanOrEqual(100 + 1e-9);
    }
  });

  it('has nothing to offer from an empty wallet', () => {
    expect(spendableBalance(0, 0.5)).toBe(0);
    expect(spendableBalance(-5, 0.5)).toBe(0);
  });
});

describe('balance sizing leaves room for the fee', () => {
  it('a hundred percent no longer asks for the whole wallet', () => {
    const shares = stakeShares(0.5, 100, 1)!;
    const spend = shares * 0.5;
    const fee = 0.07 * 0.5 * 0.5 * shares;
    expect(spend).toBeLessThan(100);
    expect(spend + fee).toBeLessThanOrEqual(100);
  });

  it('half the wallet still leaves the other half alone', () => {
    // Whole shares, so a little under half rather than exactly it.
    expect(stakeShares(0.5, 100, 0.5)! * 0.5).toBeLessThanOrEqual(48.25);
    expect(stakeShares(0.5, 100, 0.5)! * 0.5).toBeGreaterThan(47);
  });

  it('a share too small for the dollar floor is refused', () => {
    // 25% of $40 is $10 — fine at 5c. 25% of $2 is 50c — not.
    expect(stakeShares(0.05, 40, 0.25)).toBeGreaterThan(100);
    expect(stakeShares(0.05, 2, 0.25)).toBeNull();
  });
});

describe('sellableShares', () => {
  it('offers the whole position', () => {
    expect(sellableShares(100)).toBeCloseTo(100, 6);
    expect(sellableShares(15.69)).toBeCloseTo(15.69, 6);
  });

  it('never asks for more than is held', () => {
    for (const size of [15.694, 7.7777, 42.039, 5.555, 0.109]) {
      expect(sellableShares(size)).toBeLessThanOrEqual(size);
    }
  });

  it('lands on the venue step, without float dust', () => {
    for (const size of [15.69, 7.77, 42.03, 5.5, 100]) {
      const s = sellableShares(size);
      expect(Math.abs(s * 100 - Math.round(s * 100))).toBeLessThan(1e-9);
    }
  });

  it('keeps a dust position whole', () => {
    expect(sellableShares(0.1)).toBeCloseTo(0.1, 6);
    expect(sellableShares(0.01)).toBeCloseTo(0.01, 6);
  });

  it('is zero for nothing held', () => {
    expect(sellableShares(0)).toBe(0);
    expect(sellableShares(Number.NaN)).toBe(0);
  });
});

describe('stakeShares', () => {
  it('rounds the wallet share down to whole shares', () => {
    // 100 $ at 50c: 96.5 spendable, half of that is 48.25 -> 96.5 shares.
    expect(stakeShares(0.5, 100, 0.5)).toBe(96);
  });

  it('stays inside the balance the fee narrowed', () => {
    const shares = stakeShares(0.5, 100, 1) as number;
    expect(shares * 0.5 * 1.035).toBeLessThanOrEqual(100);
  });

  it('refuses a share too small for the venue floor', () => {
    expect(stakeShares(0.5, 4, 0.25)).toBeNull();
  });

  it('keeps the venue floor when flooring would drop under it', () => {
    // 20 $ at 90c, a quarter of it is ~5.3 shares - flooring lands under five.
    const shares = stakeShares(0.9, 20, 0.25);
    expect(shares).not.toBeNull();
    expect(shares as number).toBeGreaterThanOrEqual(5);
  });
});


describe('exposureFor', () => {
  it('caps a window at a quarter of the deposit', () => {
    // 300 free, 100 committed: the deposit is 400, so this window may hold 100
    // — which it already does.
    const e = exposureFor(300, 100);
    expect(e.equity).toBe(400);
    expect(e.cap).toBeCloseTo(100, 9);
    // Down to the floor, which the cap never takes away.
    expect(e.room).toBeCloseTo(MIN_ROOM_USD, 9);
    expect(e.full).toBe(false);
  });

  it('leaves room while under the line', () => {
    const e = exposureFor(96, 4);
    expect(e.cap).toBeCloseTo(25, 9);
    expect(e.room).toBeCloseTo(21, 9);
    expect(e.full).toBe(false);
  });

  /**
   * The floor is what makes the cap a size rule rather than a lockout — on a
   * small deposit a quarter is under the venue's own smallest order, so
   * without it the cap would forbid every trade there is.
   */
  it('always leaves the floor, however far past its share the window is', () => {
    const e = exposureFor(20, 80);
    expect(e.cap).toBeCloseTo(25, 9);
    expect(e.room).toBeCloseTo(MIN_ROOM_USD, 9);
    expect(e.full).toBe(false);
  });

  it('but never more than the cash that is actually there', () => {
    const e = exposureFor(1, 80);
    expect(e.room).toBeCloseTo(1, 9);
    expect(exposureFor(0, 80).room).toBe(0);
    expect(exposureFor(0, 80).full).toBe(true);
  });

  it('is not fooled by the balance falling as it is spent', () => {
    // Buying moves money from cash to committed; the deposit is unchanged, so
    // the cap does not slide down with it.
    const before = exposureFor(100, 0);
    const after = exposureFor(80, 20);
    expect(after.cap).toBeCloseTo(before.cap, 9);
    expect(after.room).toBeCloseTo(5, 9);
  });

  it('takes another share when one is asked for', () => {
    expect(exposureFor(100, 0, 0.5).cap).toBeCloseTo(50, 9);
    expect(exposureFor(100, 0, 1).room).toBeCloseTo(100, 9);
    // Even a share of nothing leaves the floor.
    expect(exposureFor(100, 0, 0).room).toBeCloseTo(MIN_ROOM_USD, 9);
  });
});

describe('cappedShares', () => {
  it('leaves an order that fits alone', () => {
    expect(cappedShares(5, 0.4, 100)).toBe(5);
  });

  it('trims one that does not, and the trim really fits', () => {
    const shares = cappedShares(100, 0.4, 10) as number;
    expect(shares).toBeLessThan(100);
    expect(orderCost(shares, 0.4)).toBeLessThanOrEqual(10);
  });

  it('counts the fee, not just the price', () => {
    // 25 shares at 40c is exactly 10 before the fee, and over it after.
    expect(cappedShares(25, 0.4, 10) as number).toBeLessThan(25);
  });

  it('refuses when even the venue minimum would not fit', () => {
    expect(cappedShares(5, 0.4, 1)).toBeNull();
    expect(cappedShares(5, 0.4, 0)).toBeNull();
  });
});
