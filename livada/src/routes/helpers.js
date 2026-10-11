import { HttpError } from '../lib/http.js';
import { rateLimit } from '../lib/security.js';
import { ValidationError } from '../lib/validate.js';

/** răspuns pentru formulare: JSON pentru fetch, redirect + flash pentru formularele clasice */
export function done(ctx, { redirect, message, type = 'success', data = {} } = {}) {
  if (ctx.wantsJson) return ctx.json({ ok: true, redirect, message, ...data });
  if (message) ctx.flash(type, message);
  return ctx.redirect(redirect || ctx.req.headers.referer || '/');
}

/** eroare de formular: JSON cu erorile pe câmpuri sau redirect înapoi cu mesaj */
export function fail(ctx, err, back) {
  if (ctx.wantsJson) throw err;
  ctx.flash('error', err instanceof ValidationError ? Object.values(err.errors)[0] : err.message);
  return ctx.redirect(back || ctx.req.headers.referer || '/');
}

export function limit(ctx, bucket, max, windowMs, key = ctx.ip) {
  const r = rateLimit(`${bucket}:${key}`, max, windowMs);
  if (!r.ok) {
    const e = new HttpError(429, `Prea multe încercări. Încearcă din nou în ${r.retryAfter < 120 ? `${r.retryAfter} secunde` : `${Math.ceil(r.retryAfter / 60)} minute`}.`);
    e.retryAfter = r.retryAfter;
    throw e;
  }
}

/** gărzi */
export const requireUser = (ctx) => {
  if (!ctx.user) {
    if (ctx.wantsJson) throw new HttpError(401, 'Autentifică-te pentru a continua.');
    ctx.redirect(`/cont/autentificare?next=${encodeURIComponent(ctx.path + ctx.url.search)}`);
    return false;
  }
  return true;
};

export const requireVerified = (ctx) => {
  if (!requireUser(ctx)) return false;
  if (!ctx.user.email_verified_at) {
    if (ctx.wantsJson) throw new HttpError(403, 'Confirmă-ți adresa de email.');
    ctx.redirect('/cont/verificare');
    return false;
  }
  return true;
};

export const requireStaff = (ctx) => {
  if (!ctx.user) { ctx.redirect(`/admin/autentificare?next=${encodeURIComponent(ctx.path)}`); return false; }
  if (!['admin', 'manager'].includes(ctx.user.role)) throw new HttpError(403, 'Nu ai acces la panoul de administrare.');
  return true;
};

export const requireAdmin = (ctx) => {
  if (!requireStaff(ctx)) return false;
  if (ctx.user.role !== 'admin') throw new HttpError(403, 'Doar administratorii pot face această acțiune.');
  return true;
};

/** redirect sigur (doar căi locale) */
export const safeNext = (n, fallback = '/cont') => (typeof n === 'string' && /^\/(?!\/)[\w\-./?=&%#]*$/.test(n) ? n : fallback);
