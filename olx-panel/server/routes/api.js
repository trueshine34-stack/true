/** Definicje endpointow JSON API panelu. */
import { config, hasOlxCredentials } from '../lib/config.js';
import { read, write, update, newId, log, settings as settingsStore } from '../lib/store.js';
import { computePrice, resolveRule, ROUNDING_MODES } from '../../shared/pricing.js';
import { importFromUrl, importFromCsv, importFromJson, normalizeListing } from '../lib/importer.js';
import { prepareListing, publishOne, startQueue, stopQueue, getQueueState, findDuplicates } from '../lib/publisher.js';
import { olx, authorizeUrl, exchangeAuthorizationCode, toAdvertPayload, validatePayload } from '../lib/olx.js';

/** Jawna koperta odpowiedzi, gdy handler musi ustawic kod HTTP inny niz 200. */
export const respond = (status, body) => ({ __response: true, status, body });

export function notFound(res) {
  res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ error: 'Nie znaleziono' }));
}

const listings = () => read('listings');
const byId = (id) => listings().find((l) => l.id === id);

function saveListing(id, patch) {
  update('listings', (all) => all.map((l) => (
    l.id === id ? { ...l, ...patch, id: l.id, updatedAt: new Date().toISOString() } : l
  )));
  return byId(id);
}

function addListings(items) {
  const existing = listings();
  const known = new Set(existing.map((l) => `${l.sourceUrl}|${l.externalId}|${l.title}`));
  const fresh = items.filter((l) => !known.has(`${l.sourceUrl}|${l.externalId}|${l.title}`));
  update('listings', (all) => [...fresh, ...all]);
  return { added: fresh.length, skipped: items.length - fresh.length, items: fresh };
}

