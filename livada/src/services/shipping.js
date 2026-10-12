import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { ROOT } from '../config.js';
import { one, all, getSetting } from '../db/index.js';
import { fold } from '../lib/format.js';
import { CARRIERS } from '../db/defaults.js';
import { t, isEn } from '../lib/i18n.js';

export const methods = () => all('SELECT * FROM delivery_methods WHERE active = 1 ORDER BY sort');
export const allMethods = () => all('SELECT * FROM delivery_methods ORDER BY sort');
export const methodByCode = (code) => one('SELECT * FROM delivery_methods WHERE code = ? AND active = 1', code);
export const carrier = (code) => CARRIERS[code] || { name: code, color: '#555', track: '' };

export function feeFor(method, subtotal, { freeShipping = false } = {}) {
  if (!method) return 0;
  if (freeShipping) return 0;
  if (method.free_over !== null && method.free_over !== undefined && subtotal >= method.free_over) return 0;
  return method.price;
}

const DAY = ['duminică', 'luni', 'marți', 'miercuri', 'joi', 'vineri', 'sâmbătă'];
const MONTH = ['ianuarie', 'februarie', 'martie', 'aprilie', 'mai', 'iunie', 'iulie', 'august', 'septembrie', 'octombrie', 'noiembrie', 'decembrie'];
// în engleză (en-GB): „Tuesday 13 October”
const DAY_EN = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTH_EN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** ora curentă în București */
function bucharestNow(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Bucharest', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
    .formatToParts(now).reduce((o, p) => ({ ...o, [p.type]: p.value }), {});
  return { date: new Date(Date.UTC(+parts.year, +parts.month - 1, +parts.day)), hour: +parts.hour % 24, minute: +parts.minute };
}

const addBusinessDays = (d, n) => {
  const r = new Date(d);
  while (n > 0) {
    r.setUTCDate(r.getUTCDate() + 1);
    if (r.getUTCDay() !== 0) n--; // curierii livrează și sâmbăta, nu duminica
  }
  return r;
};

/** estimare de livrare în limba cererii: „Mâine, marți 13 octombrie” / „Tomorrow, Tuesday 13 October” */
export function estimate(method, now = new Date()) {
  if (!method) return null;
  const store = getSetting('store') || {};
  const { date, hour } = bucharestNow(now);
  if (method.type === 'express') {
    return hour < (store.express_cutoff_hour ?? 14) && date.getUTCDay() !== 0 ? t('Azi, între 18:00 și 22:00') : t('Mâine, între 18:00 și 22:00');
  }
  if (method.type === 'pickup') {
    const open = date.getUTCDay() !== 0 && hour < 18;
    return open ? t('Gata de ridicare în ~2 ore') : t('Gata de ridicare de mâine, de la 10:00');
  }
  const days = /2–5|2-5/.test(method.eta) ? 3 : /2–3|48/.test(method.eta) ? 2 : 1;
  let ship = new Date(date);
  if (hour >= (store.cutoff_hour ?? 16) || ship.getUTCDay() === 0) ship = addBusinessDays(ship, 1);
  const arrive = addBusinessDays(ship, days);
  const diff = Math.round((arrive - date) / 86400000);
  const en = isEn();
  const label = `${(en ? DAY_EN : DAY)[arrive.getUTCDay()]} ${arrive.getUTCDate()} ${(en ? MONTH_EN : MONTH)[arrive.getUTCMonth()]}`;
  return diff === 1 ? t('Mâine, {label}', { label }) : `${label[0].toUpperCase()}${label.slice(1)}`;
}

/** „Comandă în următoarele 3 h 20 min și pleacă azi” */
export function cutoffHint(now = new Date()) {
  const store = getSetting('store') || {};
  const { date, hour, minute } = bucharestNow(now);
  const cutoff = store.cutoff_hour ?? 16;
  if (date.getUTCDay() === 0 || hour >= cutoff) return null;
  const mins = (cutoff - hour) * 60 - minute;
  const h = Math.floor(mins / 60), m = mins % 60;
  return t('Comandă în {time} și expediem azi', { time: `${h ? `${h} h ` : ''}${m} min` });
}

