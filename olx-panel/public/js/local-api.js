/**
 * Автономный движок панели: тот же JSON API, что у сервера, но целиком
 * внутри устройства. Логика цен, оформления и разбора берётся из shared/,
 * хранение — в файлах приложения (или в localStorage в браузере),
 * сеть — через нативный мост Android, чтобы не упираться в CORS.
 */
import { computePrice, resolveRule, priceListing, ROUNDING_MODES } from '../shared/pricing.js';
import { formatListing } from '../shared/formatter.js';
import {
  normalizeListing, importFromUrl, importFromCsv, importFromJson,
} from '../shared/importer.js';
import { toAdvertPayload, validatePayload } from '../shared/olx-payload.js';
import { createOlxClient } from '../shared/olx-client.js';

const native = () => (typeof window !== 'undefined' ? window.OlxNative : undefined);

/* ------------------------------ хранилище ------------------------------ */

const DEFAULTS = {
  settings: {
    pricing: {
      percent: 25, fixed: 0, minMargin: 15, maxMargin: 0, shipping: 0, feePercent: 0,
      rounding: 'end99', currency: 'PLN', negotiable: true,
    },
    categoryRules: {},
    sourceRules: {},
    template: {
      title: '{{title}}',
      body: [
        '{{description}}', '', '——————————————',
        '✅ Stan: {{condition}}',
        '📦 Wysyłka: {{shipping}}',
        '💳 Płatność: {{payment}}',
        '🚚 Czas realizacji: {{delivery}}',
        '', '{{highlights}}', '', 'ℹ️ {{footer}}',
      ].join('\n'),
      vars: {
        condition: 'Nowy',
        shipping: 'Kurier / Paczkomat InPost — wysyłka w 24h',
        payment: 'Przelew, BLIK, płatność przy odbiorze',
        delivery: '1–3 dni robocze',
        footer: 'Faktura na życzenie. Zapraszam do zakupu i na pozostałe moje ogłoszenia.',
      },
      maxTitleLength: 70,
      maxBodyLength: 9000,
      titleCase: 'smart',
      sanitize: true,
      hashtags: true,
    },
    contact: { name: '', phone: '' },
    location: { cityId: null, cityName: '', districtId: null, lat: null, lon: null },
    advertiserType: 'private',
    // На телефоне нет .env, поэтому ключи и режим публикации живут в настройках.
    olx: { clientId: '', clientSecret: '', redirectUri: 'https://localhost/oauth', scope: 'v2 read write' },
    publish: { intervalMs: 45000, maxPerRun: 20, dryRun: false },
  },
  sources: [],
  listings: [],
  logs: [],
  tokens: {},
};

function deepMerge(base, patch) {
  if (Array.isArray(base) || Array.isArray(patch)) return patch === undefined ? base : patch;
  if (typeof base !== 'object' || base === null) return patch === undefined ? base : patch;
  if (typeof patch !== 'object' || patch === null) return patch === undefined ? base : patch;
  const out = { ...base };
  for (const [key, value] of Object.entries(patch)) out[key] = deepMerge(base[key], value);
  return out;
}

const cache = new Map();

function readRaw(name) {
  const bridge = native();
  try {
    if (bridge?.readFile) return bridge.readFile(`${name}.json`) || '';
    return localStorage.getItem(`olx.${name}`) || '';
  } catch {
    return '';
  }
}

function writeRaw(name, text) {
  const bridge = native();
  try {
    if (bridge?.writeFile) bridge.writeFile(`${name}.json`, text);
    else localStorage.setItem(`olx.${name}`, text);
  } catch (err) {
    console.error('Не удалось сохранить данные', name, err);
  }
}

function read(name) {
  if (cache.has(name)) return cache.get(name);
  let value = DEFAULTS[name];
  const raw = readRaw(name);
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      value = Array.isArray(DEFAULTS[name]) ? parsed : deepMerge(DEFAULTS[name], parsed);
    } catch {
      /* повреждённый файл — начинаем с умолчаний, данные не теряем молча */
      log('warn', `Файл ${name}.json повреждён, взяты значения по умолчанию`);
    }
  }
  cache.set(name, value);
  return value;
}

