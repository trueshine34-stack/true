/**
 * Kolejka publikacji na OLX: przygotowanie -> walidacja -> wysylka -> aktywacja.
 * Publikuje pojedynczo z odstepem, zeby nie wpasc w limity API.
 */
import crypto from 'node:crypto';
import { config, hasOlxCredentials } from './config.js';
import { read, update, log, settings as settingsStore } from './store.js';
import { priceListing } from './pricing.js';
import { formatListing } from './formatter.js';
import { olx, toAdvertPayload, validatePayload } from './olx.js';

/** Dodaje wyliczona cene i sformatowana tresc. Nie zapisuje niczego. */
export function prepareListing(listing, settings = settingsStore.get()) {
  const priced = priceListing(listing, settings);
  const formatted = formatListing(listing, settings.template, priced);
  return { ...listing, priced, formatted };
}

export function fingerprint(listing) {
  const basis = [listing.sourceUrl, listing.externalId, listing.title].filter(Boolean).join('|');
  return crypto.createHash('sha1').update(basis).digest('hex').slice(0, 16);
}

export function findDuplicates(listings) {
  const seen = new Map();
  const duplicates = [];
  for (const listing of listings) {
    const fp = fingerprint(listing);
    if (seen.has(fp)) duplicates.push({ id: listing.id, duplicateOf: seen.get(fp) });
    else seen.set(fp, listing.id);
  }
  return duplicates;
}

function patchListing(id, patch) {
  update('listings', (listings) => listings.map((l) => (
    l.id === id ? { ...l, ...patch, updatedAt: new Date().toISOString() } : l
  )));
  return read('listings').find((l) => l.id === id);
}

/** Publikuje jedno ogloszenie. Zwraca zaktualizowany rekord. */
export async function publishOne(id, { dryRun = config.publisher.dryRun } = {}) {
  const stored = read('listings').find((l) => l.id === id);
  if (!stored) throw new Error(`Ogloszenie ${id} nie istnieje`);

  const settings = settingsStore.get();
  const prepared = prepareListing(stored, settings);
  const payload = toAdvertPayload(prepared, settings);
  const errors = validatePayload(payload);

  if (errors.length) {
    log('error', `Walidacja nie przeszla: ${stored.title}`, { id, errors });
    return patchListing(id, { status: 'error', error: errors.join('; '), priced: prepared.priced, formatted: prepared.formatted });
  }

  if (dryRun || !hasOlxCredentials()) {
    log('info', `DRY-RUN: ogloszenie gotowe do wysylki - ${payload.title}`, { id, price: payload.price.value });
    return patchListing(id, {
      status: 'ready',
      error: '',
      priced: prepared.priced,
      formatted: prepared.formatted,
      payloadPreview: payload,
    });
  }

  try {
    const created = stored.olx?.id
      ? await olx.updateAdvert(stored.olx.id, payload)
      : await olx.createAdvert(payload);
    const advertId = created?.id || stored.olx?.id;

    let activation = null;
    try {
      activation = await olx.activate(advertId);
    } catch (err) {
      log('warn', `Ogloszenie utworzone, ale aktywacja nie przeszla (${advertId})`, { error: err.message });
    }

    log('info', `Opublikowano: ${payload.title}`, { id, advertId });
    return patchListing(id, {
      status: 'published',
      error: '',
      priced: prepared.priced,
      formatted: prepared.formatted,
      publishedAt: new Date().toISOString(),
      olx: {
        id: advertId,
        url: created?.url || `https://www.olx.pl/d/oferta/${advertId}`,
        status: activation?.status || created?.status || 'new',
      },
    });
  } catch (err) {
    log('error', `Publikacja nie powiodla sie: ${payload.title}`, {
      id, error: err.message, validation: err.validation,
    });
    return patchListing(id, {
      status: 'error',
      error: err.message,
      validationErrors: err.validation || null,
      priced: prepared.priced,
      formatted: prepared.formatted,
    });
  }
}

/* ------------------------------- kolejka ------------------------------- */

const queueState = { running: false, stopRequested: false, current: null, done: 0, total: 0 };

export function getQueueState() {
  return { ...queueState, intervalMs: config.publisher.intervalMs };
}

export function stopQueue() {
  queueState.stopRequested = true;
  return getQueueState();
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Uruchamia kolejke w tle. Nie czeka na zakonczenie. */
export function startQueue({ ids = null, dryRun = config.publisher.dryRun } = {}) {
  if (queueState.running) return getQueueState();

  const pending = read('listings')
    .filter((l) => (ids ? ids.includes(l.id) : ['queued', 'draft', 'ready'].includes(l.status)))
    .filter((l) => l.status !== 'published')
    .slice(0, config.publisher.maxPerRun);

  Object.assign(queueState, {
    running: true, stopRequested: false, done: 0, total: pending.length, current: null,
  });

  (async () => {
    log('info', `Start kolejki: ${pending.length} ogloszen (dryRun=${dryRun})`);
    for (const listing of pending) {
      if (queueState.stopRequested) { log('warn', 'Kolejka zatrzymana recznie'); break; }
      queueState.current = listing.id;
      try {
        await publishOne(listing.id, { dryRun });
      } catch (err) {
        log('error', `Blad kolejki dla ${listing.id}`, { error: err.message });
      }
      queueState.done += 1;
      if (!queueState.stopRequested && queueState.done < pending.length) {
        await sleep(config.publisher.intervalMs);
      }
    }
    Object.assign(queueState, { running: false, current: null });
    log('info', `Kolejka zakonczona: ${queueState.done}/${queueState.total}`);
  })();

  return getQueueState();
}
