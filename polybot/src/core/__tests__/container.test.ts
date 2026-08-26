import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CONTAINER,
  addReserve,
  removeReserve,
  reservedForBots,
  splitFor,
  withBots,
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

describe('withBots', () => {
  const base = { corePct: 0.3, reserves: [] };

  it('leaves the container alone when no bot is running', () => {
    expect(withBots(base, [])).toBe(base);
    expect(withBots(base, [{ name: 'Контр-бот', usd: 0 }])).toBe(base);
  });

  it('counts a running bot as a reserve', () => {
    const c = withBots(base, [
      { name: 'Контр-бот', usd: 5 },
      { name: 'Индикаторы', usd: 6 },
    ]);
    expect(reservedForBots(c)).toBeCloseTo(11, 6);

    // On a $30 deposit: $11 to the bots, 30% of the rest untouchable.
    const split = splitFor(c, 30);
    expect(split.bots).toBeCloseTo(11, 6);
    expect(split.core).toBeCloseTo(9, 6);
    expect(split.free).toBeCloseTo(10, 6);
  });

  it('locks everything when the bots alone outgrow the deposit', () => {
    const split = splitFor(withBots(base, [{ name: 'Индикаторы', usd: 20 }]), 15);
    expect(split.bots).toBeCloseTo(15, 6);
    expect(split.core).toBeCloseTo(0, 6);
    expect(split.free).toBeCloseTo(0, 6);
  });

  it('keeps the stored reserves alongside the bots', () => {
    const stored = { corePct: 0, reserves: [{ id: 'a', name: 'Ручной', usd: 4 }] };
    expect(reservedForBots(withBots(stored, [{ name: 'Контр-бот', usd: 5 }]))).toBeCloseTo(
      9,
      6,
    );
  });
});
