import { describe, expect, it } from 'vitest';
import { pairOrders, realised } from '../trades';
import type { LoggedOrder } from '../../native/polybot';

let id = 0;
const order = (
  action: 'BUY' | 'SELL',
  price: number,
  size: number,
  {
    matched = size,
    status = 'filled',
    outcome = 'Up',
    at = ++id * 1000,
    auto = false,
  }: Partial<{
    matched: number;
    status: string;
    outcome: string;
    at: number;
    auto: boolean;
  }> = {},
): LoggedOrder => ({
  id: ++id,
  orderId: `o${id}`,
  asset: outcome === 'Up' ? 'token-up' : 'token-down',
  outcome,
  action,
  price,
  size,
  matched,
  status,
  placedAt: at,
  auto,
});

describe('pairOrders', () => {
  it('puts a buy and the sell that closed it on one row', () => {
    const rows = pairOrders([
      order('BUY', 0.73, 5, { at: 1 }),
      order('SELL', 0.85, 5, { at: 2 }),
    ]);

    expect(rows).toHaveLength(1);
    expect(rows[0].status).toBe('closed');
    expect(rows[0].buyPrice).toBe(0.73);
    expect(rows[0].sellPrice).toBe(0.85);
    expect(rows[0].shares).toBe(5);
  });

  it('counts the fee on both legs, so a small move is not a profit', () => {
    const rows = pairOrders([
      order('BUY', 0.5, 10, { at: 1 }),
      order('SELL', 0.51, 10, { at: 2 }),
    ]);

    // A cent of movement on either side of 50c does not cover two fees.
    expect(rows[0].pnl as number).toBeLessThan(0);
  });

  it('keeps the two sides apart', () => {
    const rows = pairOrders([
      order('BUY', 0.73, 5, { outcome: 'Up', at: 1 }),
      order('BUY', 0.52, 5, { outcome: 'Down', at: 2 }),
      order('SELL', 0.85, 5, { outcome: 'Up', at: 3 }),
      order('SELL', 0.01, 5, { outcome: 'Down', at: 4 }),
    ]);

    expect(rows).toHaveLength(2);
    const up = rows.find((r) => r.outcome === 'Up')!;
    const down = rows.find((r) => r.outcome === 'Down')!;
    expect(up.pnl as number).toBeGreaterThan(0);
    // Sold at a cent: the whole stake, and then some, is gone.
    expect(down.pnl as number).toBeLessThan(-2.5);
  });

  it('unwinds the oldest lot first', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('BUY', 0.6, 5, { at: 2 }),
      order('SELL', 0.7, 5, { at: 3 }),
    ]);

    const closed = rows.find((r) => r.status === 'closed')!;
    const open = rows.find((r) => r.status === 'open')!;
    expect(closed.buyPrice).toBe(0.4);
    expect(open.buyPrice).toBe(0.6);
  });

  it('splits a sell that covers more than one buy', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('BUY', 0.5, 5, { at: 2 }),
      order('SELL', 0.7, 10, { at: 3 }),
    ]);

    expect(rows).toHaveLength(2);
    expect(rows.every((r) => r.status === 'closed')).toBe(true);
    expect(rows.map((r) => r.buyPrice).sort()).toEqual([0.4, 0.5]);
    expect(rows.reduce((s, r) => s + r.shares, 0)).toBe(10);
  });

  it('shows a resting sell as what the round would make', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.9, 5, { at: 2, matched: 0, status: 'resting' }),
    ]);

    expect(rows).toHaveLength(1);
    expect(rows[0].status).toBe('pending');
    expect(rows[0].sellPrice).toBe(0.9);
    expect(rows[0].pnl as number).toBeGreaterThan(0);
    // Not counted as made: only filled rows are.
    expect(realised(rows)).toBe(0);
  });

  it('separates the filled part of a sell from the part still resting', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 10, { at: 1 }),
      order('SELL', 0.9, 10, { at: 2, matched: 4, status: 'partial' }),
    ]);

    expect(rows).toHaveLength(2);
    expect(rows.find((r) => r.status === 'closed')?.shares).toBeCloseTo(4, 6);
    expect(rows.find((r) => r.status === 'pending')?.shares).toBeCloseTo(6, 6);
  });

  it('keeps a buy that has not filled visible as an order', () => {
    const rows = pairOrders([
      order('BUY', 0.3, 5, { at: 1, matched: 0, status: 'resting' }),
    ]);

    expect(rows).toHaveLength(1);
    expect(rows[0].status).toBe('buying');
    expect(rows[0].pnl).toBeNull();
  });

  it('ignores an order that was pulled without trading', () => {
    const rows = pairOrders([
      order('BUY', 0.3, 5, { at: 1, matched: 0, status: 'cancelled' }),
    ]);

    expect(rows).toHaveLength(0);
  });

  it('adds up to what the window realised', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.6, 5, { at: 2 }),
      order('BUY', 0.5, 5, { outcome: 'Down', at: 3 }),
      order('SELL', 0.3, 5, { outcome: 'Down', at: 4 }),
    ]);

    const sum = rows.reduce((s, r) => s + (r.pnl ?? 0), 0);
    expect(realised(rows)).toBeCloseTo(sum, 9);
  });

  it('says whether the round was closed by the rule or by hand', () => {
    const byRule = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.6, 5, { at: 2, auto: true }),
    ]);
    const byHand = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.6, 5, { at: 2 }),
    ]);
    const open = pairOrders([order('BUY', 0.4, 5, { at: 1 })]);

    expect(byRule[0].closedBy).toBe('rule');
    // A sale made by tapping is a decision, and reads as one.
    expect(byHand[0].closedBy).toBe('hand');
    expect(open[0].closedBy).toBeUndefined();
  });

  it('does not draw a row for float dust', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.6, 4.999, { at: 2 }),
      order('SELL', 0.62, 0.001, { at: 3 }),
    ]);

    // The 0.001 left over is rounding, and read as "0.0 → 0.0" on screen.
    expect(rows).toHaveLength(1);
    expect(rows[0].shares).toBeCloseTo(4.999, 6);
  });

  it('pairs by token when the venue reported a fill with no label', () => {
    const rows = pairOrders([
      order('BUY', 0.54, 5, { outcome: 'Down', at: 1 }),
      // A fill from the trade feed that arrived without an outcome name.
      { ...order('SELL', 0.78, 5, { outcome: 'Down', at: 2 }), outcome: '' },
    ]);

    // Filed under "" it closed nothing, and the purchase read as still open.
    expect(rows).toHaveLength(1);
    expect(rows[0].status).toBe('closed');
    expect(rows[0].outcome).toBe('Down');
  });

  it('puts a freshly closed round above an older purchase', () => {
    const rows = pairOrders([
      order('BUY', 0.36, 5, { outcome: 'Up', at: 1 }),
      order('BUY', 0.54, 5, { outcome: 'Down', at: 2 }),
      // The Down round closes last, so it is the newest thing that happened.
      order('SELL', 0.78, 5, { outcome: 'Down', at: 9 }),
    ]);

    expect(rows[0].status).toBe('closed');
    expect(rows[0].outcome).toBe('Down');
    expect(rows[1].status).toBe('open');
  });

  it('marks a row as automatic when either leg was', () => {
    const rows = pairOrders([
      order('BUY', 0.4, 5, { at: 1 }),
      order('SELL', 0.6, 5, { at: 2, auto: true }),
    ]);

    expect(rows[0].auto).toBe(true);
  });
});
