import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CONTAINER,
  addReserve,
  removeReserve,
  reservedForBots,
  splitFor,
} from '../container';

describe('the container', () => {
  it('keeps a third of the deposit off the table by default', () => {
    const s = splitFor(DEFAULT_CONTAINER, 100);
    expect(s.core).toBeCloseTo(30, 6);
    expect(s.free).toBeCloseTo(70, 6);
  });

  it('measures the share against the deposit, not against the cash left', () => {
    // Same deposit, half of it already in the market: the reserve is unchanged.
    const spent = splitFor(DEFAULT_CONTAINER, 100);
    expect(spent.locked).toBeCloseTo(30, 6);
  });

  it('takes named stakes first, and they are fixed', () => {
    const c = addReserve(DEFAULT_CONTAINER, 'Парный', 20);
    const s = splitFor(c, 100);

    expect(s.bots).toBe(20);
    // A third of what remains after the stake.
    expect(s.core).toBeCloseTo(30, 6);
    expect(s.free).toBeCloseTo(50, 6);
  });

  it('locks everything when the stakes are bigger than the deposit', () => {
    const c = addReserve(DEFAULT_CONTAINER, 'Парный', 200);
    const s = splitFor(c, 100);

    expect(s.bots).toBe(100);
    expect(s.core).toBe(0);
    expect(s.free).toBe(0);
  });

  it('has nothing to split with nothing there', () => {
    expect(splitFor(DEFAULT_CONTAINER, 0).free).toBe(0);
    expect(splitFor(DEFAULT_CONTAINER, Number.NaN).locked).toBe(0);
  });

  it('adds and removes named stakes', () => {
    const one = addReserve(DEFAULT_CONTAINER, 'Парный', 20);
    expect(reservedForBots(one)).toBe(20);

    const two = addReserve(one, 'Терминал', 5);
    expect(reservedForBots(two)).toBe(25);

    expect(reservedForBots(removeReserve(two, two.reserves[0].id))).toBe(5);
  });

  it('refuses a stake that is not an amount', () => {
    expect(addReserve(DEFAULT_CONTAINER, 'Бот', 0).reserves).toHaveLength(0);
    expect(addReserve(DEFAULT_CONTAINER, 'Бот', Number.NaN).reserves).toHaveLength(0);
  });
});
