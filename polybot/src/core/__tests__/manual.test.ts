import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MANUAL_SETTINGS,
  MIN_ROOM_USD,
  bigPrice,
  buyBarred,
  buyCeiling,
  cappedShares,
  exposureFor,
  limitShares,
  minShares,
  openMark,
  orderCost,
  sellableShares,
  spendableBalance,
  stakeShares,
  windowCapPct,
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
  it('caps a window at the share the deposit has earned', () => {
    // 30 free, 10 committed: the deposit is 40, small enough for a quarter, so
    // this window may hold 10 — which it already does.
    const e = exposureFor(30, 10);
    expect(e.equity).toBe(40);
    expect(e.pct).toBeCloseTo(0.25, 9);
    expect(e.cap).toBeCloseTo(10, 9);
    // Down to the floor, which the cap never takes away.
    expect(e.room).toBeCloseTo(MIN_ROOM_USD, 9);
    expect(e.full).toBe(false);
  });

  it('leaves room while under the line', () => {
    const e = exposureFor(46, 4);
    expect(e.cap).toBeCloseTo(12.5, 9);
    expect(e.room).toBeCloseTo(8.5, 9);
    expect(e.full).toBe(false);
  });

  /**
   * The floor is what makes the cap a size rule rather than a lockout — on a
   * small deposit a quarter is under the venue's own smallest order, so
   * without it the cap would forbid every trade there is.
   */
  it('always leaves the floor, however far past its share the window is', () => {
    const e = exposureFor(20, 80);
    expect(e.cap).toBeCloseTo(100 * windowCapPct(100), 9);
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
    const before = exposureFor(40, 0);
    const after = exposureFor(30, 10);
    expect(after.cap).toBeCloseTo(before.cap, 9);
    expect(after.pct).toBeCloseTo(before.pct, 9);
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

describe('windowCapPct', () => {
  it('is a quarter while the deposit is small', () => {
    expect(windowCapPct(10)).toBeCloseTo(0.25, 9);
    expect(windowCapPct(50)).toBeCloseTo(0.25, 9);
  });

  it('is a percent from ten thousand on', () => {
    expect(windowCapPct(10_000)).toBeCloseTo(0.01, 9);
    expect(windowCapPct(80_000)).toBeCloseTo(0.01, 9);
  });

  /**
   * A straight line in log-log: the share falls by the same proportion for
   * every doubling, rather than by cliffs an account can be nudged over.
   */
  it('falls smoothly between the two', () => {
    expect(windowCapPct(500)).toBeCloseTo(0.0617, 4);
    expect(windowCapPct(2000)).toBeCloseTo(0.0266, 4);

    // Monotone, and never outside its own ends.
    let last = 1;
    for (let usd = 10; usd <= 20_000; usd *= 1.3) {
      const pct = windowCapPct(usd);
      expect(pct).toBeLessThanOrEqual(last + 1e-12);
      expect(pct).toBeGreaterThanOrEqual(0.01 - 1e-12);
      expect(pct).toBeLessThanOrEqual(0.25 + 1e-12);
      last = pct;
    }
  });

  /** The money at risk still grows the whole way; only its share shrinks. */
  it('lets the size in dollars keep rising', () => {
    const at = (usd: number) => usd * windowCapPct(usd);
    expect(at(50)).toBeCloseTo(12.5, 6);
    expect(at(500)).toBeGreaterThan(at(50));
    expect(at(10_000)).toBeCloseTo(100, 6);
    expect(at(10_000)).toBeGreaterThan(at(500));
  });

  it('is what the guard uses unless it is told otherwise', () => {
    expect(exposureFor(400, 100).pct).toBeCloseTo(windowCapPct(500), 9);
    expect(exposureFor(400, 100, 0.5).pct).toBeCloseTo(0.5, 9);
  });
});

describe('a resting buy is money already spoken for', () => {
  it('is not offered again as spendable cash', () => {
    // The venue reports the wallet, not what is left of it: a $10 buy resting
    // against a $50 wallet still reads as $50, and sizing the next order from
    // that number spends the same dollars twice.
    const e = exposureFor(50 - 10, 10);
    expect(e.equity).toBe(50);
    expect(e.balance).toBe(40);
    expect(e.committed).toBe(10);
    // A quarter of fifty is 12.50, of which ten is already promised — and the
    // floor is what is left standing.
    expect(e.room).toBeCloseTo(MIN_ROOM_USD, 5);
  });

  it('still leaves the whole cap open when nothing is resting', () => {
    const e = exposureFor(50, 0);
    expect(e.room).toBeCloseTo(12.5, 5);
  });
});

describe('buyCeiling', () => {
  it('holds buys to 63c through the first minute', () => {
    expect(buyCeiling(0)).toBe(0.63);
    expect(buyCeiling(59)).toBe(0.63);
  });

  it('lifts to 77c for the second and third minutes', () => {
    expect(buyCeiling(60)).toBe(0.77);
    expect(buyCeiling(179)).toBe(0.77);
  });

  it('leaves the fourth minute open', () => {
    expect(buyCeiling(180)).toBe(1);
    expect(buyCeiling(239)).toBe(1);
  });

  it('closes the last minute at 91c', () => {
    // A side dearer than this with under a minute left is paying most of a
    // dollar for a few cents, against a loss of the whole stake.
    expect(buyCeiling(240)).toBe(0.91);
    expect(buyCeiling(299)).toBe(0.91);
  });

  it('treats a window that has not started as its first minute', () => {
    // Looking ahead to the next window: nothing has happened in it yet, so the
    // early rule is exactly the rule that applies.
    expect(buyCeiling(-30)).toBe(0.63);
  });

  it('bars a price over the ceiling and allows one on it', () => {
    expect(buyBarred(0.64, 10)).toBe(true);
    expect(buyBarred(0.63, 10)).toBe(false);
    expect(buyBarred(0.7, 10)).toBe(true);
    expect(buyBarred(0.78, 120)).toBe(true);
    expect(buyBarred(0.77, 120)).toBe(false);
    expect(buyBarred(0.95, 200)).toBe(false);
    expect(buyBarred(0.92, 260)).toBe(true);
    expect(buyBarred(0.91, 260)).toBe(false);
  });
});

describe('openMark', () => {
  it('writes the change the way the venue writes it', () => {
    const m = openMark(78_251, 78_261.84)!;
    expect(m.way).toBe('up');
    expect(m.arrow).toBe('▲');
    expect(m.text).toBe('$11');
  });

  it('turns the arrow over when the window is under its open', () => {
    const m = openMark(78_251, 78_205)!;
    expect(m.way).toBe('down');
    expect(m.arrow).toBe('▼');
    expect(m.text).toBe('$46');
  });

  it('claims no direction for a change that rounds to nothing', () => {
    const m = openMark(78_251, 78_251.4)!;
    expect(m.way).toBe('flat');
    expect(m.text).toBe('$0');
  });

  it('has nothing to show without both prices', () => {
    expect(openMark(null, 78_251)).toBeNull();
    expect(openMark(78_251, null)).toBeNull();
    expect(openMark(0, 78_251)).toBeNull();
    expect(openMark(78_251, Number.NaN)).toBeNull();
  });

  it('keeps the signed change for whoever needs the number', () => {
    expect(openMark(100, 90)!.change).toBeCloseTo(-10, 9);
  });
});

describe('bigPrice', () => {
  it('groups the thousands and drops the cents', () => {
    // A non-breaking space is what the locale groups with.
    expect(bigPrice(78_261.84).replace(/\s/g, ' ')).toBe('78 262');
  });

  it('says nothing when there is no price', () => {
    expect(bigPrice(null)).toBe('—');
    expect(bigPrice(0)).toBe('—');
  });
});

describe('the ladder default', () => {
  it('has a rung for every thirty seconds of the window', () => {
    // Five rungs at that step ran out at the halfway mark; ten reach the
    // close, which is where the last one is actually needed.
    expect(DEFAULT_MANUAL_SETTINGS.autoSellLadder).toHaveLength(10);
    expect(DEFAULT_MANUAL_SETTINGS.autoSellStepSec).toBe(30);
  });

  it('still climbs, and still ends where it used to', () => {
    const ladder = DEFAULT_MANUAL_SETTINGS.autoSellLadder;
    expect(ladder[0]).toBeCloseTo(0.77, 9);
    expect(ladder[ladder.length - 1]).toBeCloseTo(0.97, 9);
    for (let i = 1; i < ladder.length; i++) {
      expect(ladder[i]).toBeGreaterThan(ladder[i - 1]);
    }
  });
});
