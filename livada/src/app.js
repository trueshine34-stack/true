import { config } from './config.js';
import { Router, runHandlers } from './lib/router.js';
import { HttpError, parseCookies, serializeCookie, parseBody, sendBuffer, clientIp } from './lib/http.js';
import { securityHeaders, csrfFor, randomToken, safeEqual, signJson, verifyJson } from './lib/security.js';
import { serveStatic, importMap } from './lib/static.js';
import { sessionUser } from './services/users.js';
import { cartIdFromToken, cartCount } from './services/cart.js';
import { ValidationError } from './lib/validate.js';
import { Raw } from './lib/html.js';

import storeRoutes from './routes/store.js';
import accountRoutes from './routes/account.js';
import apiRoutes from './routes/api.js';
import adminRoutes from './routes/admin.js';
import devRoutes from './routes/dev.js';
import { errorPage } from './views/store/errors.js';

// antetele depind de versiunea app.js (hash-ul import map-ului din CSP) → recalculate doar când aceasta se schimbă
let SEC = null;
let secFor = '';
function sec() {
  const { hash } = importMap();
  if (hash !== secFor) { SEC = securityHeaders({ scriptHashes: [hash] }); secFor = hash; }
  return SEC;
}
const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export class Ctx {
  constructor(req, res) {
    this.req = req;
    this.res = res;
    this.url = new URL(req.url, 'http://localhost');
    this.path = this.url.pathname.replace(/\/{2,}/g, '/');
    this.query = this.url.searchParams;
    this.method = req.method;
    this.cookies = parseCookies(req.headers.cookie);
    this.ip = clientIp(req);
    this.outCookies = [];
    this.params = {};
    this.body = {};
    this.files = [];
    this.state = {};
    this.sent = false;
    this.isApi = this.path.startsWith('/api/');
  }

  setCookie(name, value, opts) { this.outCookies.push(serializeCookie(name, value, opts)); }
  clearCookie(name) { this.outCookies.push(serializeCookie(name, '', { maxAge: 0 })); }

  headers(extra = {}) {
    const h = { ...sec(), ...extra };
    if (this.outCookies.length) h['Set-Cookie'] = this.outCookies;
    return h;
  }

  html(body, status = 200, extra = {}) {
    this.sent = true;
    const s = body instanceof Raw ? body.s : String(body);
    sendBuffer(this.req, this.res, status, 'text/html; charset=utf-8', s, this.headers({ 'Cache-Control': 'no-store', ...extra }));
  }

  json(obj, status = 200, extra = {}) {
    this.sent = true;
    sendBuffer(this.req, this.res, status, 'application/json; charset=utf-8', JSON.stringify(obj), this.headers({ 'Cache-Control': 'no-store', ...extra }));
  }

  send(type, body, status = 200, extra = {}) {
    this.sent = true;
    sendBuffer(this.req, this.res, status, type, body, this.headers(extra));
  }

  redirect(location, status = 303) {
    this.sent = true;
    this.res.writeHead(status, this.headers({ Location: location, 'Cache-Control': 'no-store' }));
    this.res.end();
  }

  /** mesaj „flash” afișat la următoarea pagină */
  flash(type, message) { this.setCookie('lv_flash', signJson({ type, message }, 'flash'), { maxAge: 60 }); }

  get wantsJson() { return this.isApi || (this.req.headers.accept || '').includes('application/json'); }
}

function setupVisitor(ctx) {
  let vid = ctx.cookies.lv_vid;
  if (!vid || !/^[\w-]{20,64}$/.test(vid)) {
    vid = randomToken(18);
    ctx.setCookie('lv_vid', vid, { maxAge: 365 * 86400 });
  }
  ctx.visitorId = vid;
  ctx.csrf = csrfFor(vid);
  const flash = ctx.cookies.lv_flash && verifyJson(ctx.cookies.lv_flash, 'flash');
  if (flash) { ctx.flashMessage = flash; ctx.clearCookie('lv_flash'); }
}

