import { one, all, getSetting } from '../db/index.js';
import { CARRIERS } from '../db/defaults.js';

export const methods = () => all('SELECT * FROM delivery_methods WHERE active = 1 ORDER BY sort');
export const allMethods = () => all('SELECT * FROM delivery_methods ORDER BY sort');
export const methodByCode = (code) => one('SELECT * FROM delivery_methods WHERE code = ? AND active = 1', code);
export const carrier = (code) => CARRIERS[code] || { name: code, short: code.slice(0, 2).toUpperCase(), color: '#555', ink: '#fff', track: '' };

export function feeFor(method, subtotal, { freeShipping = false } = {}) {
  if (!method) return 0;
  if (freeShipping) return 0;
  if (method.free_over !== null && method.free_over !== undefined && subtotal >= method.free_over) return 0;
  return method.price;
}

const DAY = ['duminică', 'luni', 'marți', 'miercuri', 'joi', 'vineri', 'sâmbătă'];
const MONTH = ['ianuarie', 'februarie', 'martie', 'aprilie', 'mai', 'iunie', 'iulie', 'august', 'septembrie', 'octombrie', 'noiembrie', 'decembrie'];

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

/** estimare de livrare în română: „mâine, marți 13 octombrie” */
export function estimate(method, now = new Date()) {
  if (!method) return null;
  const store = getSetting('store') || {};
  const { date, hour } = bucharestNow(now);
  if (method.type === 'express') {
    return hour < (store.express_cutoff_hour ?? 14) && date.getUTCDay() !== 0 ? 'Azi, între 18:00 și 22:00' : 'Mâine, între 18:00 și 22:00';
  }
  if (method.type === 'pickup') {
    const open = date.getUTCDay() !== 0 && hour < 18;
    return open ? 'Gata de ridicare în ~2 ore' : 'Gata de ridicare de mâine, de la 10:00';
  }
  const days = /2–5|2-5/.test(method.eta) ? 3 : /2–3|48/.test(method.eta) ? 2 : 1;
  let ship = new Date(date);
  if (hour >= (store.cutoff_hour ?? 16) || ship.getUTCDay() === 0) ship = addBusinessDays(ship, 1);
  const arrive = addBusinessDays(ship, days);
  const diff = Math.round((arrive - date) / 86400000);
  const label = `${DAY[arrive.getUTCDay()]} ${arrive.getUTCDate()} ${MONTH[arrive.getUTCMonth()]}`;
  return diff === 1 ? `Mâine, ${label}` : `${label[0].toUpperCase()}${label.slice(1)}`;
}

/** „Comandă în următoarele 3 h 20 min și pleacă azi” */
export function cutoffHint(now = new Date()) {
  const store = getSetting('store') || {};
  const { date, hour, minute } = bucharestNow(now);
  const cutoff = store.cutoff_hour ?? 16;
  if (date.getUTCDay() === 0 || hour >= cutoff) return null;
  const mins = (cutoff - hour) * 60 - minute;
  const h = Math.floor(mins / 60), m = mins % 60;
  return `Comandă în ${h ? `${h} h ` : ''}${m} min și expediem azi`;
}

// ─── lockere ────────────────────────────────────────────────────────────────
let lockerCache = null;
export function lockersCompact() {
  if (!lockerCache) {
    const rows = all('SELECT id, provider, name, address, city, county, postcode, lat, lng, hours FROM lockers WHERE active = 1 ORDER BY city, name');
    lockerCache = JSON.stringify({
      fields: ['id', 'provider', 'name', 'address', 'city', 'county', 'postcode', 'lat', 'lng', 'hours'],
      rows: rows.map((r) => [r.id, r.provider, r.name, r.address, r.city, r.county, r.postcode, r.lat, r.lng, r.hours]),
    });
  }
  return lockerCache;
}
export const invalidateLockers = () => { lockerCache = null; };
export const lockerById = (id) => one('SELECT * FROM lockers WHERE id = ? AND active = 1', id);
export const lockerStats = () => all('SELECT provider, COUNT(*) n, COUNT(DISTINCT city) cities FROM lockers WHERE active = 1 GROUP BY provider ORDER BY n DESC');

export function haversineKm(a, b) {
  const R = 6371, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
