import { HttpError } from '../lib/http.js';
import { all, one } from '../db/index.js';
import { done, fail, limit, requireUser, requireVerified, safeNext } from './helpers.js';
import { ValidationError, address as parseAddress, normalizePhone, clean } from '../lib/validate.js';
import * as users from '../services/users.js';
import * as orders from '../services/orders.js';
import * as cartSvc from '../services/cart.js';
import { listProducts } from '../services/catalog.js';
import { t } from '../lib/i18n.js';
import { loginPage, registerPage, verifyPage, forgotPage, resetPage } from '../views/store/auth.js';
import { dashboardPage, ordersPage, accountOrderPage, favoritesPage, addressesPage, profilePage } from '../views/store/account.js';

/** autentifică utilizatorul: sesiune nouă + mutarea coșului anonim în contul lui */
export function signIn(ctx, user, { remember = true } = {}) {
  if (ctx.sessionToken) users.destroySession(ctx.sessionToken);
  const { token, maxAge } = users.createSession(user.id, { ip: ctx.ip, ua: ctx.req.headers['user-agent'], remember });
  ctx.setCookie('lv_sid', token, { maxAge });
  users.setUserLang(user.id);
  const anon = cartSvc.cartIdFromToken(ctx.cookies.lv_cart);
  if (anon) { cartSvc.mergeCarts(anon, `user:${user.id}`, user.id); ctx.clearCookie('lv_cart'); }
  ctx.user = user;
}

function signOut(ctx) {
  users.destroySession(ctx.sessionToken);
  ctx.clearCookie('lv_sid');
  ctx.user = null;
}

const cooldownLeft = (userId) => {
  const v = users.lastVerification(userId);
  if (!v || v.used_at) return 0;
  const age = (Date.now() - Date.parse(`${v.created_at.replace(' ', 'T')}Z`)) / 1000;
  return Math.max(0, Math.ceil(users.RESEND_COOLDOWN_S - age));
};

