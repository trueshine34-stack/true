import { describe, expect, it } from 'vitest';
import { nextLotCost, uncoveredLots } from '../lots';

const buy = (price: number, shares: number, at: number, fill?: number) => ({
  action: 'BUY' as const,
  price,
  fillPrice: fill ?? null,
  size: shares,
  matched: shares,
  status: 'filled',
  placedAt: at,
});

const sell = (price: number, shares: number, at: number, status = 'resting') => ({
  action: 'SELL' as const,
  price,
  fillPrice: null,
  size: shares,
  matched: status === 'filled' ? shares : 0,
  status,
  placedAt: at,
});

describe('a position as the purchases it is made of', () => {
  it('keeps each purchase at its own price, oldest first', () => {
    const lots = uncoveredLots([buy(0.56, 15, 1), buy(0.66, 13, 2)]);
    expect(lots.map((l) => l.price)).toEqual([0.56, 0.66]);
    expect(lots.map((l) => l.shares)).toEqual([15, 13]);
  });

  it('measures a purchase by what it went at, not what was asked', () => {
    // Asked 41, filled at 23: the exit is priced off what it cost.
    expect(nextLotCost([buy(0.41, 10, 1, 0.23)])).toBeCloseTo(0.23, 9);
  });

  it('counts an offer still on the book as an exit arranged', () => {
    const lots = uncoveredLots([buy(0.56, 15, 1), buy(0.66, 13, 2), sell(0.62, 15, 3)]);
    expect(lots).toHaveLength(1);
    expect(lots[0]?.price).toBeCloseTo(0.66, 9);
  });

  it('does not count a pulled offer', () => {
    const lots = uncoveredLots([buy(0.56, 15, 1), sell(0.62, 15, 3, 'cancelled')]);
    expect(lots).toHaveLength(1);
    expect(lots[0]?.price).toBeCloseTo(0.56, 9);
  });

  it('takes a part-covered purchase down to what is left of it', () => {
    const lots = uncoveredLots([buy(0.56, 15, 1), sell(0.62, 6, 3)]);
    expect(lots).toHaveLength(1);
    expect(lots[0]?.shares).toBeCloseTo(9, 9);
  });

  it('has nothing to measure once every purchase is covered', () => {
    expect(nextLotCost([buy(0.56, 15, 1), sell(0.62, 15, 3)])).toBeNull();
    expect(nextLotCost([])).toBeNull();
  });

  it('names the next purchase, which is what the rule will cover', () => {
    // The 56c lot is offered; the chip must now be about the 66c one.
    expect(
      nextLotCost([buy(0.56, 15, 1), buy(0.66, 13, 2), sell(0.62, 15, 3)]),
    ).toBeCloseTo(0.66, 9);
  });
});
