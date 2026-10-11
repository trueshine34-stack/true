// Formatare pentru România: bani (integer) → „7.649,99 lei”, date, telefoane.

const nf = new Intl.NumberFormat('ro-RO', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const nf0 = new Intl.NumberFormat('ro-RO', { maximumFractionDigits: 0 });

/** 764999 → "7.649,99 lei" */
export function lei(bani, { suffix = true } = {}) {
  const v = nf.format((Number(bani) || 0) / 100);
  return suffix ? `${v} lei` : v;
}

/** sumă fără zecimale dacă e întreagă: 50000 → "500 lei" */
export function leiShort(bani) {
  const n = (Number(bani) || 0) / 100;
  return Number.isInteger(n) ? `${nf0.format(n)} lei` : lei(bani);
}

export const toBani = (v) => Math.round(Number(String(v).replace(/\s/g, '').replace(',', '.')) * 100);

/** valoarea TVA inclusă într-un preț cu TVA */
export const vatIncluded = (gross, rate = 21) => Math.round(gross - gross / (1 + rate / 100));

const dtf = new Intl.DateTimeFormat('ro-RO', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Europe/Bucharest' });
const dtfShort = new Intl.DateTimeFormat('ro-RO', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'Europe/Bucharest' });
const dttf = new Intl.DateTimeFormat('ro-RO', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Bucharest' });
const tf = new Intl.DateTimeFormat('ro-RO', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Bucharest' });

// SQLite păstrează datele ca „YYYY-MM-DD HH:MM:SS” în UTC
export function asDate(d) {
  if (d instanceof Date) return d;
  if (typeof d === 'number') return new Date(d);
  const s = String(d);
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return new Date(`${s}T00:00:00Z`);
  if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(s)) return new Date(`${s.replace(' ', 'T')}Z`);
  return new Date(s);
}

export const date = (d) => dtf.format(asDate(d));
export const dateShort = (d) => dtfShort.format(asDate(d));
export const dateTime = (d) => dttf.format(asDate(d));
export const time = (d) => tf.format(asDate(d));

export function plural(n, one, few, many) {
  // reguli RO: 1 produs, 2–19 produse, 20+ de produse
  if (n === 1) return `${n} ${one}`;
  if (n === 0 || (n % 100 > 0 && n % 100 < 20)) return `${n} ${few}`;
  return `${n} ${many}`;
}

/** +40 722 123 456 */
export function phone(p) {
  const d = String(p || '').replace(/\D/g, '');
  const n = d.startsWith('40') ? d.slice(2) : d.startsWith('0') ? d.slice(1) : d;
  if (n.length !== 9) return p || '';
  return `+40 ${n.slice(0, 3)} ${n.slice(3, 6)} ${n.slice(6)}`;
}

export function slugify(s) {
  return String(s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80);
}

/** elimină diacriticele (pentru căutare) */
export const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