export const routes = [
  /* --------------------------------- stan -------------------------------- */
  {
    method: 'GET',
    path: '/api/state',
    handler: () => {
      const all = listings();
      const count = (status) => all.filter((l) => l.status === status).length;
      const totalMargin = all
        .filter((l) => l.status === 'published')
        .reduce((sum, l) => sum + (l.priced?.margin || 0), 0);
      return {
        settings: settingsStore.get(),
        sources: read('sources'),
        queue: getQueueState(),
        olxConfigured: hasOlxCredentials(),
        dryRun: config.publisher.dryRun || !hasOlxCredentials(),
        roundingModes: ROUNDING_MODES,
        stats: {
          total: all.length,
          draft: count('draft'),
          ready: count('ready'),
          queued: count('queued'),
          published: count('published'),
          error: count('error'),
          margin: Math.round(totalMargin * 100) / 100,
        },
      };
    },
  },

  /* ------------------------------ ustawienia ----------------------------- */
  { method: 'GET', path: '/api/settings', handler: () => settingsStore.get() },
  {
    method: 'PUT',
    path: '/api/settings',
    handler: ({ body }) => {
      const saved = settingsStore.patch(body || {});
      log('info', 'Zapisano ustawienia panelu');
      return saved;
    },
  },

  /* ------------------------------- ogloszenia ---------------------------- */
  {
    method: 'GET',
    path: '/api/listings',
    handler: ({ query }) => {
      const status = query.get('status');
      const q = (query.get('q') || '').toLocaleLowerCase('pl-PL');
      const settings = settingsStore.get();
      const items = listings()
        .filter((l) => (!status || status === 'all' ? true : l.status === status))
        .filter((l) => (!q ? true : `${l.title} ${l.externalId} ${l.categoryName}`.toLocaleLowerCase('pl-PL').includes(q)))
        .map((l) => {
          const { priced, formatted } = prepareListing(l, settings);
          return { ...l, priced, formatted };
        });
      return { items, duplicates: findDuplicates(items) };
    },
  },
  {
    method: 'POST',
    path: '/api/listings',
    handler: ({ body }) => {
      const listing = normalizeListing(body || {});
      update('listings', (all) => [listing, ...all]);
      return respond(201, listing);
    },
  },
  {
    method: 'GET',
    path: '/api/listings/:id',
    handler: ({ params }) => {
      const listing = byId(params.id);
      if (!listing) throw Object.assign(new Error('Ogloszenie nie istnieje'), { status: 404 });
      const prepared = prepareListing(listing);
      const payload = toAdvertPayload(prepared, settingsStore.get());
      return { ...prepared, payload, validation: validatePayload(payload) };
    },
  },
  {
    method: 'PATCH',
    path: '/api/listings/:id',
    handler: ({ params, body }) => {
      if (!byId(params.id)) throw Object.assign(new Error('Ogloszenie nie istnieje'), { status: 404 });
      return saveListing(params.id, body || {});
    },
  },
  {
    method: 'DELETE',
    path: '/api/listings/:id',
    handler: ({ params }) => {
      update('listings', (all) => all.filter((l) => l.id !== params.id));
      return { ok: true };
    },
  },
  {
    method: 'POST',
    path: '/api/listings/bulk',
    handler: ({ body }) => {
      const { action, ids = [], patch = {} } = body || {};
      const set = new Set(ids);
      if (action === 'delete') {
        update('listings', (all) => all.filter((l) => !set.has(l.id)));
        return { ok: true, affected: ids.length };
      }
      if (action === 'patch' || action === 'status') {
        update('listings', (all) => all.map((l) => (
          set.has(l.id) ? { ...l, ...patch, updatedAt: new Date().toISOString() } : l
        )));
        return { ok: true, affected: ids.length };
      }
      if (action === 'queue') {
        update('listings', (all) => all.map((l) => (
          set.has(l.id) && l.status !== 'published' ? { ...l, status: 'queued' } : l
        )));
        return { ok: true, affected: ids.length };
      }
      throw new Error(`Nieznana akcja masowa: ${action}`);
    },
  },

  /* --------------------------------- import ------------------------------ */
  {
    method: 'POST',
    path: '/api/import/url',
    handler: async ({ body }) => {
      const urls = (Array.isArray(body?.urls) ? body.urls : String(body?.urls || '').split(/\s+/))
        .map((u) => u.trim())
        .filter((u) => /^https?:\/\//i.test(u))
        .slice(0, 50);
      if (!urls.length) throw new Error('Podaj przynajmniej jeden poprawny adres URL');

      const imported = [];
      const failed = [];
      for (const url of urls) {
        try {
          imported.push(await importFromUrl(url, { sourceId: body?.sourceId || '' }));
        } catch (err) {
          failed.push({ url, error: err.message });
          log('warn', `Import nie powiodl sie: ${url}`, { error: err.message });
        }
      }
      const result = addListings(imported);
      log('info', `Import z URL: dodano ${result.added}, pominieto ${result.skipped}, bledy ${failed.length}`);
      return { ...result, failed };
    },
  },
  {
    method: 'POST',
    path: '/api/import/file',
    handler: ({ body }) => {
      const { format = 'csv', text = '', sourceId = '' } = body || {};
      if (!text.trim()) throw new Error('Puste dane wejsciowe');
      const items = format === 'json'
        ? importFromJson(text, { sourceId })
        : importFromCsv(text, { sourceId });
      const valid = items.filter((l) => l.title);
      const result = addListings(valid);
      log('info', `Import ${format.toUpperCase()}: dodano ${result.added}, pominieto ${result.skipped}`);
      return { ...result, parsed: items.length, invalid: items.length - valid.length };
    },
  },

  /* --------------------------------- ceny -------------------------------- */
  {
    method: 'POST',
    path: '/api/pricing/preview',
    handler: ({ body }) => {
      const settings = settingsStore.get();
      const rule = resolveRule(settings, {
        sourceId: body?.sourceId,
        categoryKey: body?.categoryKey,
        override: body?.rule,
      });
      const samples = (body?.samples || [10, 49, 99, 199, 499, 1299])
        .map((base) => computePrice(base, rule));
      return { rule, samples };
    },
  },
  {
    method: 'POST',
    path: '/api/pricing/apply',
    handler: ({ body }) => {
      const { ids = [], rule = {} } = body || {};
      const set = new Set(ids);
      update('listings', (all) => all.map((l) => (
        set.has(l.id) ? { ...l, priceOverride: rule, manualPrice: null, updatedAt: new Date().toISOString() } : l
      )));
      log('info', `Zastosowano regule marzy do ${ids.length} ogloszen`, rule);
      return { ok: true, affected: ids.length };
    },
  },

  /* ------------------------------ publikacja ----------------------------- */
  {
    method: 'GET',
    path: '/api/listings/:id/preview',
    handler: ({ params }) => {
      const listing = byId(params.id);
      if (!listing) throw Object.assign(new Error('Ogloszenie nie istnieje'), { status: 404 });
      const prepared = prepareListing(listing);
      const payload = toAdvertPayload(prepared, settingsStore.get());
      return { formatted: prepared.formatted, priced: prepared.priced, payload, validation: validatePayload(payload) };
    },
  },
  {
    method: 'POST',
    path: '/api/publish',
    handler: ({ body }) => {
      const ids = Array.isArray(body?.ids) && body.ids.length ? body.ids : null;
      const dryRun = body?.dryRun ?? (config.publisher.dryRun || !hasOlxCredentials());
      return startQueue({ ids, dryRun });
    },
  },
  {
    method: 'POST',
    path: '/api/publish/one',
    handler: async ({ body }) => publishOne(body?.id, { dryRun: body?.dryRun }),
  },
  { method: 'POST', path: '/api/publish/stop', handler: () => stopQueue() },
  { method: 'GET', path: '/api/queue', handler: () => getQueueState() },

  /* -------------------------------- zrodla ------------------------------- */
  { method: 'GET', path: '/api/sources', handler: () => read('sources') },
  {
    method: 'POST',
    path: '/api/sources',
    handler: ({ body }) => {
      const source = {
        id: body?.id || newId('src'),
        name: body?.name || 'Bez nazwy',
        type: body?.type || 'manual',
        url: body?.url || '',
        note: body?.note || '',
        createdAt: new Date().toISOString(),
      };
      update('sources', (all) => [...all.filter((s) => s.id !== source.id), source]);
      return source;
    },
  },
  {
    method: 'DELETE',
    path: '/api/sources/:id',
    handler: ({ params }) => {
      update('sources', (all) => all.filter((s) => s.id !== params.id));
      return { ok: true };
    },
  },

  /* ------------------------------ OLX / OAuth ---------------------------- */
  { method: 'GET', path: '/api/olx/me', handler: () => olx.me() },
  {
    method: 'GET',
    path: '/api/olx/categories',
    handler: ({ query }) => olx.categories(query.get('parent_id') || undefined),
  },
  {
    method: 'GET',
    path: '/api/olx/categories/:id/attributes',
    handler: ({ params }) => olx.categoryAttributes(params.id),
  },
  { method: 'GET', path: '/api/olx/cities', handler: ({ query }) => olx.cities(query.get('q') || '') },
  {
    method: 'GET',
    path: '/api/olx/authorize',
    handler: ({ url }) => ({ url: authorizeUrl(`${url.origin}/oauth/callback`) }),
  },
  {
    method: 'GET',
    path: '/oauth/callback',
    handler: async ({ query, url, res }) => {
      const code = query.get('code');
      if (!code) throw new Error('Brak parametru code w odpowiedzi OLX');
      await exchangeAuthorizationCode(code, `${url.origin}/oauth/callback`);
      log('info', 'Konto OLX polaczone przez OAuth');
      res.writeHead(302, { location: '/' });
      res.end();
      return undefined;
    },
  },

  /* --------------------------------- logi -------------------------------- */
  { method: 'GET', path: '/api/logs', handler: () => read('logs').slice(0, 200) },
  { method: 'DELETE', path: '/api/logs', handler: () => write('logs', []) },
];
