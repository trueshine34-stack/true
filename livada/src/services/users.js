import { one, all, run, tx } from '../db/index.js';
import { config } from '../config.js';
import { hashPassword, verifyPassword, randomToken, sha256, numericCode, safeEqual } from '../lib/security.js';
import { ValidationError, clean, isEmail, normalizePhone, passwordProblem } from '../lib/validate.js';
import { sendMail } from '../lib/mailer.js';
import * as T from './mail-templates.js';
import { dateTime } from '../lib/format.js';

export const VERIFY_TTL_MIN = 30;
export const VERIFY_MAX_ATTEMPTS = 5;
export const RESEND_COOLDOWN_S = 60;
const SESSION_DAYS = 30;
const SESSION_SHORT_HOURS = 12;

export const publicUser = (u) => u && ({
  id: u.id, email: u.email, first_name: u.first_name, last_name: u.last_name, phone: u.phone, role: u.role,
  verified: !!u.email_verified_at, marketing: !!u.marketing_opt_in, admin_lang: u.admin_lang,
});

export const userById = (id) => one('SELECT * FROM users WHERE id = ?', id);
export const userByEmail = (email) => one('SELECT * FROM users WHERE email = ? COLLATE NOCASE', String(email || '').trim());

/** descriere scurtă a dispozitivului pentru emailurile de securitate */
export function describeUA(ua = '') {
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android' : /Mac OS X/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} pe ${os}` : browser;
}

const maskIp = (ip = '') => (ip.includes('.') ? ip.replace(/\.\d+$/, '.•••') : ip.replace(/:[0-9a-f]+$/i, ':••••'));
export const requestMeta = (ctx) => ({ when: dateTime(new Date()), device: describeUA(ctx?.req?.headers['user-agent']), ip: maskIp(ctx?.ip || '') });

// ─── înregistrare + confirmare email ───────────────────────────────────────
export async function register(body, meta) {
  const errors = {};
  const data = {
    first_name: clean(body.first_name, 60), last_name: clean(body.last_name, 60),
    email: clean(body.email, 190).toLowerCase(), phone: body.phone ? normalizePhone(body.phone) : '',
    marketing: body.marketing === true || body.marketing === 'on' || body.marketing === '1',
  };
  if (!data.first_name) errors.first_name = 'Completează prenumele.';
  if (!data.last_name) errors.last_name = 'Completează numele.';
  if (!isEmail(data.email)) errors.email = 'Adresa de email nu pare corectă.';
  if (body.phone && !data.phone) errors.phone = 'Numărul de telefon nu este valid (ex: 0722 123 456).';
  const pwp = passwordProblem(body.password);
  if (pwp) errors.password = pwp;
  if (!(body.terms === true || body.terms === 'on' || body.terms === '1')) errors.terms = 'Trebuie să accepți Termenii și Politica de confidențialitate.';
  if (Object.keys(errors).length) throw new ValidationError(errors);
  const taken = () => new ValidationError({ email: 'Există deja un cont cu acest email. Te poți autentifica sau reseta parola.' });
  if (userByEmail(data.email)) throw taken();

  const hash = await hashPassword(body.password);
  // verificăm din nou după hash (async): două trimiteri simultane nu trebuie să ajungă la constrângerea UNIQUE
  if (userByEmail(data.email)) throw taken();
  const r = run(`INSERT INTO users(email, password_hash, first_name, last_name, phone, marketing_opt_in) VALUES (?,?,?,?,?,?)`,
    data.email, hash, data.first_name, data.last_name, data.phone || '', data.marketing ? 1 : 0);
  const user = userById(Number(r.lastInsertRowid));
  if (data.marketing) run('INSERT OR IGNORE INTO newsletter(email) VALUES (?)', data.email);
  await sendVerification(user, meta);
  return user;
}

export function lastVerification(userId) {
  return one('SELECT * FROM email_verifications WHERE user_id = ? ORDER BY id DESC LIMIT 1', userId);
}

export async function sendVerification(user, meta, { force = false } = {}) {
  const last = lastVerification(user.id);
  if (!force && last && !last.used_at) {
    const age = (Date.now() - Date.parse(`${last.created_at.replace(' ', 'T')}Z`)) / 1000;
    if (age < RESEND_COOLDOWN_S) {
      const e = new Error(`Poți cere un cod nou peste ${Math.ceil(RESEND_COOLDOWN_S - age)} secunde.`);
      e.status = 429;
      throw e;
    }
  }
  const code = numericCode(6);
  const token = randomToken(32);
  tx(() => {
    run("UPDATE email_verifications SET used_at = datetime('now') WHERE user_id = ? AND used_at IS NULL", user.id);
    run(`INSERT INTO email_verifications(user_id, code_hash, token_hash, expires_at) VALUES (?,?,?, datetime('now', '+${VERIFY_TTL_MIN} minutes'))`,
      user.id, sha256(`${user.id}:${code}`), sha256(token));
  });
  const link = `${config.publicUrl}/cont/confirma?token=${encodeURIComponent(token)}`;
  const mail = T.verifyEmail({ user, code, link, meta });
  await sendMail({ to: user.email, subject: mail.subject, html: mail.html, text: mail.text, template: 'verify' });
  return { code, token }; // returnate doar pentru teste
}

