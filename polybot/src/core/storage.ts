import { Preferences } from '@capacitor/preferences';
import { DEFAULT_MANUAL_SETTINGS, type ManualSettings } from './manual';
import type { AccountConfig } from './account';

/**
 * Persistence for everything that is not the signing key.
 *
 * Android's SharedPreferences — what Capacitor Preferences writes to — is not
 * encrypted, so the key never lived here in the clear and does not live here at
 * all any more: it is sealed by the Android Keystore on the native side (see
 * KeyVault.kt). `clearVault` still wipes the old PIN-sealed blob so an install
 * upgraded from that scheme does not leave ciphertext lying around.
 */

const KEY_VAULT = 'vault.v1';
const KEY_ACCOUNT = 'account.v1';
const KEY_MANUAL = 'manual.v1';
const KEY_SAVINGS = 'savings.v1';

/**
 * Where profit is withdrawn to, so the balance can still see it.
 *
 * Money moved off the venue is still the run's money; a line that drops by
 * what was taken out reads a good week as a bad one. Read-only — the address
 * is only ever asked about, never sent to.
 */
export async function saveSavingsAddress(address: string): Promise<void> {
  await Preferences.set({ key: KEY_SAVINGS, value: address.trim() });
}

/**
 * The address this app was set up for, until it is told another.
 *
 * Only used when nothing has been stored at all: an address cleared on purpose
 * is stored as empty and stays that way, so emptying the field switches the
 * whole thing off rather than resetting it to this.
 */
const DEFAULT_SAVINGS = '0x89C1DFaBfD22c5fF16158eD7d0A23d2cEa0177C3';

export async function loadSavingsAddress(): Promise<string> {
  const { value } = await Preferences.get({ key: KEY_SAVINGS });
  if (value == null) return DEFAULT_SAVINGS;
  return value.trim();
}

export async function clearVault(): Promise<void> {
  await Preferences.remove({ key: KEY_VAULT });
  await Preferences.remove({ key: KEY_ACCOUNT });
}

export async function saveAccount(account: AccountConfig): Promise<void> {
  await Preferences.set({ key: KEY_ACCOUNT, value: JSON.stringify(account) });
}

export async function loadAccount(): Promise<AccountConfig | null> {
  const { value } = await Preferences.get({ key: KEY_ACCOUNT });
  if (!value) return null;
  try {
    return JSON.parse(value) as AccountConfig;
  } catch {
    return null;
  }
}



export async function saveManualSettings(settings: ManualSettings): Promise<void> {
  await Preferences.set({ key: KEY_MANUAL, value: JSON.stringify(settings) });
}

export async function loadManualSettings(): Promise<ManualSettings> {
  const { value } = await Preferences.get({ key: KEY_MANUAL });
  if (!value) return { ...DEFAULT_MANUAL_SETTINGS };
  try {
    const stored = JSON.parse(value) as ManualSettings;
    return {
      ...DEFAULT_MANUAL_SETTINGS,
      ...stored,
      // The retry used to be a field the user set, and seven was its default.
      // It is a fixed three seconds now, so a stored seven is read as "never
      // chosen" — otherwise the change would never reach anyone already running.
      autoSellRetrySec:
        stored.autoSellRetrySec === 7
          ? DEFAULT_MANUAL_SETTINGS.autoSellRetrySec
          : (stored.autoSellRetrySec ?? DEFAULT_MANUAL_SETTINGS.autoSellRetrySec),
    };
  } catch {
    return { ...DEFAULT_MANUAL_SETTINGS };
  }
}