function loadSession(ctx) {
  ctx.sessionToken = ctx.cookies.lv_sid || null;
  const u = sessionUser(ctx.sessionToken);
  if (ctx.sessionToken && !u) ctx.clearCookie('lv_sid');
  ctx.user = u || null;
  ctx.cartToken = ctx.cookies.lv_cart && /^[\w-]{20,64}$/.test(ctx.cookies.lv_cart) ? ctx.cookies.lv_cart : null;
  ctx.cartId = ctx.user ? `user:${ctx.user.id}` : cartIdFromToken(ctx.cartToken);
  ctx.cartCount = () => (ctx._cc ??= cartCount(ctx.cartId));
}

/** asigură un coș (anonim) — creează cookie-ul la primul produs adăugat */
export function ensureCartId(ctx) {
  if (ctx.cartId) return ctx.cartId;
  ctx.cartToken = randomToken(24);
  ctx.setCookie('lv_cart', ctx.cartToken, { maxAge: 60 * 86400 });
  ctx.cartId = cartIdFromToken(ctx.cartToken);
  return ctx.cartId;
}

function checkCsrf(ctx) {
  // protecție dublă: token HMAC legat de vizitator + verificarea Origin
  const origin = ctx.req.headers.origin;
  if (origin) {
    const host = ctx.req.headers['x-forwarded-host'] || ctx.req.headers.host;
    let ok = false;
    try { ok = new URL(origin).host === host || origin === config.publicUrl; } catch { ok = false; }
    if (!ok) throw new HttpError(403, 'Cerere respinsă (origine necunoscută).');
  }
  const token = ctx.req.headers['x-csrf-token'] || ctx.body._csrf;
  if (!safeEqual(token, ctx.csrf)) throw new HttpError(403, 'Sesiunea formularului a expirat. Reîncarcă pagina și încearcă din nou.');
}

export function createApp() {
  const router = new Router();
  apiRoutes(router);
  accountRoutes(router);
  adminRoutes(router);
  if (config.devTools) devRoutes(router);
  storeRoutes(router); // ultimele: conțin rutele generice de catalog

  return async function handle(req, res) {
    const ctx = new Ctx(req, res);
    try {
      if (serveStatic(req, res, ctx.path, ctx.query)) return;
      setupVisitor(ctx);
      loadSession(ctx);
      const m = router.match(ctx.method, ctx.path);
      if (UNSAFE.has(ctx.method)) {
        const { body, files } = await parseBody(req);
        ctx.body = body || {};
        ctx.files = files || [];
        if (m && !m.methodNotAllowed) checkCsrf(ctx);
      }
      if (!m) throw new HttpError(404, 'Pagina nu există.');
      if (m.methodNotAllowed) throw new HttpError(405, 'Metodă nepermisă.');
      ctx.params = m.params;
      await runHandlers(m.handlers, ctx);
      if (!ctx.sent && !res.writableEnded) throw new HttpError(404, 'Pagina nu există.');
    } catch (err) {
      handleError(ctx, err);
    }
  };
}

function handleError(ctx, err) {
  if (ctx.res.headersSent) { ctx.res.end(); return; }
  const status = err.status || (err instanceof ValidationError ? 422 : 500);
  if (status >= 500) console.error(`[livada] ${ctx.method} ${ctx.path}`, err);
  const message = status >= 500 && config.production ? 'A apărut o eroare. Încearcă din nou în câteva momente.' : err.message;
  if (ctx.wantsJson) {
    ctx.json({ error: message, errors: err.errors || undefined }, status, err.retryAfter ? { 'Retry-After': String(err.retryAfter) } : {});
    return;
  }
  try {
    ctx.html(errorPage(ctx, status, message), status);
  } catch (e) {
    console.error(e);
    ctx.res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' });
    ctx.res.end(message);
  }
}
