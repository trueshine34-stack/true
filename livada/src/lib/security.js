import crypto from 'node:crypto';
import { promisify } from 'node:util';
import { config } from '../config.js';

const scrypt = promisify(crypto.scrypt);
const SCRYPT = { N: 16384, r: 8, p: 1, keylen: 64 };

/** hash parolă: scrypt$N$r$p$salt$hash */
export async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(String(password).normalize('NFKC'), salt, SCRYPT.keylen, { N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p, maxmem: 64 * 1024 * 1024 });
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString('base64url')}$${key.toString('base64url')}`;
}

export async function verifyPassword(password, stored) {
  if (!stored || !stored.startsWith('scrypt$')) {
    // comparație „fantomă” ca timpul de răspuns să nu dezvăluie dacă emailul există
    await scrypt('x', 'salt-placeholder', SCRYPT.keylen, { N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p, maxmem: 64 * 1024 * 1024 });
    return false;
  }
  const [, N, r, p, salt, hash] = stored.split('$');
  const expected = Buffer.from(hash, 'base64url');
  const key = await scrypt(String(password).normalize('NFKC'), Buffer.from(salt, 'base64url'), expected.length, { N: +N, r: +r, p: +p, maxmem: 64 * 1024 * 1024 });
  return crypto.timingSafeEqual(key, expected);
}

export const randomToken = (bytes = 32) => crypto.randomBytes(bytes).toString('base64url');
export const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
export const hmac = (s, purpose = 'generic') => crypto.createHmac('sha256', `${config.secret}:${purpose}`).update(String(s)).digest('base64url');

export function safeEqual(a, b) {
  const A = Buffer.from(String(a || ''));
  const B = Buffer.from(String(b || ''));
  return A.length === B.length && crypto.timingSafeEqual(A, B);
}

/** cod numeric de 6 cifre, distribuție uniformă */
export function numericCode(len = 6) {
  let s = '';
  while (s.length < len) s += crypto.randomInt(0, 10);
  return s;
}

/** Semnează un payload JSON scurt (folosit pentru cookie-ul de flash) */
export function signJson(obj, purpose) {
  const data = Buffer.from(JSON.stringify(obj)).toString('base64url');
  return `${data}.${hmac(data, purpose)}`;
}

export function verifyJson(token, purpose) {
  if (!token || !token.includes('.')) return null;
  const [data, sig] = token.split('.');
  if (!safeEqual(sig, hmac(data, purpose))) return null;
  try { return JSON.parse(Buffer.from(data, 'base64url').toString('utf8')); } catch { return null; }
}

export const csrfFor = (visitorId) => hmac(visitorId, 'csrf');

// ─── limitare rată (fereastră glisantă în memorie) ─────────────────────────
const buckets = new Map();
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of buckets) if (v.reset < now) buckets.delete(k);
}, 60_000).unref();

/** @returns {{ok:boolean, retryAfter:number}} */
export function rateLimit(key, limit, windowMs) {
  const now = Date.now();
  let b = buckets.get(key);
  if (!b || b.reset < now) {
    b = { count: 0, reset: now + windowMs };
    buckets.set(key, b);
  }
  b.count++;
  return { ok: b.count <= limit, retryAfter: Math.ceil((b.reset - now) / 1000) };
}

export const resetRateLimits = () => buckets.clear();

// ─── antete de securitate ──────────────────────────────────────────────────
export function securityHeaders() {
  const tileHosts = new Set();
  try {
    const u = new URL(config.map.tiles.replace('{s}', 'a').replace(/\{[a-z]+\}/g, '0'));
    tileHosts.add(`${u.protocol}//${u.host.replace(/^a\./, '*.')}`);
  } catch { /* ignoră */ }
  const csp = [
    "default-src 'self'",
    `img-src 'self' data: blob: ${[...tileHosts].join(' ')}`.trim(),
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self'",
    "connect-src 'self'",
    "frame-src 'self'",
    "frame-ancestors 'self'",
    "base-uri 'self'",
    "form-action 'self'",
    "object-src 'none'",
  ].join('; ');
  const h = {
    'Content-Security-Policy': csp,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'geolocation=(self), camera=(), microphone=(), payment=(self)',
    'Cross-Origin-Opener-Policy': 'same-origin',
    'X-Frame-Options': 'SAMEORIGIN',
  };
  if (config.production) h['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains';
  return h;
}
