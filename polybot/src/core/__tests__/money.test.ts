import { describe, expect, it } from 'vitest';
import {
  breakEvenPrice,
  ceilToTick,
  feePerShare,
  limitLadder,
  standingOf,
  limitUpside,
  potentialProfit,
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

describe('potentialProfit', () => {
  it('is the winning side less what the window cost', () => {
    // 10 shares of Up at 40c: $4 paid, $10 back if Up wins. The taker fee on a
    // buy is taken in shares, so the average price already carries it and the
    // shares held are what is left — counting it again here charged it twice.
    expect(potentialProfit([{ outcome: 'Up', size: 10, avgPrice: 0.4 }])).toBeCloseTo(
      6,
      6,
    );
  });

  it('scores the sides separately, since only one can win', () => {
    const p = potentialProfit([
      { outcome: 'Up', size: 10, avgPrice: 0.4 },
      { outcome: 'Down', size: 4, avgPrice: 0.5 },
    ]);
    // Up winning pays 10; both sides together cost 6.
    expect(p).toBeCloseTo(4, 6);
  });

  it('has nothing to say with nothing held', () => {
    expect(potentialProfit([])).toBe(0);
    expect(potentialProfit([{ outcome: 'Up', size: 0, avgPrice: 0.4 }])).toBe(0);
  });
});

describe('limitUpside', () => {
  it('is what the shares gain on the way to a dollar', () => {
    expect(limitUpside(10, 0.6)).toBeCloseTo(10 * 0.4 - 0.0168 * 10, 6);
  });
});

describe('limitLadder', () => {
  it('steps down from the price asked for', () => {
    expect(limitLadder(0.6, 3, 0.03)).toEqual([0.6, 0.57, 0.54, 0.51]);
  });

  it('stops where prices stop', () => {
    expect(limitLadder(0.05, 3, 0.03)).toEqual([0.05, 0.02]);
  });

  it('is just the one price when the ladder is off', () => {
    expect(limitLadder(0.6, 0, 0.03)).toEqual([0.6]);
  });
});

describe('standingOf', () => {
  const held = (outcome: string, size: number, avgPrice: number, curPrice: number) => ({
    outcome,
    size,
    avgPrice,
    curPrice,
  });

  it('is empty when nothing is held', () => {
    const s = standingOf([]);
    expect(s.cost).toBe(0);
    expect(s.now).toBe(0);
    expect(s.both).toBe(false);
  });

  it('prices one side at the bid less the fee, and at a dollar if it wins', () => {
    // 10 shares bought at 40c, now bid 60c.
    const s = standingOf([held('Up', 10, 0.4, 0.6)]);
    expect(s.cost).toBeCloseTo(4, 6);
    // net(0.6) = 0.6 - 0.07*0.6*0.4 = 0.5832
    expect(s.now).toBeCloseTo(10 * 0.5832 - 4, 6);
    // Settlement is a contract call, not a trade: a winning share pays a flat
    // dollar with nothing taken out of it.
    expect(s.ifUp).toBeCloseTo(6, 6);
    expect(s.ifDown).toBeCloseTo(-4, 6);
    expect(s.both).toBe(false);
  });

  it('knows a two-sided window can only end two ways', () => {
    // 10 Up at 40c and 10 Down at 45c: $8.50 in, $10 back either way.
    const s = standingOf([held('Up', 10, 0.4, 0.55), held('Down', 10, 0.45, 0.45)]);
    expect(s.cost).toBeCloseTo(8.5, 6);
    expect(s.ifUp).toBeCloseTo(1.5, 6);
    expect(s.ifDown).toBeCloseTo(1.5, 6);
    expect(s.both).toBe(true);
    expect(s.worst).toBeCloseTo(1.5, 6);
  });

  it('shows the lopsided case as the two numbers it is', () => {
    // 20 Up at 30c and 5 Down at 50c: $8.50 in, $20 or $5 back.
    const s = standingOf([held('Up', 20, 0.3, 0.6), held('Down', 5, 0.5, 0.4)]);
    expect(s.ifUp).toBeCloseTo(11.5, 6);
    expect(s.ifDown).toBeCloseTo(-3.5, 6);
    expect(s.worst).toBeCloseTo(-3.5, 6);
    expect(s.both).toBe(true);
  });

  it('adds up several lots of the same side', () => {
    const s = standingOf([held('Up', 5, 0.2, 0.5), held('Up', 5, 0.6, 0.5)]);
    expect(s.cost).toBeCloseTo(4, 6);
    expect(s.ifUp).toBeCloseTo(6, 6);
  });
});
