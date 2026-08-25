import { Preferences } from '@capacitor/preferences';
import { DEFAULT_SETTINGS, type StrategySettings } from './settings';
import { DEFAULT_PAIR_SETTINGS } from './pair';
import { DEFAULT_MANUAL_SETTINGS, type ManualSettings } from './manual';
import type { PairSettings } from '../native/polybot';
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
const KEY_SETTINGS = 'settings.v1';
const KEY_PAIR = 'pair.v1';
const KEY_MANUAL = 'manual.v1';

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

export async function saveSettings(settings: StrategySettings): Promise<void> {
  await Preferences.set({ key: KEY_SETTINGS, value: JSON.stringify(settings) });
}

export async function loadSettings(): Promise<StrategySettings> {
  const { value } = await Preferences.get({ key: KEY_SETTINGS });
  if (!value) return { ...DEFAULT_SETTINGS };
  try {
    // Merge so a settings file written by an older build still loads.
    return { ...DEFAULT_SETTINGS, ...(JSON.parse(value) as StrategySettings) };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export async function savePairSettings(settings: PairSettings): Promise<void> {
  await Preferences.set({ key: KEY_PAIR, value: JSON.stringify(settings) });
}

export async function loadPairSettings(): Promise<PairSettings> {
  const { value } = await Preferences.get({ key: KEY_PAIR });
  if (!value) return { ...DEFAULT_PAIR_SETTINGS };
  try {
    return { ...DEFAULT_PAIR_SETTINGS, ...(JSON.parse(value) as PairSettings) };
  } catch {
    return { ...DEFAULT_PAIR_SETTINGS };
  }
}

export async function saveManualSettings(settings: ManualSettings): Promise<void> {
  await Preferences.set({ key: KEY_MANUAL, value: JSON.stringify(settings) });
}

export async function loadManualSettings(): Promise<ManualSettings> {
  const { value } = await Preferences.get({ key: KEY_MANUAL });
  if (!value) return { ...DEFAULT_MANUAL_SETTINGS };
  try {
    return {
      ...DEFAULT_MANUAL_SETTINGS,
      ...(JSON.parse(value) as ManualSettings),
    };
  } catch {
    return { ...DEFAULT_MANUAL_SETTINGS };
  }
}
