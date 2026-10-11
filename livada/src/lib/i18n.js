// Limba magazinului: română (implicit, la rădăcină) și engleză (sub /en/…).
// Textele din cod rămân în română și sunt cheia traducerii: t('Adaugă în coș') → „Add to bag” pe /en.
// Limba cererii curente stă într-un AsyncLocalStorage, așa că șabloanele nu au nevoie de ctx.
import fs from 'node:fs';
import path from 'node:path';
import { AsyncLocalStorage } from 'node:async_hooks';
import { ROOT, config } from '../config.js';
import { raw, esc } from './html.js';

export const LANGS = ['ro', 'en'];
export const LOCALE = { ro: 'ro-RO', en: 'en-GB' };
const als = new AsyncLocalStorage();

/** limba cererii curente ('ro' | 'en') */
export const lang = () => als.getStore()?.lang || 'ro';
export const isEn = () => lang() === 'en';
export const locale = () => LOCALE[lang()];

/** rulează fn în limba dată (ex.: emailul unei comenzi, trimis din admin, în limba clientului) */
export function withLang(l, fn) {
  return als.run({ lang: LANGS.includes(l) ? l : 'ro' }, fn);
}

// ─── dicționare: src/i18n/en/*.json, { "text în română": "English text" } ──────
const DIR = path.join(ROOT, 'src/i18n');
let dict = { en: {} };
let stamp = '';
let checkedAt = 0;
const missing = new Map();

function dirStamp() {
  try {
    return fs.readdirSync(path.join(DIR, 'en')).filter((f) => f.endsWith('.json'))
      .map((f) => `${f}:${fs.statSync(path.join(DIR, 'en', f)).mtimeMs}`).join('|');
  } catch { return ''; }
}

function load() {
  const out = {};
  const d = path.join(DIR, 'en');
  if (fs.existsSync(d)) {
    for (const f of fs.readdirSync(d).filter((x) => x.endsWith('.json')).sort()) {
      try {
        Object.assign(out, JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')));
      } catch (e) {
        console.error(`[livada] i18n: ${f} nu e JSON valid — ${e.message}`);
      }
    }
  }
  dict = { en: out };
  stamp = dirStamp();
}
load();

function fresh() {
  // în development, dicționarele se reîncarcă singure când se modifică fișierele
  if (config.production) return;
  const now = Date.now();
  if (now - checkedAt < 1000) return;
  checkedAt = now;
  if (dirStamp() !== stamp) load();
}

const fill = (s, vars) => (vars ? s.replace(/\{(\w+)\}/g, (m, k) => (vars[k] !== undefined && vars[k] !== null ? String(vars[k]) : m)) : s);

/**
 * Traduce un text din română în limba curentă. Variabile: t('Mulțumim, {name}!', { name }).
 * Lipsă din dicționar → rămâne textul românesc (și apare în /dev/i18n).
 */
export function t(s, vars) {
  let out = s;
  if (lang() === 'en' && s) {
    fresh();
    const v = dict.en[s];
    if (v !== undefined) out = v;
    else if (!config.production) missing.set(s, (missing.get(s) || 0) + 1);
  }
  return fill(out, vars);
}

/** ca t(), dar pentru texte cu marcaj HTML în traducere; variabilele sunt escapate */
export function tHtml(s, vars) {
  const safe = vars ? Object.fromEntries(Object.entries(vars).map(([k, v]) => [k, v && v.s !== undefined ? v.s : esc(v)])) : undefined;
  return raw(t(s, safe));
}

/** text ales direct după limbă: tr({ ro: '…', en: '…' }) — pentru texte lungi (pagini legale) */
export const pick = (o) => (o && typeof o === 'object' ? (o[lang()] ?? o.ro) : o);

/** dicționarul pentru scripturile din browser (src/i18n/en/client.json) */
export function clientDict() {
  if (lang() !== 'en') return null;
  fresh();
  try {
    return JSON.parse(fs.readFileSync(path.join(DIR, 'en', 'client.json'), 'utf8'));
  } catch { return {}; }
}

/** textele cerute în engleză și negăsite în dicționar (pentru /dev/i18n) */
export const missingStrings = () => [...missing.entries()].sort((a, b) => b[1] - a[1]);
export const clearMissing = () => missing.clear();
export const reloadDict = () => load();

// ─── adrese ──────────────────────────────────────────────────────────────────
// căi care nu au versiune pe limbă (fișiere, API, admin)
const NEUTRAL = /^\/(?:en|ro|img|css|js|vendor|fonts|uploads|api|admin|dev|favicon|robots\.txt|sitemap|manifest|apple-touch|icon-|og-|\.well-known)(?:[/.?#-]|$)/;

/** „/produs/x” → „/en/produs/x” în engleză; căile neutre rămân la fel */
export function localizePath(p, l = lang()) {
  if (l !== 'en' || typeof p !== 'string' || !p.startsWith('/') || p.startsWith('//') || NEUTRAL.test(p)) return p;
  return p === '/' ? '/en' : `/en${p}`;
}

/** aceeași pagină în cealaltă limbă (comutatorul RO / EN): /ro/… redirecționează spre rădăcină */
export function alternatePath(p, target) {
  const base = p || '/';
  if (target === 'en') return base === '/' ? '/en' : `/en${base}`;
  return base === '/' ? '/ro' : `/ro${base}`;
}

/** rescrie legăturile interne din HTML (href / action) pentru pagina în engleză */
export function localizeHtml(s, l = lang()) {
  if (l !== 'en') return s;
  return s.replace(/(\s(?:href|action|formaction|data-href)=")(\/[^"]*)"/g, (m, a, p) => `${a}${localizePath(p, l)}"`);
}

/** aceeași rescriere în JSON (fragmente HTML întoarse de API: coșul, sumarul comenzii) */
export function localizeJson(s, l = lang()) {
  if (l !== 'en') return s;
  return s.replace(/((?:href|action)=\\")(\/[^"\\]*)\\"/g, (m, a, p) => `${a}${localizePath(p, l)}\\"`);
}
