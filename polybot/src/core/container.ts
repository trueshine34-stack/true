import { Preferences } from '@capacitor/preferences';

/**
 * The container: money that is on the exchange and not for trading.
 *
 * A balance is not a budget. Everything in the wallet reads as available, and
 * what reads as available gets used — which is how a run that was up ends the
 * day at zero. The container takes a share off the top and keeps it off: a
 * fixed part of the deposit that no order can reach, so a five-minute market
 * can never have all of it.
 *
 * It also holds what is set aside for a particular strategy. Those are fixed
 * amounts rather than shares, because a bot is given a stake, not a percentage.
 */
export type BotReserve = {
  id: string;
  name: string;
  usd: number;
};

export type Container = {
  /** Share of the deposit kept untouchable, as a fraction. */
  corePct: number;
  reserves: BotReserve[];
};

const KEY = 'container.v1';

export const DEFAULT_CONTAINER: Container = { corePct: 0.3, reserves: [] };

export async function loadContainer(): Promise<Container> {
  const { value } = await Preferences.get({ key: KEY });
  if (!value) return DEFAULT_CONTAINER;
  try {
    const parsed = JSON.parse(value) as Container;
    return {
      corePct: Number.isFinite(parsed?.corePct) ? parsed.corePct : DEFAULT_CONTAINER.corePct,
      reserves: Array.isArray(parsed?.reserves)
        ? parsed.reserves.filter((r) => Number.isFinite(r.usd) && r.usd > 0)
        : [],
    };
  } catch {
    return DEFAULT_CONTAINER;
  }
}

export async function saveContainer(container: Container): Promise<void> {
  await Preferences.set({ key: KEY, value: JSON.stringify(container) });
}

export function addReserve(container: Container, name: string, usd: number): Container {
  if (!Number.isFinite(usd) || usd <= 0) return container;
  return {
    ...container,
    reserves: [
      ...container.reserves,
      {
        // Two stakes added in the same millisecond would otherwise share an id,
        // and removing one would remove both.
        id: `r${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
        name: name.trim() || 'Бот',
        usd,
      },
    ],
  };
}

export function removeReserve(container: Container, id: string): Container {
  return { ...container, reserves: container.reserves.filter((r) => r.id !== id) };
}

/** What the named stakes come to. */
export const reservedForBots = (container: Container): number =>
  container.reserves.reduce((sum, r) => sum + r.usd, 0);

export type ContainerSplit = {
  /** The untouchable share, in dollars. */
  core: number;
  /** Set aside for particular strategies. */
  bots: number;
  /** Both together — what no hand-placed order may reach. */
  locked: number;
  /** What is left to trade with. */
  free: number;
};

/**
 * Split a deposit into what is locked and what is not.
 *
 * Measured against the deposit — cash plus what is already in the market —
 * because a share read off cash alone shrinks with every purchase, and a
 * reserve that shrinks as you spend is not a reserve.
 *
 * The named stakes come out first: they are fixed amounts, and a deposit too
 * small to honour them is entirely locked rather than partly.
 */
export function splitFor(container: Container, equity: number): ContainerSplit {
  const total = Number.isFinite(equity) && equity > 0 ? equity : 0;
  const bots = Math.min(reservedForBots(container), total);
  const core = Math.min(
    total * Math.max(0, Math.min(1, container.corePct)),
    total - bots,
  );
  const locked = bots + core;
  return { core, bots, locked, free: Math.max(0, total - locked) };
}
