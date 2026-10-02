import { Preferences } from '@capacitor/preferences';
import {
  DEFAULT_MANUAL_SETTINGS,
  stretchLadder,
  type ManualSettings,
} from './manual';
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
const KEY_CHIMETEST = 'chimetest.v1';

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

/**
 * Whether the sound-test buttons are still on the settings screen.
 *
 * They exist to answer one question — does this phone make the sound — and
 * once it is answered they are three buttons in the way of the ones that are
 * used. So they can be taken off, and the answer is remembered rather than
 * asked for again on every launch.
 */
export async function loadChimeTest(): Promise<boolean> {
  const { value } = await Preferences.get({ key: KEY_CHIMETEST });
  return value !== 'off';
}

export async function saveChimeTest(shown: boolean): Promise<void> {
  await Preferences.set({ key: KEY_CHIMETEST, value: shown ? 'on' : 'off' });
}

export async function clearVault(slot = 0): Promise<void> {
  await Preferences.remove({ key: KEY_VAULT });
  await Preferences.remove({ key: accountKey(slot) });
}

/**
 * Where one wallet's terms are kept.
 *
 * The first slot keeps the key it has always had, so an upgrade finds its
 * account where it left it and a second wallet is new ground rather than a
 * migration. The native side does the same with its own storage.
 */
const accountKey = (slot: number): string =>
  slot <= 0 ? KEY_ACCOUNT : `${KEY_ACCOUNT}.w${slot}`;

export async function saveAccount(
  account: AccountConfig,
  slot = 0,
): Promise<void> {
  await Preferences.set({
    key: accountKey(slot),
    value: JSON.stringify(account),
  });
}

export async function loadAccount(slot = 0): Promise<AccountConfig | null> {
  const { value } = await Preferences.get({ key: accountKey(slot) });
  if (!value) return null;
  try {
    return JSON.parse(value) as AccountConfig;
  } catch {
    return null;
  }
}



/**
 * Where one wallet's desk settings live.
 *
 * Which exit chip is armed, what the third one's percentage is, how long each
 * watches, how a buy goes out — these are how an account is being traded, and
 * two accounts are usually being traded differently or there would be no reason
 * to have two. So each slot keeps its own, and the first keeps the key it has
 * always had.
 */
const manualKey = (slot: number): string =>
  slot <= 0 ? KEY_MANUAL : `${KEY_MANUAL}.w${slot}`;

export async function saveManualSettings(
  settings: ManualSettings,
  slot = 0,
): Promise<void> {
  await Preferences.set({
    key: manualKey(slot),
    value: JSON.stringify(settings),
  });
}

/** How long a rung used to hold, back when there were five of them. */
const OLD_STEP_SEC = 60;

export async function loadManualSettings(slot = 0): Promise<ManualSettings> {
  const { value } = await Preferences.get({ key: manualKey(slot) });
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
      // The rung used to hold for a minute, and a stored sixty is that old
      // default rather than a choice — nothing else was ever offered.
      autoSellStepSec:
        (stored.autoSellStepSec ?? OLD_STEP_SEC) === OLD_STEP_SEC
          ? DEFAULT_MANUAL_SETTINGS.autoSellStepSec
          : stored.autoSellStepSec,
      // And half-minute rungs need twice as many of them to reach the close.
      // A ladder the user has edited is theirs, so it is resampled rather than
      // replaced: the same curve read at twice as many points.
      autoSellLadder: stretchLadder(
        stored.autoSellLadder ?? DEFAULT_MANUAL_SETTINGS.autoSellLadder,
        DEFAULT_MANUAL_SETTINGS.autoSellLadder.length,
      ),
    };
  } catch {
    return { ...DEFAULT_MANUAL_SETTINGS };
  }
}
