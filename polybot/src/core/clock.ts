/**
 * Clock alignment with the CLOB.
 *
 * Both auth levels sign a timestamp that the server checks against its own
 * clock, and a phone whose clock has drifted gets every request rejected with
 * an opaque auth error. Signing against a corrected clock removes the whole
 * failure mode.
 */

let offsetSec = 0;
let synced = false;

export function nowSec(): number {
  return Math.floor(Date.now() / 1000) + offsetSec;
}

export function clockOffsetSec(): number {
  return offsetSec;
}

export function isClockSynced(): boolean {
  return synced;
}

/** @param fetchServerTime returns the server's clock in seconds */
export async function syncClock(
  fetchServerTime: () => Promise<number>,
): Promise<number> {
  const server = await fetchServerTime();
  if (!Number.isFinite(server) || server <= 0) {
    throw new Error('сервер вернул некорректное время');
  }
  offsetSec = server - Math.floor(Date.now() / 1000);
  synced = true;
  return offsetSec;
}