function write(name, value) {
  cache.set(name, value);
  writeRaw(name, JSON.stringify(value, null, 2));
  return value;
}

const update = (name, mutator) => write(name, mutator(read(name)));

function randomId(prefix = 'l') {
  const bytes = new Uint8Array(6);
  (globalThis.crypto || {}).getRandomValues?.(bytes);
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${prefix}_${hex || Math.random().toString(16).slice(2, 14)}`;
}

function log(level, message, meta = {}) {
  const entry = { id: randomId('log'), at: new Date().toISOString(), level, message, meta };
  update('logs', (logs) => [entry, ...logs].slice(0, 500));
  return entry;
}

/* --------------------------------- сеть -------------------------------- */

const pendingHttp = new Map();

if (typeof window !== 'undefined') {
  window.__olxHttpResolve = (id, resultJson) => {
    const pending = pendingHttp.get(id);
    if (!pending) return;
    pendingHttp.delete(id);
    try {
      const result = JSON.parse(resultJson);
      if (result.error) pending.reject(new Error(result.error));
      else pending.resolve({ status: result.status, text: result.text || '' });
    } catch (err) {
      pending.reject(err);
    }
  };
}

/** Запрос через нативный мост, а в обычном браузере — обычным fetch. */
function http(url, init = {}) {
  const bridge = native();
  if (bridge?.httpRequest) {
    const id = randomId('req');
    return new Promise((resolve, reject) => {
      pendingHttp.set(id, { resolve, reject });
      setTimeout(() => {
        if (pendingHttp.delete(id)) reject(new Error(`Таймаут запроса к ${url}`));
      }, 45000);
      bridge.httpRequest(id, JSON.stringify({
        url,
        method: init.method || 'GET',
        headers: init.headers || {},
        body: init.body || null,
      }));
    });
  }
  return fetch(url, init).then(async (res) => ({ status: res.status, text: await res.text() }));
}

const fetchText = async (url) => {
  const res = await http(url, {
    headers: {
      accept: 'text/html,application/xhtml+xml',
      'accept-language': 'pl-PL,pl;q=0.9,en;q=0.8',
    },
  });
  if (res.status < 200 || res.status >= 300) throw new Error(`HTTP ${res.status} для ${url}`);
  return res.text;
};

/* ------------------------------- клиент OLX ---------------------------- */

const olx = createOlxClient({
  request: http,
  tokens: { read: () => read('tokens'), write: (value) => write('tokens', value) },
  credentials: () => read('settings').olx,
  log,
});

/* ------------------------------ подготовка ----------------------------- */

const settingsOf = () => read('settings');
const listings = () => read('listings');
const byId = (id) => listings().find((l) => l.id === id);

function prepareListing(listing, settings = settingsOf()) {
  const priced = priceListing(listing, settings);
  const formatted = formatListing(listing, settings.template, priced);
  return { ...listing, priced, formatted };
}

function patchListing(id, patch) {
  update('listings', (all) => all.map((l) => (
    l.id === id ? { ...l, ...patch, id: l.id, updatedAt: new Date().toISOString() } : l
  )));
  return byId(id);
}

function fingerprint(listing) {
  return [listing.sourceUrl, listing.externalId, listing.title].filter(Boolean).join('|');
}

function findDuplicates(items) {
  const seen = new Map();
  const duplicates = [];
  for (const listing of items) {
    const fp = fingerprint(listing);
    if (seen.has(fp)) duplicates.push({ id: listing.id, duplicateOf: seen.get(fp) });
    else seen.set(fp, listing.id);
  }
  return duplicates;
}

function addListings(items) {
  const known = new Set(listings().map(fingerprint));
  const fresh = items.filter((l) => !known.has(fingerprint(l)));
  update('listings', (all) => [...fresh, ...all]);
  return { added: fresh.length, skipped: items.length - fresh.length, items: fresh };
}

/* ------------------------------ публикация ----------------------------- */

const queueState = { running: false, stopRequested: false, current: null, done: 0, total: 0, intervalMs: 45000 };
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function publishOne(id, { dryRun } = {}) {
  const stored = byId(id);
  if (!stored) throw new Error(`Объявление ${id} не найдено`);

  const settings = settingsOf();
  const isDry = dryRun ?? (settings.publish.dryRun || !olx.hasCredentials());
  const prepared = prepareListing(stored, settings);
  const payload = toAdvertPayload(prepared, settings);
  const errors = validatePayload(payload);

  if (errors.length) {
    log('error', `Проверка не пройдена: ${stored.title}`, { id, errors });
    return patchListing(id, {
      status: 'error', error: errors.join('; '), priced: prepared.priced, formatted: prepared.formatted,
    });
  }

  if (isDry) {
    log('info', `Тест: объявление готово к отправке — ${payload.title}`, { id, price: payload.price.value });
    return patchListing(id, {
      status: 'ready', error: '', priced: prepared.priced, formatted: prepared.formatted, payloadPreview: payload,
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
      log('warn', `Объявление создано, но активация не прошла (${advertId})`, { error: err.message });
    }

    log('info', `Опубликовано: ${payload.title}`, { id, advertId });
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
    log('error', `Публикация не удалась: ${payload.title}`, { id, error: err.message });
    return patchListing(id, {
      status: 'error',
      error: err.message,
      validationErrors: err.validation || null,
      priced: prepared.priced,
      formatted: prepared.formatted,
    });
  }
}

function startQueue({ ids = null, dryRun } = {}) {
  if (queueState.running) return { ...queueState };
  const settings = settingsOf();
  const pending = listings()
    .filter((l) => (ids ? ids.includes(l.id) : ['queued', 'draft', 'ready'].includes(l.status)))
    .filter((l) => l.status !== 'published')
    .slice(0, settings.publish.maxPerRun || 20);

  Object.assign(queueState, {
    running: true,
    stopRequested: false,
    done: 0,
    total: pending.length,
    current: null,
    intervalMs: settings.publish.intervalMs || 45000,
  });

  (async () => {
    log('info', `Старт очереди: ${pending.length} объявлений`);
    native()?.keepAwake?.(true);
    for (const listing of pending) {
      if (queueState.stopRequested) { log('warn', 'Очередь остановлена вручную'); break; }
      queueState.current = listing.id;
      try {
        await publishOne(listing.id, { dryRun });
      } catch (err) {
        log('error', `Сбой очереди для ${listing.id}`, { error: err.message });
      }
      queueState.done += 1;
      if (!queueState.stopRequested && queueState.done < pending.length) {
        await sleep(queueState.intervalMs);
      }
    }
    Object.assign(queueState, { running: false, current: null });
    native()?.keepAwake?.(false);
    log('info', `Очередь завершена: ${queueState.done} из ${queueState.total}`);
  })();

  return { ...queueState };
}

/* -------------------------------- роутер ------------------------------- */

const routes = [];
const route = (method, path, handler) => routes.push({ method, path, handler });

function match(pattern, pathname) {
  const p = pattern.split('/');
  const s = pathname.split('/');
  if (p.length !== s.length) return null;
  const params = {};
  for (let i = 0; i < p.length; i += 1) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = decodeURIComponent(s[i]);
    else if (p[i] !== s[i]) return null;
  }
  return params;
}

route('GET', '/api/state', () => {
  const all = listings();
  const count = (status) => all.filter((l) => l.status === status).length;
  const margin = all.filter((l) => l.status === 'published')
    .reduce((sum, l) => sum + (l.priced?.margin || 0), 0);
  const settings = settingsOf();
  const tokens = read('tokens') || {};
  return {
    settings,
    sources: read('sources'),
    queue: { ...queueState },
    olxConfigured: olx.hasCredentials(),
    // Публикация идёт только по authorization_code, поэтому одних ключей мало —
    // нужен ещё токен, полученный после подтверждения доступа в OLX.
    olxConnected: Boolean(tokens.refreshToken || tokens.accessToken),
    dryRun: settings.publish.dryRun || !olx.hasCredentials(),
    standalone: true,
    hasNative: Boolean(native()),
    roundingModes: ROUNDING_MODES,
    stats: {
      total: all.length,
      draft: count('draft'),
      ready: count('ready'),
      queued: count('queued'),
      published: count('published'),
      error: count('error'),
      margin: Math.round(margin * 100) / 100,
    },
  };
});

route('GET', '/api/settings', () => settingsOf());
route('PUT', '/api/settings', ({ body }) => {
  const saved = write('settings', deepMerge(settingsOf(), body || {}));
  log('info', 'Настройки сохранены');
  return saved;
});

route('GET', '/api/listings', ({ query }) => {
  const status = query.get('status');
  const q = (query.get('q') || '').toLocaleLowerCase('pl-PL');
  const settings = settingsOf();
  const items = listings()
    .filter((l) => (!status || status === 'all' ? true : l.status === status))
    .filter((l) => (!q ? true
      : `${l.title} ${l.externalId} ${l.categoryName}`.toLocaleLowerCase('pl-PL').includes(q)))
    .map((l) => {
      const { priced, formatted } = prepareListing(l, settings);
      return { ...l, priced, formatted };
    });
  return { items, duplicates: findDuplicates(items) };
});

route('POST', '/api/listings', ({ body }) => {
  const listing = normalizeListing(body || {});
  update('listings', (all) => [listing, ...all]);
  return listing;
});

route('GET', '/api/listings/:id', ({ params }) => {
  const listing = byId(params.id);
  if (!listing) throw new Error('Объявление не найдено');
  const prepared = prepareListing(listing);
  const payload = toAdvertPayload(prepared, settingsOf());
  return { ...prepared, payload, validation: validatePayload(payload) };
});

route('PATCH', '/api/listings/:id', ({ params, body }) => {
  if (!byId(params.id)) throw new Error('Объявление не найдено');
  return patchListing(params.id, body || {});
});

route('DELETE', '/api/listings/:id', ({ params }) => {
  update('listings', (all) => all.filter((l) => l.id !== params.id));
  return { ok: true };
});

route('POST', '/api/listings/bulk', ({ body }) => {
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
  throw new Error(`Неизвестное массовое действие: ${action}`);
});

route('GET', '/api/listings/:id/preview', ({ params }) => {
  const listing = byId(params.id);
  if (!listing) throw new Error('Объявление не найдено');
  const prepared = prepareListing(listing);
  const payload = toAdvertPayload(prepared, settingsOf());
  return {
    formatted: prepared.formatted, priced: prepared.priced, payload, validation: validatePayload(payload),
  };
});

route('POST', '/api/import/url', async ({ body }) => {
  const urls = (Array.isArray(body?.urls) ? body.urls : String(body?.urls || '').split(/\s+/))
    .map((u) => u.trim())
    .filter((u) => /^https?:\/\//i.test(u))
    .slice(0, 50);
  if (!urls.length) throw new Error('Укажите хотя бы одну корректную ссылку');

  const imported = [];
  const failed = [];
  for (const url of urls) {
    try {
      imported.push(await importFromUrl(url, fetchText, { sourceId: body?.sourceId || '' }));
    } catch (err) {
      failed.push({ url, error: err.message });
      log('warn', `Импорт не удался: ${url}`, { error: err.message });
    }
  }
  const result = addListings(imported);
  log('info', `Импорт по ссылкам: добавлено ${result.added}, пропущено ${result.skipped}, ошибок ${failed.length}`);
  return { ...result, failed };
});

route('POST', '/api/import/file', ({ body }) => {
  const { format = 'csv', text = '', sourceId = '' } = body || {};
  if (!text.trim()) throw new Error('Пустые данные');
  const items = format === 'json' ? importFromJson(text, { sourceId }) : importFromCsv(text, { sourceId });
  const valid = items.filter((l) => l.title);
  const result = addListings(valid);
  log('info', `Импорт ${format.toUpperCase()}: добавлено ${result.added}, пропущено ${result.skipped}`);
  return { ...result, parsed: items.length, invalid: items.length - valid.length };
});

route('POST', '/api/pricing/preview', ({ body }) => {
  const rule = resolveRule(settingsOf(), {
    sourceId: body?.sourceId, categoryKey: body?.categoryKey, override: body?.rule,
  });
  const samples = (body?.samples || [10, 49, 99, 199, 499, 1299]).map((base) => computePrice(base, rule));
  return { rule, samples };
});

route('POST', '/api/pricing/apply', ({ body }) => {
  const { ids = [], rule = {} } = body || {};
  const set = new Set(ids);
  update('listings', (all) => all.map((l) => (
    set.has(l.id) ? { ...l, priceOverride: rule, manualPrice: null, updatedAt: new Date().toISOString() } : l
  )));
  log('info', `Правило наценки применено к ${ids.length} объявлениям`);
  return { ok: true, affected: ids.length };
});

route('POST', '/api/publish', ({ body }) => startQueue({
  ids: Array.isArray(body?.ids) && body.ids.length ? body.ids : null,
  dryRun: body?.dryRun,
}));
route('POST', '/api/publish/one', ({ body }) => publishOne(body?.id, { dryRun: body?.dryRun }));
route('POST', '/api/publish/stop', () => {
  queueState.stopRequested = true;
  return { ...queueState };
});
route('GET', '/api/queue', () => ({ ...queueState }));

route('GET', '/api/sources', () => read('sources'));
route('POST', '/api/sources', ({ body }) => {
  const source = {
    id: body?.id || randomId('src'),
    name: body?.name || 'Без названия',
    type: body?.type || 'manual',
    url: body?.url || '',
    note: body?.note || '',
    createdAt: new Date().toISOString(),
  };
  update('sources', (all) => [...all.filter((s) => s.id !== source.id), source]);
  return source;
});
route('DELETE', '/api/sources/:id', ({ params }) => {
  update('sources', (all) => all.filter((s) => s.id !== params.id));
  return { ok: true };
});

route('GET', '/api/olx/me', () => olx.me());
route('GET', '/api/olx/categories', ({ query }) => olx.categories(query.get('parent_id') || undefined));
route('GET', '/api/olx/categories/:id/attributes', ({ params }) => olx.categoryAttributes(params.id));
route('GET', '/api/olx/cities', ({ query }) => olx.cities(query.get('q') || ''));
route('GET', '/api/olx/authorize', () => {
  const { redirectUri } = settingsOf().olx;
  return { url: olx.authorizeUrl(redirectUri), redirectUri };
});
route('POST', '/api/olx/oauth/exchange', async ({ body }) => {
  const { redirectUri } = settingsOf().olx;
  await olx.exchangeAuthorizationCode(body?.code, body?.redirectUri || redirectUri);
  log('info', 'Аккаунт OLX подключён');
  return { ok: true };
});

route('GET', '/api/logs', () => read('logs').slice(0, 200));
route('DELETE', '/api/logs', () => write('logs', []));

/* Резервная копия: единственный способ не потерять данные без компьютера. */
route('GET', '/api/backup', () => ({
  exportedAt: new Date().toISOString(),
  settings: settingsOf(),
  sources: read('sources'),
  listings: listings(),
}));
route('POST', '/api/restore', ({ body }) => {
  if (!body || typeof body !== 'object') throw new Error('Некорректный файл резервной копии');
  if (body.settings) write('settings', deepMerge(DEFAULTS.settings, body.settings));
  if (Array.isArray(body.sources)) write('sources', body.sources);
  if (Array.isArray(body.listings)) write('listings', body.listings.map((l) => normalizeListing(l)));
  log('info', `Данные восстановлены: объявлений ${read('listings').length}`);
  return { ok: true, listings: read('listings').length };
});

/** Тот же интерфейс, что у серверного api() в app.js. */
export async function localApi(path, options = {}) {
  const url = new URL(path, 'http://local');
  const method = options.method || 'GET';
  for (const item of routes) {
    if (item.method !== method) continue;
    const params = match(item.path, url.pathname);
    if (!params) continue;
    return item.handler({ params, query: url.searchParams, body: options.body });
  }
  throw new Error(`Нет обработчика для ${method} ${url.pathname}`);
}

export const localStore = { read, write, log };
