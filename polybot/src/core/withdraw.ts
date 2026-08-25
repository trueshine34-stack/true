import { Preferences } from '@capacitor/preferences';

/**
 * Where money goes when it leaves the exchange.
 *
 * One saved address, because a withdrawal address that has to be typed on a
 * phone every time is a withdrawal address that will eventually be typed wrong,
 * and there is no undo on a chain.
 */
const KEY = 'withdraw.v1';

/** The wallet this app was set up to pay out to. */
export const DEFAULT_WITHDRAW_ADDRESS =
  '0x89C1DFaBfD22c5fF16158eD7d0A23d2cEa0177C3';

const ADDRESS_RE = /^0x[0-9a-fA-F]{40}$/;

export const looksLikeAddress = (value: string): boolean =>
  ADDRESS_RE.test(value.trim());

export async function loadWithdrawAddress(): Promise<string> {
  const { value } = await Preferences.get({ key: KEY });
  return value && looksLikeAddress(value) ? value : DEFAULT_WITHDRAW_ADDRESS;
}

export async function saveWithdrawAddress(address: string): Promise<void> {
  if (!looksLikeAddress(address)) return;
  await Preferences.set({ key: KEY, value: address.trim() });
}

/**
 * How much of the balance a withdrawal may ask for.
 *
 * Gas is paid in POL, not in USDC, so the dollar amount is not what has to be
 * held back — but a transfer that leaves the wallet with no gas leaves it
 * unable to make the next one, which is worth saying out loud rather than
 * discovering later.
 */
export function withdrawable(usdc: number): number {
  if (!Number.isFinite(usdc) || usdc <= 0) return 0;
  // Cents cannot be sent to six decimals without rounding surprises; trim to
  // the cent, downwards.
  return Math.floor(usdc * 100) / 100;
}

/** Short form for a screen: the ends of an address are what people check. */
export const shortAddress = (address: string): string =>
  address.length > 12 ? `${address.slice(0, 6)}…${address.slice(-4)}` : address;