export default function accountRoutes(r) {
  // ─── autentificare ─────────────────────────────────────────────────────
  r.get('/cont/autentificare', (ctx) => {
    if (ctx.user) return ctx.redirect(safeNext(ctx.query.get('next')));
    ctx.html(loginPage(ctx, { next: safeNext(ctx.query.get('next'), '') }));
  });

  r.post('/cont/autentificare', async (ctx) => {
    limit(ctx, 'login', 10, 10 * 60_000);
    limit(ctx, 'login-email', 8, 10 * 60_000, clean(ctx.body.email, 190).toLowerCase());
    let user;
    try {
      user = await users.authenticate(ctx.body.email, ctx.body.password);
    } catch (e) {
      if (ctx.wantsJson) throw e;
      return ctx.html(loginPage(ctx, { next: safeNext(ctx.body.next, ''), email: ctx.body.email, error: e.message }), e.status || 401);
    }
    signIn(ctx, user, { remember: ctx.body.remember === 'on' || ctx.body.remember === true });
    const next = !user.email_verified_at ? '/cont/verificare' : safeNext(ctx.body.next, user.role === 'customer' ? '/cont' : '/cont');
    return done(ctx, { redirect: next, message: t('Bine ai revenit, {name}!', { name: user.first_name }) });
  });

  r.post('/cont/iesire', (ctx) => {
    signOut(ctx);
    return done(ctx, { redirect: '/', message: t('Te-ai deconectat.') });
  });

  r.get('/cont/iesire-si-inregistrare', (ctx) => {
    // link din pagina de verificare („alt email”) — deconectează doar conturile neconfirmate
    if (ctx.user && !ctx.user.email_verified_at) signOut(ctx);
    ctx.redirect('/cont/inregistrare');
  });

  // ─── înregistrare + confirmare ─────────────────────────────────────────
  r.get('/cont/inregistrare', (ctx) => {
    if (ctx.user) return ctx.redirect('/cont');
    ctx.html(registerPage(ctx, { next: safeNext(ctx.query.get('next'), '') }));
  });

  r.post('/cont/inregistrare', async (ctx) => {
    limit(ctx, 'register', 5, 30 * 60_000);
    let user;
    try {
      user = await users.register(ctx.body, users.requestMeta(ctx));
    } catch (e) { return fail(ctx, e, '/cont/inregistrare'); }
    signIn(ctx, user, { remember: true });
    return done(ctx, { redirect: '/cont/verificare', message: t('Contul a fost creat. Verifică-ți emailul.') });
  });

  r.get('/cont/verificare', requireUser, (ctx) => {
    if (ctx.user.email_verified_at) return ctx.redirect('/cont');
    ctx.html(verifyPage(ctx, { email: ctx.user.email, cooldown: cooldownLeft(ctx.user.id), expired: ctx.query.get('expirat') === '1' }));
  });

  r.post('/cont/verificare', requireUser, async (ctx) => {
    limit(ctx, 'verify', 20, 10 * 60_000, ctx.user.id);
    if (ctx.user.email_verified_at) return done(ctx, { redirect: '/cont' });
    const code = String(ctx.body.code || '').replace(/\D/g, '');
    if (code.length !== 6) {
      const e = new ValidationError({ code: t('Introdu toate cele 6 cifre.') });
      if (ctx.wantsJson) throw e;
      return ctx.html(verifyPage(ctx, { email: ctx.user.email, error: e.message }), 422);
    }
    try {
      await users.verifyCode(ctx.user.id, code);
    } catch (e) {
      if (ctx.wantsJson) throw new ValidationError({ code: e.message });
      return ctx.html(verifyPage(ctx, { email: ctx.user.email, error: e.message, cooldown: cooldownLeft(ctx.user.id) }), e.status || 400);
    }
    return done(ctx, { redirect: '/cont?bun-venit=1', message: t('Adresa de email a fost confirmată. Bun venit!') });
  });

  r.post('/cont/verificare/retrimite', requireUser, async (ctx) => {
    limit(ctx, 'resend', 5, 30 * 60_000, ctx.user.id);
    if (ctx.user.email_verified_at) return done(ctx, { redirect: '/cont' });
    try {
      await users.sendVerification(ctx.user, users.requestMeta(ctx));
    } catch (e) { return fail(ctx, e, '/cont/verificare'); }
    return done(ctx, { redirect: '/cont/verificare', message: t('Ți-am trimis un cod nou la {email}.', { email: ctx.user.email }), data: { cooldown: users.RESEND_COOLDOWN_S } });
  });

  // link-ul din email: funcționează și fără sesiune (alt dispozitiv)
  r.get('/cont/confirma', async (ctx) => {
    limit(ctx, 'verify-link', 30, 10 * 60_000);
    const user = await users.verifyToken(ctx.query.get('token'));
    if (!user) {
      if (ctx.user && !ctx.user.email_verified_at) return ctx.redirect('/cont/verificare?expirat=1');
      ctx.flash('error', t('Linkul de confirmare a expirat sau a fost deja folosit.'));
      return ctx.redirect(ctx.user ? '/cont' : '/cont/autentificare');
    }
    if (!ctx.user || ctx.user.id !== user.id) signIn(ctx, user, { remember: true });
    ctx.flash('success', t('Adresa de email a fost confirmată. Bun venit!'));
    return ctx.redirect('/cont?bun-venit=1');
  });

  // ─── parolă uitată ─────────────────────────────────────────────────────
  r.get('/cont/parola-uitata', (ctx) => ctx.html(forgotPage(ctx)));
  r.post('/cont/parola-uitata', async (ctx) => {
    limit(ctx, 'forgot', 5, 30 * 60_000);
    const email = clean(ctx.body.email, 190);
    await users.requestPasswordReset(email, users.requestMeta(ctx));
    if (ctx.wantsJson) return ctx.json({ ok: true, replace: forgotPage(ctx, { sent: true, email }).s.match(/<div class="auth__card">([\s\S]*?)<\/div>\s*<aside/)?.[1] });
    return ctx.html(forgotPage(ctx, { sent: true, email }));
  });

  r.get('/cont/parola-noua', (ctx) => {
    const token = ctx.query.get('token') || '';
    ctx.html(resetPage(ctx, { token, valid: users.resetTokenValid(token) }));
  });
  r.post('/cont/parola-noua', async (ctx) => {
    limit(ctx, 'reset', 10, 30 * 60_000);
    let user;
    try { user = await users.resetPassword(ctx.body.token, ctx.body.password); } catch (e) { return fail(ctx, e, `/cont/parola-noua?token=${encodeURIComponent(ctx.body.token || '')}`); }
    signIn(ctx, user, { remember: true });
    return done(ctx, { redirect: '/cont', message: t('Parola a fost schimbată.') });
  });

  // ─── contul meu ────────────────────────────────────────────────────────
  const withThumbs = (list) => list.map((o) => ({ ...o, thumbs: all('SELECT image FROM order_items WHERE order_id = ? ORDER BY id LIMIT 3', o.id).map((x) => x.image).filter(Boolean) }));

  r.get('/cont', requireVerified, (ctx) => {
    const list = withThumbs(orders.ordersForUser(ctx.user.id));
    if (list[0]) list[0].events = orders.orderEvents(list[0].id);
    ctx.html(dashboardPage(ctx, { orders: list, favoritesCount: users.favoriteIds(ctx.user.id).length, addressesCount: users.addresses(ctx.user.id).length }));
  });

  r.get('/cont/comenzi', requireVerified, (ctx) => ctx.html(ordersPage(ctx, withThumbs(orders.ordersForUser(ctx.user.id)))));

  r.get('/cont/comenzi/:number', requireVerified, (ctx) => {
    const o = orders.orderByNumber(ctx.params.number);
    if (!o || o.user_id !== ctx.user.id) throw new HttpError(404, t('Comanda nu există.'));
    ctx.html(accountOrderPage(ctx, o, orders.orderItems(o.id), orders.orderEvents(o.id)));
  });

  r.get('/cont/favorite', requireUser, (ctx) => {
    const ids = users.favoriteIds(ctx.user.id);
    ctx.html(favoritesPage(ctx, ids.length ? listProducts({ ids, sort: 'ids' }) : []));
  });

  r.get('/cont/adrese', requireVerified, (ctx) => ctx.html(addressesPage(ctx, users.addresses(ctx.user.id))));
  r.post('/cont/adrese', requireVerified, (ctx) => {
    const errors = {};
    const a = parseAddress(ctx.body, '', errors);
    const phone = normalizePhone(ctx.body.phone);
    if (!phone) errors.phone = t('Numărul de telefon nu este valid.');
    if (Object.keys(errors).length) return ctx.html(addressesPage(ctx, users.addresses(ctx.user.id), { errors, values: ctx.body }), 422);
    users.saveAddress(ctx.user.id, { ...a, phone }, { makeDefault: ctx.body.is_default === 'on' });
    return done(ctx, { redirect: '/cont/adrese', message: t('Adresa a fost salvată.') });
  });
  r.post('/cont/adrese/:id/sterge', requireVerified, (ctx) => { users.deleteAddress(ctx.user.id, Number(ctx.params.id)); return done(ctx, { redirect: '/cont/adrese', message: t('Adresa a fost ștearsă.') }); });
  r.post('/cont/adrese/:id/implicita', requireVerified, (ctx) => { users.setDefaultAddress(ctx.user.id, Number(ctx.params.id)); return done(ctx, { redirect: '/cont/adrese' }); });

  r.get('/cont/profil', requireVerified, (ctx) => ctx.html(profilePage(ctx)));
  r.post('/cont/profil', requireVerified, (ctx) => {
    try { ctx.user = { ...ctx.user, ...users.updateProfile(ctx.user.id, ctx.body) }; } catch (e) {
      if (e instanceof ValidationError) return ctx.html(profilePage(ctx, { errors: e.errors }), 422);
      throw e;
    }
    return done(ctx, { redirect: '/cont/profil', message: t('Datele au fost salvate.') });
  });
  r.post('/cont/parola', requireVerified, async (ctx) => {
    limit(ctx, 'change-pw', 10, 30 * 60_000, ctx.user.id);
    try { await users.changePassword(ctx.user, ctx.body.current_password, ctx.body.new_password, ctx.user.session_id); } catch (e) {
      if (e instanceof ValidationError) return ctx.html(profilePage(ctx, { errors: e.errors }), 422);
      throw e;
    }
    return done(ctx, { redirect: '/cont/profil', message: t('Parola a fost actualizată. Celelalte sesiuni au fost închise.') });
  });

  r.get('/cont/export', requireVerified, (ctx) => {
    const u = ctx.user;
    const data = {
      generat: new Date().toISOString(), cont: users.publicUser(u), creat: u.created_at,
      adrese: users.addresses(u.id), favorite: users.favoriteIds(u.id),
      comenzi: orders.ordersForUser(u.id).map((o) => ({ numar: o.number, data: o.created_at, status: o.status, total_lei: o.total / 100, livrare: o.delivery, produse: orders.orderItems(o.id).map((i) => ({ nume: i.name, varianta: i.variant_label, cantitate: i.qty, pret_lei: i.unit_price / 100 })) })),
      newsletter: !!one('SELECT 1 FROM newsletter WHERE email = ?', u.email),
    };
    ctx.send('application/json; charset=utf-8', JSON.stringify(data, null, 2), 200, { 'Content-Disposition': `attachment; filename="${t('livada-datele-mele.json')}"`, 'Cache-Control': 'no-store' });
  });
}
