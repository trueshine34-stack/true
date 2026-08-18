/**
 * Rozbior ofert zrodlowych: HTML (JSON-LD / stan OLX / OpenGraph), CSV, JSON.
 * Modul jest czysty - nie siega do sieci ani do dysku, wiec dziala tak samo
 * na serwerze Node i w WebView telefonu. Pobieranie stron robi ten, kto wola.
 */

/** Identyfikator dziala i w Node, i w przegladarce. */
function randomId(prefix) {
  const bytes = new Uint8Array(6);
  (globalThis.crypto || {}).getRandomValues?.(bytes);
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${prefix}_${hex || Math.random().toString(16).slice(2, 14)}`;
}

export const EMPTY_LISTING = {
  title: '',
  description: '',
  images: [],
  basePrice: 0,
  currency: 'PLN',
  categoryId: null,
  categoryName: '',
  brand: '',
  condition: '',
  attributes: {},
  externalId: '',
  sourceUrl: '',
};

export function normalizeListing(raw = {}, extra = {}) {
  const images = (Array.isArray(raw.images) ? raw.images : [raw.image])
    .flat()
    .map((img) => (typeof img === 'string' ? img : img?.url || img?.contentUrl || ''))
    .map((url) => String(url || '').trim())
    .filter((url) => /^https?:\/\//i.test(url));

  return {
    ...EMPTY_LISTING,
    ...raw,
    id: raw.id || randomId('ad'),
    title: String(raw.title || '').trim(),
    description: String(raw.description || '').trim(),
    images: [...new Set(images)].slice(0, 12), // OLX: maks. 12 zdjec na ogloszenie
    basePrice: toNumber(raw.basePrice ?? raw.price),
    currency: String(raw.currency || 'PLN').toUpperCase(),
    attributes: raw.attributes && typeof raw.attributes === 'object' ? raw.attributes : {},
    status: raw.status || 'draft',
    createdAt: raw.createdAt || new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...extra,
  };
}

export function toNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  if (value == null) return 0;
  const cleaned = String(value)
    .replace(/\s| /g, '')
    .replace(/(zł|pln|eur|usd|€|\$)/gi, '')
    .replace(/,(\d{1,2})$/, '.$1')
    .replace(/,(?=\d{3}\b)/g, '');
  const n = Number.parseFloat(cleaned);
  return Number.isFinite(n) ? n : 0;
}

export function extractJsonLd(html) {
  const out = [];
  const re = /<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    try {
      const parsed = JSON.parse(m[1].trim());
      out.push(...(Array.isArray(parsed) ? parsed : [parsed]));
    } catch { /* nie kazdy blok ld+json jest poprawny - pomijamy */ }
  }
  return out.flatMap((node) => (Array.isArray(node?.['@graph']) ? node['@graph'] : [node]));
}

export function extractMeta(html) {
  const meta = {};
  const re = /<meta[^>]+(?:property|name)=["']([^"']+)["'][^>]+content=["']([^"']*)["'][^>]*>/gi;
  let m;
  while ((m = re.exec(html)) !== null) meta[m[1].toLowerCase()] = decodeEntities(m[2]);
  return meta;
}

function decodeEntities(text) {
  return String(text)
    .replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&');
}

/** Stan wstepnie renderowany OLX - zawiera pelny opis i wszystkie zdjecia. */
export function extractOlxState(html) {
  const m = html.match(/window\.__PRERENDERED_STATE__\s*=\s*("(?:[^"\\]|\\.)*")/);
  if (!m) return null;
  try {
    return JSON.parse(JSON.parse(m[1]));
  } catch {
    return null;
  }
}

function fromOlxState(state) {
  const ad = state?.ad?.ad || state?.ad || null;
  if (!ad || !ad.title) return null;
  const priceParam = (ad.params || []).find((p) => p.key === 'price' || p.type === 'price');
  const attributes = {};
  for (const p of ad.params || []) {
    if (p.key === 'price') continue;
    const value = p.normalizedValue || p.value?.label || p.value?.key || p.value;
    if (typeof value === 'string' || typeof value === 'number') attributes[p.name || p.key] = String(value);
  }
  return {
    externalId: String(ad.id ?? ''),
    title: ad.title,
    description: ad.description || '',
    images: (ad.photos || []).map((p) => (typeof p === 'string' ? p : p.link || p.url || '')),
    basePrice: toNumber(priceParam?.value?.value ?? priceParam?.normalizedValue ?? 0),
    currency: priceParam?.value?.currency || 'PLN',
    categoryName: ad.category?.name || '',
    categoryId: ad.category?.id ?? null,
    attributes,
  };
}

function fromJsonLd(nodes) {
  const product = nodes.find((n) => /product|offer|vehicle|apartment/i.test(String(n?.['@type'] || '')));
  if (!product) return null;
  const offer = Array.isArray(product.offers) ? product.offers[0] : product.offers;
  const attributes = {};
  for (const key of ['color', 'material', 'size', 'model', 'sku', 'gtin', 'mpn']) {
    if (product[key]) attributes[key] = String(product[key]);
  }
  for (const prop of product.additionalProperty || []) {
    if (prop?.name) attributes[prop.name] = String(prop.value ?? '');
  }
  return {
    externalId: String(product.sku || product.productID || ''),
    title: product.name || '',
    description: product.description || '',
    images: product.image || [],
    basePrice: toNumber(offer?.price ?? product.price),
    currency: offer?.priceCurrency || 'PLN',
    brand: typeof product.brand === 'string' ? product.brand : product.brand?.name || '',
    categoryName: typeof product.category === 'string' ? product.category : product.category?.name || '',
    condition: /new/i.test(String(offer?.itemCondition || '')) ? 'Nowy' : '',
    attributes,
  };
}

function fromMeta(meta, url) {
  if (!meta['og:title'] && !meta['twitter:title']) return null;
  return {
    title: meta['og:title'] || meta['twitter:title'] || '',
    description: meta['og:description'] || meta.description || '',
    images: [meta['og:image'], meta['twitter:image']].filter(Boolean),
    basePrice: toNumber(meta['product:price:amount'] || meta['og:price:amount']),
    currency: meta['product:price:currency'] || meta['og:price:currency'] || 'PLN',
    sourceUrl: url,
  };
}

/** Parsuje pobrany HTML na ogloszenie. Kolejnosc: stan OLX -> JSON-LD -> OpenGraph. */
export function parseHtml(html, url) {
  const candidates = [
    fromOlxState(extractOlxState(html) || {}),
    fromJsonLd(extractJsonLd(html)),
    fromMeta(extractMeta(html), url),
  ].filter(Boolean);
  if (!candidates.length) return null;
  const merged = candidates.reduce((acc, c) => {
    for (const [k, v] of Object.entries(c)) {
      const empty = acc[k] === undefined || acc[k] === '' || acc[k] === 0
        || (Array.isArray(acc[k]) && acc[k].length === 0);
      if (empty && v !== '' && v !== undefined && v !== null) acc[k] = v;
    }
    return acc;
  }, {});
  return normalizeListing({ ...merged, sourceUrl: url });
}

/**
 * Pobiera i rozbiera oferte. Sposob pobrania wstrzykuje wolajacy:
 * na serwerze to fetch, w aplikacji - most do natywnego klienta HTTP.
 * @param {(url: string) => Promise<string>} fetchText
 */
export async function importFromUrl(url, fetchText, extra = {}) {
  const html = await fetchText(url);
  const listing = parseHtml(html, url);
  if (!listing) throw new Error(`Nie udalo sie odczytac danych oferty z ${url}`);
  return normalizeListing(listing, extra);
}

/* --------------------------------- CSV --------------------------------- */

export function parseCsv(text, delimiter = null) {
  const src = String(text || '').replace(/^﻿/, '').trim();
  if (!src) return [];
  const sep = delimiter || guessDelimiter(src);
  const rows = [];
  let field = '';
  let row = [];
  let quoted = false;
  for (let i = 0; i < src.length; i += 1) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') { field += '"'; i += 1; } else quoted = false;
      } else field += ch;
      continue;
    }
    if (ch === '"') { quoted = true; continue; }
    if (ch === sep) { row.push(field); field = ''; continue; }
    if (ch === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue; }
    if (ch === '\r') continue;
    field += ch;
  }
  row.push(field);
  rows.push(row);

  const [header, ...body] = rows.filter((r) => r.some((c) => c.trim() !== ''));
  if (!header) return [];
  const keys = header.map((h) => h.trim().toLowerCase());
  return body.map((cells) => Object.fromEntries(keys.map((k, i) => [k, (cells[i] ?? '').trim()])));
}

function guessDelimiter(text) {
  const line = text.split('\n')[0];
  const counts = [';', ',', '\t', '|'].map((d) => [d, line.split(d).length]);
  counts.sort((a, b) => b[1] - a[1]);
  return counts[0][1] > 1 ? counts[0][0] : ',';
}

const FIELD_ALIASES = {
  title: ['title', 'name', 'tytul', 'tytuł', 'nazwa', 'наименование', 'название'],
  description: ['description', 'desc', 'opis', 'описание'],
  basePrice: ['price', 'cena', 'base_price', 'cena_zakupu', 'cost', 'цена', 'закупка'],
  currency: ['currency', 'waluta', 'валюта'],
  images: ['images', 'image', 'photos', 'zdjecia', 'zdjęcia', 'foto', 'фото'],
  externalId: ['id', 'sku', 'external_id', 'kod', 'артикул'],
  categoryName: ['category', 'kategoria', 'категория'],
  categoryId: ['category_id', 'kategoria_id'],
  brand: ['brand', 'marka', 'бренд'],
  condition: ['condition', 'stan', 'состояние'],
  sourceUrl: ['url', 'link', 'source_url', 'ссылка'],
};

/** Mapuje dowolny wiersz feedu na nasz ksztalt; nieznane kolumny ida do attributes. */
export function mapRow(row) {
  const used = new Set();
  const out = {};
  for (const [field, aliases] of Object.entries(FIELD_ALIASES)) {
    const key = aliases.find((a) => row[a] !== undefined && String(row[a]).trim() !== '');
    if (!key) continue;
    used.add(key);
    out[field] = row[key];
  }
  if (out.images) out.images = String(out.images).split(/[|,;\s]+/).filter(Boolean);
  const attributes = {};
  for (const [k, v] of Object.entries(row)) {
    if (used.has(k) || !String(v).trim()) continue;
    attributes[k] = String(v).trim();
  }
  out.attributes = attributes;
  return normalizeListing(out);
}

export function importFromCsv(text, extra = {}) {
  return parseCsv(text).map((row) => normalizeListing(mapRow(row), extra));
}

export function importFromJson(text, extra = {}) {
  const parsed = typeof text === 'string' ? JSON.parse(text) : text;
  const items = Array.isArray(parsed) ? parsed : parsed.items || parsed.offers || parsed.products || [parsed];
  return items.map((item) => normalizeListing(mapRow(lowerKeys(item)), extra));
}

function lowerKeys(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj || {})) {
    out[String(k).toLowerCase()] = Array.isArray(v) ? v.join('|') : v;
  }
  return out;
}
