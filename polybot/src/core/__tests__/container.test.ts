import { describe, expect, it } from 'vitest';
import {
  DEFAULT_CONTAINER,
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

  it('has nothing to split with nothing there', () => {
    expect(splitFor(DEFAULT_CONTAINER, 0).free).toBe(0);
    expect(splitFor(DEFAULT_CONTAINER, Number.NaN).locked).toBe(0);
  });

});

describe('reserves left over from an older version', () => {
  const stored = {
    corePct: 0.3,
    reserves: [{ id: 'a', name: 'Парный', usd: 20 }],
  };

  it('are still honoured, and still removable', () => {
    expect(reservedForBots(stored)).toBeCloseTo(20, 6);
    expect(splitFor(stored, 100).bots).toBeCloseTo(20, 6);

    const cleared = removeReserve(stored, 'a');
    expect(cleared.reserves).toHaveLength(0);
    expect(splitFor(cleared, 100).bots).toBe(0);
  });
});