async function markVerified(userId) {
  run("UPDATE users SET email_verified_at = COALESCE(email_verified_at, datetime('now')) WHERE id = ?", userId);
  run("UPDATE email_verifications SET used_at = COALESCE(used_at, datetime('now')) WHERE user_id = ?", userId);
  const user = userById(userId);
  const mail = T.welcome({ user });
  await sendMail({ to: user.email, subject: mail.subject, html: mail.html, text: mail.text, template: 'welcome' });
  return user;
}

/** confirmare cu codul de 6 cifre */
export async function verifyCode(userId, code) {
  const v = one("SELECT * FROM email_verifications WHERE user_id = ? AND used_at IS NULL AND expires_at > datetime('now') ORDER BY id DESC LIMIT 1", userId);
  const err = (m, s = 400) => Object.assign(new Error(m), { status: s });
  if (!v) throw err('Codul a expirat. Cere un cod nou.', 410);
  if (v.attempts >= VERIFY_MAX_ATTEMPTS) throw err('Prea multe încercări greșite. Cere un cod nou.', 429);
  const clean6 = String(code || '').replace(/\D/g, '');
  if (!safeEqual(sha256(`${userId}:${clean6}`), v.code_hash)) {
    run('UPDATE email_verifications SET attempts = attempts + 1 WHERE id = ?', v.id);
    const left = VERIFY_MAX_ATTEMPTS - v.attempts - 1;
    throw err(left > 0 ? `Cod incorect. Mai ai ${left} ${left === 1 ? 'încercare' : 'încercări'}.` : 'Prea multe încercări greșite. Cere un cod nou.', left > 0 ? 400 : 429);
  }
  return markVerified(userId);
}

/** confirmare prin link-ul din email */
export async function verifyToken(token) {
  if (!token) return null;
  const v = one("SELECT * FROM email_verifications WHERE token_hash = ? AND used_at IS NULL AND expires_at > datetime('now')", sha256(token));
  if (!v) return null;
  return markVerified(v.user_id);
}

// ─── autentificare ─────────────────────────────────────────────────────────
export async function authenticate(email, password) {
  const user = userByEmail(email);
  const ok = await verifyPassword(String(password || ''), user?.password_hash);
  if (!user || !ok) throw Object.assign(new Error('Email sau parolă incorecte.'), { status: 401 });
  if (user.status === 'blocked') throw Object.assign(new Error('Contul este suspendat. Contactează-ne pentru detalii.'), { status: 403 });
  return user;
}

export function createSession(userId, { ip, ua, remember = true } = {}) {
  const token = randomToken(32);
  const ttl = remember ? `+${SESSION_DAYS} days` : `+${SESSION_SHORT_HOURS} hours`;
  run(`INSERT INTO sessions(id, user_id, expires_at, ip, user_agent) VALUES (?,?, datetime('now', ?), ?, ?)`, sha256(token), userId, ttl, ip || '', (ua || '').slice(0, 300));
  run("UPDATE users SET last_login_at = datetime('now') WHERE id = ?", userId);
  return { token, maxAge: remember ? SESSION_DAYS * 86400 : undefined };
}

export function sessionUser(token) {
  if (!token) return null;
  return one(`SELECT u.*, s.id AS session_id FROM sessions s JOIN users u ON u.id = s.user_id
              WHERE s.id = ? AND s.expires_at > datetime('now') AND u.status = 'active'`, sha256(token));
}

export const destroySession = (token) => token && run('DELETE FROM sessions WHERE id = ?', sha256(token));
export const destroyUserSessions = (userId, exceptSessionId) => run('DELETE FROM sessions WHERE user_id = ? AND id IS NOT ?', userId, exceptSessionId ?? null);
export const pruneSessions = () => run("DELETE FROM sessions WHERE expires_at < datetime('now')");

// ─── resetare parolă ───────────────────────────────────────────────────────
export async function requestPasswordReset(email, meta) {
  const user = userByEmail(email);
  if (!user || user.status !== 'active') return null; // răspuns identic, fără a dezvălui existența contului
  const recent = one("SELECT COUNT(*) n FROM password_resets WHERE user_id = ? AND created_at > datetime('now', '-15 minutes')", user.id).n;
  if (recent >= 3) return null;
  const token = randomToken(32);
  run("INSERT INTO password_resets(user_id, token_hash, expires_at) VALUES (?,?, datetime('now', '+60 minutes'))", user.id, sha256(token));
  const link = `${config.publicUrl}/cont/parola-noua?token=${encodeURIComponent(token)}`;
  const mail = T.passwordReset({ user, link, meta });
  await sendMail({ to: user.email, subject: mail.subject, html: mail.html, text: mail.text, template: 'reset' });
  return token;
}

