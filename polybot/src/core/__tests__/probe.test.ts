import { describe, expect, it } from 'vitest';
import { bySide, curve, latest, pnlOf, summarise, traded } from '../probe';
import type { ProbeRound } from '../../native/polybot';

/** One scored window, with only the parts a report reads spelled out. */
const round = (over: Partial<ProbeRound>): ProbeRound => {
  const base: ProbeRound = {
    windowStart: 1_787_817_600,
    demo: true,
    target: 0,
    resting: 0,
    side: 'Up',
    perHour: 120,
    shares: 10,
    price: 0.5,
    sold: 10,
    proceeds: 6,
    settled: 0,
    winner: 'Up',
    pnl: 1,
    right: true,
    note: null,
    open: false,
  };
  const r = { ...base, ...over };
  // A record whose parts disagree with its total is a record no report could
  // be right about, so the fixture keeps them in step: unless the sale is
  // spelled out, it is whatever the stated result implies.
  if (over.pnl !== undefined && over.proceeds === undefined) {
    r.proceeds = r.shares * r.price + over.pnl - r.settled;
  }
  r.pnl = pnlOf(r);
  return r;
};

describe('summarise', () => {
  it('says nothing when nothing has been traded', () => {
    const s = summarise([]);
    expect(s.rounds).toBe(0);
    expect(s.hitRate).toBeNull();
    expect(s.average).toBeNull();
    expect(s.best).toBeNull();
  });

  it('adds the money up across windows', () => {
    const s = summarise([
      round({ shares: 10, price: 0.5, proceeds: 6, pnl: 1 }),
      round({ shares: 10, price: 0.6, proceeds: 4, pnl: -2 }),
    ]);
    expect(s.rounds).toBe(2);
    expect(s.spent).toBeCloseTo(11, 9);
    expect(s.got).toBeCloseTo(10, 9);
    expect(s.pnl).toBeCloseTo(-1, 9);
    expect(s.average).toBeCloseTo(-0.5, 9);
  });

  it('counts the settlement as money as much as a sale', () => {
    const s = summarise([
      round({ shares: 10, price: 0.5, sold: 0, proceeds: 0, settled: 10, pnl: 5 }),
    ]);
    expect(s.settled).toBeCloseTo(10, 9);
    expect(s.pnl).toBeCloseTo(5, 9);
    expect(s.toSettlement).toBe(1);
    expect(s.byLadder).toBe(0);
  });

  it('splits wins from losses, and calls a cent flat', () => {
    const s = summarise([
      round({ pnl: 1 }),
      round({ pnl: -1 }),
      round({ pnl: 0.001 }),
    ]);
    expect(s.wins).toBe(1);
    expect(s.losses).toBe(1);
    expect(s.flat).toBe(1);
  });

  it('scores whether the line called the direction, apart from the money', () => {
    // Right twice, wrong once — and the money says the opposite, which is
    // exactly why the two are counted separately.
    const s = summarise([
      round({ right: true, pnl: 0.2 }),
      round({ right: true, pnl: 0.2 }),
      round({ right: false, winner: 'Down', pnl: -5 }),
    ]);
    expect(s.scored).toBe(3);
    expect(s.called).toBe(2);
    expect(s.hitRate).toBeCloseTo(2 / 3, 9);
    expect(s.pnl).toBeCloseTo(-4.6, 9);
  });

  it('leaves a window with no result out of the hit rate', () => {
    const s = summarise([round({ winner: '', right: false })]);
    expect(s.rounds).toBe(1);
    expect(s.scored).toBe(0);
    expect(s.hitRate).toBeNull();
  });

  it('ignores what is still riding', () => {
    const s = summarise([round({ open: true, pnl: 99 }), round({ pnl: 1 })]);
    expect(s.rounds).toBe(1);
    expect(s.pnl).toBeCloseTo(1, 9);
  });

  it('keeps the best and the worst window', () => {
    const s = summarise([round({ pnl: 3 }), round({ pnl: -4 }), round({ pnl: 1 })]);
    expect(s.best).toBeCloseTo(3, 9);
    expect(s.worst).toBeCloseTo(-4, 9);
  });
});

describe('bySide', () => {
  it('keeps the two directions apart', () => {
    const both = bySide([
      round({ side: 'Up', pnl: 2 }),
      round({ side: 'Up', pnl: 1 }),
      round({ side: 'Down', pnl: -3 }),
    ]);
    expect(both.up.rounds).toBe(2);
    expect(both.up.pnl).toBeCloseTo(3, 9);
    expect(both.down.rounds).toBe(1);
    expect(both.down.pnl).toBeCloseTo(-3, 9);
  });
});

describe('latest', () => {
  it('trims from the newest end, which is where the record starts', () => {
    const rows = [round({ pnl: 1 }), round({ pnl: 2 }), round({ pnl: 3 })];
    expect(latest(rows, 2).map((r) => r.pnl)).toEqual([1, 2]);
    expect(latest(rows, 0)).toEqual([]);
    expect(latest(rows, 9)).toHaveLength(3);
  });
});

describe('curve', () => {
  it('runs the total forward in time, oldest first', () => {
    // The record is newest first, so the curve reads it backwards.
    const rows = [round({ pnl: 3 }), round({ pnl: -1 }), round({ pnl: 2 })];
    expect(curve(rows)).toEqual([2, 1, 4]);
  });

  it('is empty until a window has closed', () => {
    expect(curve([round({ open: true, pnl: 5 })])).toEqual([]);
  });
});

describe('windows it stood out of', () => {
  const skip = round({ shares: 0, price: 0, proceeds: 0, note: 'у разворота 78420' });

  it('is not a trade', () => {
    expect(traded(skip)).toBe(false);
    expect(traded(round({ shares: 5 }))).toBe(true);
  });

  it('is left out of the money and the averages', () => {
    // Otherwise a run that mostly stood aside reads as a run of flat trades.
    const s = summarise([skip, round({ pnl: 2 })]);
    expect(s.rounds).toBe(1);
    expect(s.flat).toBe(0);
    expect(s.average).toBeCloseTo(2, 9);
  });

  it('is left out of the curve', () => {
    expect(curve([skip, round({ pnl: 1 })])).toEqual([1]);
  });
});
