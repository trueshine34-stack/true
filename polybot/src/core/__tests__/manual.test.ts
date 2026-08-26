import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MANUAL_SETTINGS,
  balanceShares,
  limitShares,
  minShares,
  cappedShares,
  exposureFor,
  orderCost,
  sellableShares,
  sharesFor,
  spendableBalance,
  stakeShares,
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

describe('balanceShares', () => {
  it('spends the set share of what is actually spendable', () => {
    // 25% of $40 is $10, but the taker fee comes on top of the order, so the
    // share is taken of the wallet less that reserve: 3.5% at 50c.
    expect(balanceShares(0.5, 40, 0.25)).toBeCloseTo((40 * 0.965 * 0.25) / 0.5, 9);
    expect(balanceShares(0.25, 40, 0.25)).toBeCloseTo((40 * 0.9475 * 0.25) / 0.25, 9);
  });

  it('buys far more of a cheap side for roughly the same money', () => {
    const dear = balanceShares(0.8, 40, 0.25)!;
    const cheap = balanceShares(0.2, 40, 0.25)!;

    expect(cheap / dear).toBeGreaterThan(3.5);
    // Not identical any more: the cheap side reserves a bigger fee, because at
    // 20c the fee really is a bigger slice of the order.
    expect(cheap * 0.2).toBeLessThan(dear * 0.8);
    expect(cheap * 0.2).toBeGreaterThan(dear * 0.8 * 0.9);
  });

  it('refuses rather than quietly overspending the share', () => {
    // 25% of $4 is $1, which at 50c is two shares — under the venue floor.
    // Rounding up to five would spend $2.50, more than double the rule.
    expect(balanceShares(0.5, 4, 0.25)).toBeNull();
  });

  it('has nothing to say without a balance or a price', () => {
    expect(balanceShares(0.5, 0, 0.25)).toBeNull();
    expect(balanceShares(0, 40, 0.25)).toBeNull();
    expect(balanceShares(Number.NaN, 40, 0.25)).toBeNull();
  });

  it('honours a different floor', () => {
    expect(balanceShares(0.5, 40, 0.25, 25)).toBeNull();
    expect(balanceShares(0.5, 40, 0.25, 15)).toBeCloseTo(19.3, 9);
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
  it('the ladder cannot ask for less than a dollar', () => {
    const cheapBand: ManualSettings = {
      ...DEFAULT_MANUAL_SETTINGS,
      sizeRules: [{ maxPrice: 1, shares: 5 }],
    };
    // Five shares at 5c would be rejected; the floor lifts it to a dollar.
    expect(sharesFor(0.05, cheapBand)).toBe(20);
    expect(sharesFor(0.5, cheapBand)).toBe(5);
  });

  it('a limit at a cheap price is sized by money', () => {
    expect(limitShares(0.05)).toBe(20);
    expect(limitShares(0.02)).toBe(50);
  });

  it('a balance share too small for the dollar floor is refused', () => {
    // 25% of $40 is $10 — fine at 5c. 25% of $2 is 50c — not.
    expect(balanceShares(0.05, 40, 0.25)).toBeCloseTo((40 * 0.9335 * 0.25) / 0.05, 9);
    expect(balanceShares(0.05, 2, 0.25)).toBeNull();
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
    const shares = balanceShares(0.5, 100, 1)!;
    const spend = shares * 0.5;
    const fee = 0.07 * 0.5 * 0.5 * shares;
    expect(spend).toBeLessThan(100);
    expect(spend + fee).toBeLessThanOrEqual(100);
  });

  it('half the wallet still leaves the other half alone', () => {
    const shares = balanceShares(0.5, 100, 0.5)!;
    expect(shares * 0.5).toBeCloseTo(48.25, 9);
  });
});

describe('sellableShares', () => {
  it('trims three percent off the position', () => {
    expect(sellableShares(100)).toBeCloseTo(97, 6);
  });

  it('never asks for more than is held', () => {
    // 15.69 held: rounding to a tenth alone asks for 15.7 and is refused.
    expect(sellableShares(15.69)).toBeLessThanOrEqual(15.69);
    expect(sellableShares(15.69)).toBeCloseTo(15.2, 6);
  });

  it('trims by the fee where the fee is bigger than three percent', () => {
    // At 20c the fee is 5.6% of the share count, so 3% would still over-ask.
    expect(sellableShares(100, 0.2)).toBeCloseTo(94.4, 6);
    expect(sellableShares(100, 0.9)).toBeCloseTo(97, 6);
  });

  it('lands on a tenth, without float dust', () => {
    for (const size of [15.69, 7.77, 42.03, 5.5, 100]) {
      const s = sellableShares(size, 0.43);
      expect(Math.abs(s * 10 - Math.round(s * 10))).toBeLessThan(1e-9);
    }
  });

  it('offers a dust position whole rather than nothing', () => {
    expect(sellableShares(0.1)).toBeCloseTo(0.1, 6);
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
  it('measures the cap against cash plus what is already in the market', () => {
    // 30 free, 30 committed: the deposit is 60, half of it is 30, and that is
    // already used up.
    const e = exposureFor(30, 30, 0.5);
    expect(e.equity).toBe(60);
    expect(e.cap).toBe(30);
    expect(e.room).toBe(0);
    expect(e.full).toBe(true);
  });

  it('leaves room while under the line', () => {
    const e = exposureFor(80, 20, 0.5);
    expect(e.cap).toBe(50);
    expect(e.room).toBe(30);
    expect(e.full).toBe(false);
  });

  it('never offers more room than there is cash', () => {
    // Nothing committed, so half the deposit is half the cash.
    const e = exposureFor(10, 0, 0.5);
    expect(e.room).toBe(5);
  });

  it('is not fooled by the balance falling as it is spent', () => {
    // Buying moves money from cash to committed; the deposit is unchanged, so
    // the cap does not slide down with it.
    const before = exposureFor(100, 0, 0.5);
    const after = exposureFor(60, 40, 0.5);
    expect(after.cap).toBe(before.cap);
    expect(after.room).toBe(10);
  });

  it('has no room at all when the guard is set to nothing', () => {
    expect(exposureFor(100, 0, 0).room).toBe(0);
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
