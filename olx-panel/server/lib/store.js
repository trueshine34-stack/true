import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from './config.js';

const DEFAULTS = {
  settings: {
    // Regula marzy stosowana, gdy nie ma nadpisania per kategoria / per zrodlo.
    pricing: {
      percent: 25,
      fixed: 0,
      minMargin: 15,
      maxMargin: 0,          // 0 = bez gornego limitu
      shipping: 0,           // koszt wysylki wliczany w cene
      feePercent: 0,         // prowizja platnosci - doliczana tak, by marza netto zostala
      rounding: 'end99',     // none | end99 | up1 | up5 | up10 | up50 | up100
      currency: 'PLN',
      negotiable: true,
    },
    categoryRules: {},       // { "<categoryId|slug>": { percent, fixed, ... } }
    sourceRules: {},         // { "<sourceId>": { percent, fixed, ... } }
    template: {
      title: '{{title}}',
      body: [
        '{{description}}',
        '',
        '——————————————',
        '✅ Stan: {{condition}}',
        '📦 Wysyłka: {{shipping}}',
        '💳 Płatność: {{payment}}',
        '🚚 Czas realizacji: {{delivery}}',
        '',
        '{{highlights}}',
        '',
        'ℹ️ {{footer}}',
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
      titleCase: 'smart',    // none | smart | upperFirst
      sanitize: true,        // usuwa cudze telefony, e-maile, linki, nazwy sklepow
      hashtags: true,
    },
    contact: { name: '', phone: '' },
    location: { cityId: null, cityName: '', districtId: null, lat: null, lon: null },
    advertiserType: 'private', // private | business
  },
  sources: [],
  listings: [],
  logs: [],
  tokens: {},
};

const FILES = {
  settings: 'settings.json',
  sources: 'sources.json',
  listings: 'listings.json',
  logs: 'logs.json',
  tokens: 'tokens.json',
};

const cache = new Map();

function filePath(name) {
  return path.join(config.dataDir, FILES[name]);
}

function ensureDir() {
  fs.mkdirSync(config.dataDir, { recursive: true });
}

function deepMerge(base, patch) {
  if (Array.isArray(base) || Array.isArray(patch)) return patch === undefined ? base : patch;
  if (typeof base !== 'object' || base === null) return patch === undefined ? base : patch;
  if (typeof patch !== 'object' || patch === null) return patch === undefined ? base : patch;
  const out = { ...base };
  for (const [key, value] of Object.entries(patch)) out[key] = deepMerge(base[key], value);
  return out;
}

export function read(name) {
  if (cache.has(name)) return cache.get(name);
  ensureDir();
  let value = DEFAULTS[name];
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath(name), 'utf8'));
    value = Array.isArray(DEFAULTS[name]) ? parsed : deepMerge(DEFAULTS[name], parsed);
  } catch {
    /* pierwszy start albo uszkodzony plik - jedziemy na domyslnych */
  }
  cache.set(name, value);
  return value;
}

export function write(name, value) {
  ensureDir();
  cache.set(name, value);
  const target = filePath(name);
  const tmp = `${target}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2));
  fs.renameSync(tmp, target); // zapis atomowy
  return value;
}

export function update(name, mutator) {
  const next = mutator(read(name));
  return write(name, next);
}

export const settings = {
  get: () => read('settings'),
  patch: (patch) => write('settings', deepMerge(read('settings'), patch)),
};

export function newId(prefix = 'l') {
  return `${prefix}_${crypto.randomBytes(6).toString('hex')}`;
}

export function log(level, message, meta = {}) {
  const entry = { id: newId('log'), at: new Date().toISOString(), level, message, meta };
  update('logs', (logs) => [entry, ...logs].slice(0, 500));
  if (level === 'error') console.error(`[${level}] ${message}`, meta);
  else console.log(`[${level}] ${message}`);
  return entry;
}

export function resetCache() {
  cache.clear();
}

export { deepMerge };