// ─── lockere ────────────────────────────────────────────────────────────────
let lockerCache = null;
/** lista compactă pentru hartă, comprimată o singură dată (br + gzip) și servită cu ETag */
export function lockersPayload() {
  if (!lockerCache) {
    const rows = all(`SELECT l.id, l.provider, l.kind, l.name, l.address, l.city, l.county, l.postcode, l.lat, l.lng, l.hours, l.note
      FROM lockers l WHERE l.active = 1 ORDER BY l.county, l.city, l.name`);
    const json = JSON.stringify({
      fields: ['id', 'provider', 'kind', 'name', 'address', 'city', 'county', 'postcode', 'lat', 'lng', 'hours', 'note'],
      rows: rows.map((r) => [r.id, r.provider, r.kind === 'point' ? 1 : 0, r.name, r.address, r.city, r.county, r.postcode, r.lat, r.lng, r.hours, r.note]),
    });
    const raw = Buffer.from(json);
    lockerCache = {
      raw,
      br: zlib.brotliCompressSync(raw, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 9, [zlib.constants.BROTLI_PARAM_SIZE_HINT]: raw.length } }),
      gz: zlib.gzipSync(raw, { level: 9 }),
      etag: `"${crypto.createHash('sha1').update(raw).digest('base64url').slice(0, 20)}"`,
      count: rows.length,
    };
  }
  return lockerCache;
}
export const lockersCompact = () => lockersPayload().raw.toString();
let totalCache = null;
/** numărul de lockere și puncte active (pentru textele din magazin) */
export const lockerTotal = () => (totalCache ??= one('SELECT COUNT(*) n FROM lockers WHERE active = 1').n);
export const invalidateLockers = () => { lockerCache = null; nearCache = null; totalCache = null; };
export const lockerById = (id) => one('SELECT * FROM lockers WHERE id = ? AND active = 1', id);
export const lockerStats = () => all(`SELECT provider, COUNT(*) n, SUM(kind = 'locker') lockers, SUM(kind = 'point') points,
  COUNT(DISTINCT county || '/' || city) cities, COUNT(DISTINCT county) counties FROM lockers WHERE active = 1 GROUP BY provider ORDER BY n DESC`);

/** sursele listei de lockere (din data/lockers.json, fără lista propriu-zisă) */
let sourcesCache = null;
export function lockerSources() {
  if (!sourcesCache) {
    const file = path.join(ROOT, 'data/lockers.json');
    try {
      const raw = fs.readFileSync(file, 'utf8');
      // lista e ultima cheie din fișier; „lockers” apare și în statisticile surselor, de aceea căutăm începutul tabloului
      const head = `${raw.slice(0, raw.indexOf(',"lockers":['))}}`;
      const d = JSON.parse(head);
      sourcesCache = { generated: d.generated, sources: d.sources };
    } catch { sourcesCache = {}; }
  }
  return sourcesCache;
}

// ─── localități (căutarea de pe hartă, inclusiv satele fără lockere) ────────
let places = null;
let nearCache = null;
function loadPlaces() {
  if (!places) {
    const file = path.join(ROOT, 'data/places.json');
    const d = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : { rows: [] };
    places = d.rows.map(([name, county, lat, lng, pop]) => ({ name, county, lat, lng, pop, key: fold(name), countyKey: fold(county) }));
  }
  return places;
}
/** coordonatele lockerelor active, pentru numărul de puncte din jurul unei localități */
function lockerPoints() {
  if (!nearCache) nearCache = all('SELECT lat, lng FROM lockers WHERE active = 1');
  return nearCache;
}
function countNear(lat, lng, km) {
  const dLat = km / 111, dLng = km / (111 * Math.cos(lat * Math.PI / 180));
  let n = 0;
  for (const p of lockerPoints()) {
    if (Math.abs(p.lat - lat) <= dLat && Math.abs(p.lng - lng) <= dLng && haversineKm({ lat, lng }, p) <= km) n++;
  }
  return n;
}

/** „cluj” → Cluj-Napoca; „sanpetru bv” → Sânpetru, Brașov; ordonat după potrivire și populație */
export function searchPlaces(q, limit = 6) {
  const words = fold(q).replace(/[^a-z0-9 ]/g, ' ').split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const scored = [];
  for (const p of loadPlaces()) {
    const name = p.key;
    const first = words[0];
    let score;
    if (name === words.join(' ')) score = 0;
    else if (name.startsWith(words.join(' '))) score = 1;
    else if (name.startsWith(first) || name.split(/[ -]/).some((w) => w.startsWith(first))) score = 2;
    else continue;
    // cuvintele în plus pot fi județul („sanpetru brasov”)
    const rest = words.slice(1).filter((w) => !name.includes(w));
    if (rest.length && !rest.every((w) => p.countyKey.includes(w))) continue;
    scored.push([score, -p.pop, p]);
  }
  scored.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  return scored.slice(0, limit).map(([, , p]) => ({
    name: p.name, county: p.county, lat: p.lat, lng: p.lng,
    near: countNear(p.lat, p.lng, p.pop > 100000 ? 8 : 5),
  }));
}

export function haversineKm(a, b) {
  const R = 6371, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