export const resetTokenValid = (token) => !!token && !!one("SELECT id FROM password_resets WHERE token_hash = ? AND used_at IS NULL AND expires_at > datetime('now')", sha256(token));

export async function resetPassword(token, password) {
  const r = one("SELECT * FROM password_resets WHERE token_hash = ? AND used_at IS NULL AND expires_at > datetime('now')", sha256(token || ''));
  if (!r) throw Object.assign(new Error('Linkul de resetare a expirat. Cere unul nou.'), { status: 410 });
  const pwp = passwordProblem(password);
  if (pwp) throw new ValidationError({ password: pwp });
  const hash = await hashPassword(password);
  tx(() => {
    run('UPDATE users SET password_hash = ? WHERE id = ?', hash, r.user_id);
    run("UPDATE password_resets SET used_at = datetime('now') WHERE user_id = ? AND used_at IS NULL", r.user_id);
    // resetarea prin email confirmă implicit și adresa
    run("UPDATE users SET email_verified_at = COALESCE(email_verified_at, datetime('now')) WHERE id = ?", r.user_id);
    destroyUserSessions(r.user_id);
  });
  return userById(r.user_id);
}

export async function changePassword(user, current, next, keepSessionId) {
  if (!(await verifyPassword(current || '', user.password_hash))) throw new ValidationError({ current_password: 'Parola actuală nu este corectă.' });
  const pwp = passwordProblem(next);
  if (pwp) throw new ValidationError({ new_password: pwp });
  run('UPDATE users SET password_hash = ? WHERE id = ?', await hashPassword(next), user.id);
  destroyUserSessions(user.id, keepSessionId);
}

export function updateProfile(userId, body) {
  const errors = {};
  const first = clean(body.first_name, 60), last = clean(body.last_name, 60);
  const phone = body.phone ? normalizePhone(body.phone) : '';
  if (!first) errors.first_name = 'Completează prenumele.';
  if (!last) errors.last_name = 'Completează numele.';
  if (body.phone && !phone) errors.phone = 'Numărul de telefon nu este valid.';
  if (Object.keys(errors).length) throw new ValidationError(errors);
  const marketing = body.marketing === 'on' || body.marketing === true || body.marketing === '1';
  run('UPDATE users SET first_name = ?, last_name = ?, phone = ?, marketing_opt_in = ? WHERE id = ?', first, last, phone || '', marketing ? 1 : 0, userId);
  const u = userById(userId);
  if (marketing) run('INSERT OR IGNORE INTO newsletter(email) VALUES (?)', u.email);
  else run('DELETE FROM newsletter WHERE email = ?', u.email);
  return u;
}

// ─── adrese salvate ────────────────────────────────────────────────────────
export const addresses = (userId) => all('SELECT * FROM addresses WHERE user_id = ? ORDER BY is_default DESC, id DESC', userId);

export function saveAddress(userId, a, { makeDefault = false, label = '' } = {}) {
  const exists = one('SELECT id FROM addresses WHERE user_id = ? AND street = ? AND city = ? AND county = ?', userId, a.street, a.city, a.county);
  if (exists) return exists.id;
  const isFirst = !one('SELECT id FROM addresses WHERE user_id = ?', userId);
  if (makeDefault) run('UPDATE addresses SET is_default = 0 WHERE user_id = ?', userId);
  const r = run(`INSERT INTO addresses(user_id, label, first_name, last_name, phone, county, city, street, postcode, is_default) VALUES (?,?,?,?,?,?,?,?,?,?)`,
    userId, label, a.first_name, a.last_name, a.phone || '', a.county, a.city, a.street, a.postcode || '', makeDefault || isFirst ? 1 : 0);
  return Number(r.lastInsertRowid);
}

export const deleteAddress = (userId, id) => run('DELETE FROM addresses WHERE id = ? AND user_id = ?', id, userId);
export function setDefaultAddress(userId, id) {
  tx(() => {
    run('UPDATE addresses SET is_default = 0 WHERE user_id = ?', userId);
    run('UPDATE addresses SET is_default = 1 WHERE id = ? AND user_id = ?', id, userId);
  });
}

// ─── favorite ──────────────────────────────────────────────────────────────
export const favoriteIds = (userId) => all('SELECT product_id FROM favorites WHERE user_id = ? ORDER BY created_at DESC', userId).map((r) => r.product_id);
export function toggleFavorite(userId, productId) {
  const r = run('DELETE FROM favorites WHERE user_id = ? AND product_id = ?', userId, productId);
  if (r.changes) return false;
  run('INSERT INTO favorites(user_id, product_id) VALUES (?, ?)', userId, productId);
  return true;
}
