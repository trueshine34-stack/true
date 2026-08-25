import { Preferences } from '@capacitor/preferences';
import { DEFAULT_SETTINGS, type StrategySettings } from './settings';
import { DEFAULT_PAIR_SETTINGS } from './pair';
import { DEFAULT_MANUAL_SETTINGS, type ManualSettings } from './manual';
import type { PairSettings } from '../native/polybot';
import type { AccountConfig } from './account';

/**
 * Persistence.
 *
 * Android's SharedPreferences — what Capacitor Preferences writes to — is not
 * encrypted, so the private key never goes in as plaintext. It is sealed with
 * AES-GCM under a key derived from the user's PIN, and the PIN is never
 * stored. Losing the PIN means re-importing the key, which is the intended
 * trade-off.
 */

const KEY_VAULT = 'vault.v1';
const KEY_ACCOUNT = 'account.v1';
const KEY_SETTINGS = 'settings.v1';
const KEY_PAIR = 'pair.v1';
const KEY_MANUAL = 'manual.v1';

const PBKDF2_ITERATIONS = 210_000;

type Vault = {
  salt: string;
  iv: string;
  ciphertext: string;
};

const toB64 = (b: Uint8Array) => {
  let s = '';
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
  return btoa(s);
};

const fromB64 = (s: string) => {
  const raw = atob(s);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
};

async function deriveKey(pin: string, salt: Uint8Array): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(pin) as unknown as ArrayBuffer,
    'PBKDF2',
    false,
    ['deriveKey'],
  );
  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: salt as unknown as ArrayBuffer,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

export async function saveEncryptedKey(
  privateKey: string,
  pin: string,
): Promise<void> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(pin, salt);
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as unknown as ArrayBuffer },
    key,
    new TextEncoder().encode(privateKey) as unknown as ArrayBuffer,
  );

  const vault: Vault = {
    salt: toB64(salt),
    iv: toB64(iv),
    ciphertext: toB64(new Uint8Array(ciphertext)),
  };
  await Preferences.set({ key: KEY_VAULT, value: JSON.stringify(vault) });
}

export async function hasVault(): Promise<boolean> {
  const { value } = await Preferences.get({ key: KEY_VAULT });
  return Boolean(value);
}

/** Returns null when the PIN is wrong — AES-GCM authentication catches it. */
export async function loadEncryptedKey(pin: string): Promise<string | null> {
  const { value } = await Preferences.get({ key: KEY_VAULT });
  if (!value) return null;

  const vault = JSON.parse(value) as Vault;
  const key = await deriveKey(pin, fromB64(vault.salt));
  try {
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromB64(vault.iv) as unknown as ArrayBuffer },
      key,
      fromB64(vault.ciphertext) as unknown as ArrayBuffer,
    );
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
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
