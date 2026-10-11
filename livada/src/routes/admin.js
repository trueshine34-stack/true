import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from '../config.js';
import { HttpError } from '../lib/http.js';
import { one, all, run, tx, json, getSetting, setSetting, audit } from '../db/index.js';
import { requireStaff, requireAdmin, limit, safeNext } from './helpers.js';
import { clean, cleanText, isEmail } from '../lib/validate.js';
import { toBani, slugify } from '../lib/format.js';
import { sendMail } from '../lib/mailer.js';
import * as T from '../services/mail-templates.js';
import * as orders from '../services/orders.js';
import * as users from '../services/users.js';
import { listProducts, productById, hydrate, categories, imageFor } from '../services/catalog.js';
import { allMethods, lockerStats, invalidateLockers } from '../services/shipping.js';
import { dashboard, customerStats } from '../services/stats.js';
import { rebuildSearchIndex } from '../db/seed.js';
import { invalidateNav } from '../views/layout.js';
import { translator } from '../views/admin/i18n.js';
import { adminLogin } from '../views/admin/layout.js';
import * as V from '../views/admin/pages.js';
import { signIn } from './account.js';

const tr = (ctx) => translator(ctx.user?.admin_lang || ctx.cookies.lv_lang || 'ro');
const log = (ctx, action, entity, id, data) => audit(ctx.user?.id, action, entity, id, data, ctx.ip);
const saved = (ctx, to) => { ctx.flash('success', tr(ctx)('saved')); ctx.redirect(to); };

function csvCell(v) {
  const s = String(v ?? '');
  return /[",;\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export default function adminRoutes(r) {
  // ─── autentificare echipă ───────────────────────────────────────────────
  r.get('/admin/autentificare', (ctx) => {
    if (ctx.user && ctx.user.role !== 'customer') return ctx.redirect('/admin');
    ctx.html(adminLogin(ctx, tr(ctx), { next: safeNext(ctx.query.get('next'), '/admin') }));
  });
  r.post('/admin/autentificare', async (ctx) => {
    limit(ctx, 'admin-login', 8, 15 * 60_000);
    const t = tr(ctx);
    let user;
    try {
      user = await users.authenticate(ctx.body.email, ctx.body.password);
      if (user.role === 'customer') throw Object.assign(new Error('Contul nu are acces la panou.'), { status: 403 });
    } catch (e) {
      return ctx.html(adminLogin(ctx, t, { error: e.message, email: ctx.body.email, next: safeNext(ctx.body.next, '/admin') }), e.status || 401);
    }
    signIn(ctx, user, { remember: false });
    audit(user.id, 'admin.login', 'user', user.id, null, ctx.ip);
    ctx.redirect(safeNext(ctx.body.next, '/admin'));
  });

  r.post('/admin/limba', requireStaff, (ctx) => {
    const lang = ctx.body.lang === 'ru' ? 'ru' : 'ro';
    run('UPDATE users SET admin_lang = ? WHERE id = ?', lang, ctx.user.id);
    ctx.redirect(ctx.req.headers.referer?.includes('/admin') ? new URL(ctx.req.headers.referer).pathname : '/admin');
  });

  // ─── panou ─────────────────────────────────────────────────────────────
  r.get('/admin', requireStaff, (ctx) => ctx.html(V.dashboardPage(ctx, tr(ctx), dashboard())));

  // ─── comenzi ───────────────────────────────────────────────────────────
  const orderFilters = (q) => ({ q: clean(q.get('q'), 80), status: clean(q.get('status'), 20), payment: clean(q.get('payment'), 20), delivery: clean(q.get('delivery'), 20) });

  r.get('/admin/comenzi', requireStaff, (ctx) => {
    const filters = orderFilters(ctx.query);
    const offset = Math.max(0, Number(ctx.query.get('o')) || 0);
    const limitN = 30;
    const { total, rows } = orders.listOrders({ ...filters, limit: limitN, offset });
    // căutare exactă după numărul comenzii → direct la comandă
    if (filters.q && total === 1 && rows[0].number.toLowerCase() === filters.q.toLowerCase()) return ctx.redirect(`/admin/comenzi/${rows[0].id}`);
    ctx.html(V.ordersPage(ctx, tr(ctx), { rows, total, filters, limit: limitN, offset }));
  });

  r.get('/admin/comenzi.csv', requireStaff, (ctx) => {
    const { rows } = orders.listOrders({ ...orderFilters(ctx.query), limit: 100000 });
    const head = ['Număr', 'Data', 'Client', 'Email', 'Telefon', 'Status', 'Plată', 'Status plată', 'Livrare', 'Subtotal', 'Reducere', 'Transport', 'Ramburs', 'Total', 'TVA', 'Cod promo', 'AWB'];
    const lines = rows.map((o) => [o.number, o.created_at, `${o.first_name} ${o.last_name}`, o.email, o.phone, o.status, o.payment_method, o.payment_status, o.delivery_method,
      (o.subtotal / 100).toFixed(2), (o.discount / 100).toFixed(2), (o.shipping_fee / 100).toFixed(2), (o.cod_fee / 100).toFixed(2), (o.total / 100).toFixed(2), (o.vat / 100).toFixed(2), o.promo_code || '', o.awb || '']
      .map(csvCell).join(';'));
    log(ctx, 'orders.export', 'orders', null, { count: rows.length });
    ctx.send('text/csv; charset=utf-8', `﻿${head.join(';')}\n${lines.join('\n')}\n`, 200, { 'Content-Disposition': `attachment; filename="comenzi-livada-${new Date().toISOString().slice(0, 10)}.csv"`, 'Cache-Control': 'no-store' });
  });

  const loadOrder = (ctx) => {
    const o = orders.orderById(Number(ctx.params.id));
    if (!o) throw new HttpError(404, 'Comanda nu există.');
    return o;
  };

  r.get('/admin/comenzi/:id', requireStaff, (ctx) => {
    const o = loadOrder(ctx);
    const customerOrders = o.user_id ? one('SELECT COUNT(*) n FROM orders WHERE user_id = ?', o.user_id).n : 0;
    ctx.html(V.orderPage(ctx, tr(ctx), { order: o, items: orders.orderItems(o.id), events: orders.orderEvents(o.id), customerOrders }));
  });

  r.post('/admin/comenzi/:id/status', requireStaff, async (ctx) => {
    const o = loadOrder(ctx);
    const status = clean(ctx.body.status, 20);
    try {
      await orders.setStatus(o.id, status, { note: ctx.body.note, awb: ctx.body.awb, actor: `admin:${ctx.user.email}`, notify: ctx.body.notify === '1' });
    } catch (e) { ctx.flash('error', e.message); return ctx.redirect(`/admin/comenzi/${o.id}`); }
    log(ctx, 'order.status', 'order', o.id, { from: o.status, to: status, awb: ctx.body.awb || undefined });
    saved(ctx, `/admin/comenzi/${o.id}`);
  });

  r.post('/admin/comenzi/:id/plata', requireStaff, (ctx) => {
    const o = loadOrder(ctx);
    orders.setPaymentStatus(o.id, clean(ctx.body.status, 20), `admin:${ctx.user.email}`);
    if (o.status === 'new') orders.setStatus(o.id, 'confirmed', { note: 'Plată confirmată', actor: `admin:${ctx.user.email}`, notify: true });
    log(ctx, 'order.payment', 'order', o.id, { status: ctx.body.status });
    saved(ctx, `/admin/comenzi/${o.id}`);
  });

  r.post('/admin/comenzi/:id/note', requireStaff, (ctx) => {
    const o = loadOrder(ctx);
    run("UPDATE orders SET admin_notes = ?, updated_at = datetime('now') WHERE id = ?", cleanText(ctx.body.admin_notes, 2000), o.id);
    saved(ctx, `/admin/comenzi/${o.id}`);
  });

  r.get('/admin/comenzi/:id/factura', requireStaff, (ctx) => {
    const o = loadOrder(ctx);
    ctx.html(V.invoicePage(ctx, { order: o, items: orders.orderItems(o.id), company: getSetting('company') }));
  });

  // ─── produse ───────────────────────────────────────────────────────────
  r.get('/admin/produse', requireStaff, (ctx) => {
    const q = clean(ctx.query.get('q'), 60).toLowerCase();
    const cat = clean(ctx.query.get('cat'), 30);
    const stoc = ctx.query.get('stoc');
    let rows = all(`SELECT p.*, c.slug cat, c.name category_name,
        (SELECT MIN(price) FROM variants v WHERE v.product_id = p.id) price_from, (SELECT MAX(price) FROM variants v WHERE v.product_id = p.id) price_to,
        (SELECT COALESCE(SUM(stock), 0) FROM variants v WHERE v.product_id = p.id AND v.active = 1) stock_total,
        (SELECT COUNT(*) FROM variants v WHERE v.product_id = p.id) variant_count,
        (SELECT group_concat(sku || ' ' || COALESCE(mpn, ''), ' ') FROM variants v WHERE v.product_id = p.id) codes
      FROM products p JOIN categories c ON c.id = p.category_id ORDER BY c.sort, p.sort`).map((p) => ({ ...p, image: imageFor(hydrate(p), {}, '480') }));
    if (q) rows = rows.filter((p) => `${p.name} ${p.codes} ${p.grp}`.toLowerCase().includes(q));
    if (cat) rows = rows.filter((p) => p.cat === cat);
    if (stoc === 'low') rows = rows.filter((p) => !p.preorder && p.stock_total <= 3);
    if (stoc === 'out') rows = rows.filter((p) => !p.preorder && p.stock_total <= 0);
    ctx.html(V.productsPage(ctx, tr(ctx), { rows, categories: categories(), filters: { q, cat, stoc } }));
  });

  r.get('/admin/produse/nou', requireStaff, (ctx) => {
    ctx.html(V.productEditPage(ctx, tr(ctx), { p: { options: [], images: {}, category_id: categories().find((c) => c.slug === 'accesorii')?.id }, categories: categories(), isNew: true }));
  });

  function productFields(body) {
    const lines = (v) => String(v || '').split('\n').map((s) => s.trim()).filter(Boolean).slice(0, 20);
    const name = clean(body.name, 140);
    const slug = slugify(body.slug || name);
    if (!name || !slug) throw new HttpError(422, 'Numele și slug-ul sunt obligatorii.');
    return {
      name, slug, category_id: Number(body.category_id), grp: clean(body.grp, 60), tagline: clean(body.tagline, 160),
      description: JSON.stringify(lines(body.description)), highlights: JSON.stringify(lines(body.highlights)),
      badge: clean(body.badge, 30) || null, release_note: clean(body.release_note, 120) || null,
      active: body.active === '1' ? 1 : 0, featured: body.featured === '1' ? 1 : 0, preorder: body.preorder === '1' ? 1 : 0,
    };
  }
  const afterCatalogChange = () => { rebuildSearchIndex(); invalidateNav(); };

  r.post('/admin/produse', requireStaff, (ctx) => {
    const f = productFields(ctx.body);
    if (one('SELECT id FROM products WHERE slug = ?', f.slug)) { ctx.flash('error', 'Există deja un produs cu acest slug.'); return ctx.redirect('/admin/produse/nou'); }
    const id = tx(() => {
      const r2 = run(`INSERT INTO products(slug, category_id, grp, name, tagline, badge, featured, release_note, description, highlights, preorder, active, options, images, sort)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?, '[]', '{}', 999)`, f.slug, f.category_id, f.grp, f.name, f.tagline, f.badge, f.featured, f.release_note, f.description, f.highlights, f.preorder, f.active);
      const pid = Number(r2.lastInsertRowid);
      run("INSERT INTO variants(product_id, sku, options, price, stock) VALUES (?, ?, '{}', 0, 0)", pid, `LV-${f.slug.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 14)}-${crypto.randomBytes(2).toString('hex').toUpperCase()}`);
      return pid;
    });
    afterCatalogChange();
    log(ctx, 'product.create', 'product', id, { name: f.name });
    ctx.flash('success', 'Produsul a fost creat. Completează prețul și stocul.');
    ctx.redirect(`/admin/produse/${id}#variante`);
  });

  r.get('/admin/produse/:id', requireStaff, (ctx) => {
    const p = productById(Number(ctx.params.id));
    if (!p) throw new HttpError(404, 'Produsul nu există.');
    const raw = one('SELECT options FROM products WHERE id = ?', p.id);
    p.options = json(raw.options, []);
    p.allVariants = all('SELECT * FROM variants WHERE product_id = ? ORDER BY sort, id', p.id).map((v) => ({ ...v, options: json(v.options, {}) }));
    ctx.html(V.productEditPage(ctx, tr(ctx), { p, categories: categories() }));
  });

  r.post('/admin/produse/:id', requireStaff, (ctx) => {
    const id = Number(ctx.params.id);
    if (!one('SELECT id FROM products WHERE id = ?', id)) throw new HttpError(404, 'Produsul nu există.');
    const f = productFields(ctx.body);
    if (one('SELECT id FROM products WHERE slug = ? AND id != ?', f.slug, id)) { ctx.flash('error', 'Slug-ul este folosit de alt produs.'); return ctx.redirect(`/admin/produse/${id}`); }
    run(`UPDATE products SET name=?, slug=?, category_id=?, grp=?, tagline=?, description=?, highlights=?, badge=?, release_note=?, active=?, featured=?, preorder=?, updated_at=datetime('now') WHERE id = ?`,
      f.name, f.slug, f.category_id, f.grp, f.tagline, f.description, f.highlights, f.badge, f.release_note, f.active, f.featured, f.preorder, id);
    afterCatalogChange();
    log(ctx, 'product.update', 'product', id, { name: f.name, active: f.active });
    saved(ctx, `/admin/produse/${id}`);
  });

  r.post('/admin/produse/:id/variante', requireStaff, (ctx) => {
    const id = Number(ctx.params.id);
    const variants = all('SELECT * FROM variants WHERE product_id = ?', id);
    if (!variants.length) throw new HttpError(404, 'Produsul nu există.');
    const pct = Number(String(ctx.body.bulk_pct || '').replace(',', '.')) || 0;
    const changes = [];
    tx(() => {
      for (const v of variants) {
        let price = toBani(ctx.body[`price_${v.id}`] ?? v.price / 100);
        if (!Number.isFinite(price) || price < 0) price = v.price;
        if (pct) price = Math.round((price * (1 + pct / 100)) / 100) * 100 - 1; // păstrăm terminația ,99
        const cmpRaw = String(ctx.body[`compare_${v.id}`] ?? '').trim();
        const compare = cmpRaw ? toBani(cmpRaw) : null;
        const stock = Math.max(0, Math.floor(Number(ctx.body[`stock_${v.id}`] ?? v.stock)) || 0);
        const active = ctx.body[`active_${v.id}`] === '1' ? 1 : 0;
        if (price !== v.price || compare !== v.compare_at || stock !== v.stock || active !== v.active) changes.push({ sku: v.sku, price, stock });
        run('UPDATE variants SET price = ?, compare_at = ?, stock = ?, active = ? WHERE id = ?', price, compare && compare > price ? compare : null, stock, active, v.id);
      }
      run("UPDATE products SET updated_at = datetime('now') WHERE id = ?", id);
    });
    invalidateNav();
    log(ctx, 'product.variants', 'product', id, { changed: changes.length, pct: pct || undefined });
    saved(ctx, `/admin/produse/${id}#variante`);
  });

  r.post('/admin/produse/:id/imagini', requireStaff, (ctx) => {
    const id = Number(ctx.params.id);
    const p = one('SELECT images FROM products WHERE id = ?', id);
    if (!p) throw new HttpError(404, 'Produsul nu există.');
    const f = ctx.files.find((x) => x.field === 'image');
    if (!f) { ctx.flash('error', 'Alege o imagine.'); return ctx.redirect(`/admin/produse/${id}`); }
    // verificăm semnătura fișierului, nu doar extensia
    const sig = f.data.subarray(0, 12);
    const ext = sig.subarray(0, 4).toString('hex') === '89504e47' ? 'png' : sig.subarray(0, 3).toString('hex') === 'ffd8ff' ? 'jpg'
      : sig.subarray(0, 4).toString() === 'RIFF' && sig.subarray(8, 12).toString() === 'WEBP' ? 'webp' : null;
    if (!ext || f.data.length > 8 * 1024 * 1024) { ctx.flash('error', 'Format neacceptat (WebP, PNG sau JPG, max. 8 MB).'); return ctx.redirect(`/admin/produse/${id}`); }
    const key = clean(ctx.body.key, 40) || '-';
    const name = `p${id}-${crypto.randomBytes(6).toString('hex')}.${ext}`;
    fs.writeFileSync(path.join(config.uploadDir, name), f.data);
    const images = json(p.images, {});
    (images[key] ||= []).push({ 480: `/uploads/${name}`, 1000: `/uploads/${name}` });
    run("UPDATE products SET images = ?, updated_at = datetime('now') WHERE id = ?", JSON.stringify(images), id);
    invalidateNav();
    log(ctx, 'product.image', 'product', id, { file: name, key });
    saved(ctx, `/admin/produse/${id}`);
  });

  // ─── clienți ───────────────────────────────────────────────────────────
  r.get('/admin/clienti', requireStaff, (ctx) => {
    const q = clean(ctx.query.get('q'), 80);
    const offset = Math.max(0, Number(ctx.query.get('o')) || 0);
    const like = `%${q}%`;
    const where = q ? "WHERE (u.email LIKE ? OR (u.first_name || ' ' || u.last_name) LIKE ? OR u.phone LIKE ?)" : '';
    const params = q ? [like, like, like] : [];
    const total = one(`SELECT COUNT(*) n FROM users u ${where}`, ...params).n;
    const rows = all(`SELECT u.*, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) orders,
      (SELECT COALESCE(SUM(total), 0) FROM orders o WHERE o.user_id = u.id AND o.status NOT IN ('cancelled','returned')) spent
      FROM users u ${where} ORDER BY u.id DESC LIMIT 40 OFFSET ?`, ...params, offset);
    ctx.html(V.customersPage(ctx, tr(ctx), { rows, total, q, limit: 40, offset }));
  });

  r.get('/admin/clienti/:id', requireStaff, (ctx) => {
    const u = users.userById(Number(ctx.params.id));
    if (!u) throw new HttpError(404, 'Clientul nu există.');
    ctx.html(V.customerPage(ctx, tr(ctx), { u, orders: all('SELECT * FROM orders WHERE user_id = ? ORDER BY id DESC', u.id), addresses: users.addresses(u.id), stats: customerStats(u.id) }));
  });

  r.post('/admin/clienti/:id/status', requireAdmin, (ctx) => {
    const u = users.userById(Number(ctx.params.id));
    if (!u || u.role !== 'customer') throw new HttpError(404, 'Clientul nu există.');
    const status = ctx.body.status === 'blocked' ? 'blocked' : 'active';
    run('UPDATE users SET status = ? WHERE id = ?', status, u.id);
    if (status === 'blocked') users.destroyUserSessions(u.id);
    log(ctx, `customer.${status}`, 'user', u.id);
    saved(ctx, `/admin/clienti/${u.id}`);
  });

  r.post('/admin/clienti/:id/confirmare', requireStaff, async (ctx) => {
    const u = users.userById(Number(ctx.params.id));
    if (!u) throw new HttpError(404, 'Clientul nu există.');
    await users.sendVerification(u, { when: new Date().toLocaleString('ro-RO'), device: 'trimis de echipa Livada' }, { force: true });
    log(ctx, 'customer.resend_verification', 'user', u.id);
    ctx.flash('success', `Email de confirmare trimis la ${u.email}.`);
    ctx.redirect(`/admin/clienti/${u.id}`);
  });

  // ─── promoții ──────────────────────────────────────────────────────────
  r.get('/admin/promotii', requireStaff, (ctx) => ctx.html(V.promosPage(ctx, tr(ctx), all('SELECT * FROM promo_codes ORDER BY active DESC, id DESC'))));
  r.post('/admin/promotii', requireStaff, (ctx) => {
    const code = clean(ctx.body.code, 30).toUpperCase();
    if (!/^[A-Z0-9_-]{3,30}$/.test(code)) { ctx.flash('error', 'Cod invalid (3–30 caractere: litere, cifre, - sau _).'); return ctx.redirect('/admin/promotii'); }
    if (one('SELECT id FROM promo_codes WHERE code = ? COLLATE NOCASE', code)) { ctx.flash('error', 'Codul există deja.'); return ctx.redirect('/admin/promotii'); }
    const type = ['percent', 'fixed', 'shipping'].includes(ctx.body.type) ? ctx.body.type : 'percent';
    const num = (v) => Number(String(v || '').replace(',', '.')) || 0;
    const value = type === 'percent' ? Math.min(90, Math.max(1, Math.round(num(ctx.body.value)))) : type === 'fixed' ? toBani(num(ctx.body.value)) : 0;
    const ends = /^\d{4}-\d{2}-\d{2}$/.test(ctx.body.ends_at || '') ? `${ctx.body.ends_at} 21:59:59` : null;
    run(`INSERT INTO promo_codes(code, description, type, value, min_subtotal, max_discount, max_uses, ends_at, first_order_only) VALUES (?,?,?,?,?,?,?,?,?)`,
      code, clean(ctx.body.description, 140), type, value, toBani(num(ctx.body.min_subtotal)), ctx.body.max_discount ? toBani(num(ctx.body.max_discount)) : null,
      ctx.body.max_uses ? Math.max(1, Math.floor(num(ctx.body.max_uses))) : null, ends, ctx.body.first_order_only ? 1 : 0);
    log(ctx, 'promo.create', 'promo', code, { type, value });
    saved(ctx, '/admin/promotii');
  });
  r.post('/admin/promotii/:id/toggle', requireStaff, (ctx) => { run('UPDATE promo_codes SET active = 1 - active WHERE id = ?', Number(ctx.params.id)); log(ctx, 'promo.toggle', 'promo', ctx.params.id); ctx.redirect('/admin/promotii'); });
  r.post('/admin/promotii/:id/sterge', requireAdmin, (ctx) => { run('DELETE FROM promo_codes WHERE id = ?', Number(ctx.params.id)); log(ctx, 'promo.delete', 'promo', ctx.params.id); saved(ctx, '/admin/promotii'); });

  // ─── livrare ───────────────────────────────────────────────────────────
  r.get('/admin/livrare', requireStaff, (ctx) => ctx.html(V.deliveryPage(ctx, tr(ctx), { methods: allMethods(), stats: lockerStats() })));
  r.post('/admin/livrare', requireAdmin, (ctx) => {
    tx(() => {
      for (const m of allMethods()) {
        const price = toBani(ctx.body[`price_${m.code}`] ?? m.price / 100);
        const free = String(ctx.body[`free_${m.code}`] ?? '').trim();
        run('UPDATE delivery_methods SET eta = ?, price = ?, free_over = ?, cod_allowed = ?, active = ? WHERE code = ?',
          clean(ctx.body[`eta_${m.code}`] ?? m.eta, 40), Number.isFinite(price) && price >= 0 ? price : m.price, free ? toBani(free) : null,
          ctx.body[`cod_${m.code}`] === '1' ? 1 : 0, ctx.body[`active_${m.code}`] === '1' ? 1 : 0, m.code);
      }
    });
    log(ctx, 'delivery.update', 'delivery_methods');
    saved(ctx, '/admin/livrare');
  });
  r.post('/admin/livrare/lockere', requireAdmin, (ctx) => {
    const f = ctx.files.find((x) => x.field === 'file');
    let data;
    try { data = JSON.parse(f?.data.toString('utf8') || ''); } catch { data = null; }
    const list = Array.isArray(data) ? data : data?.lockers;
    if (!Array.isArray(list)) { ctx.flash('error', 'Fișier JSON invalid.'); return ctx.redirect('/admin/livrare'); }
    let n = 0;
    tx(() => {
      for (const l of list) {
        const lat = Number(l.lat), lng = Number(l.lng);
        if (!l.id || !l.provider || !Number.isFinite(lat) || !Number.isFinite(lng) || lat < 43 || lat > 49 || lng < 20 || lng > 30.5) continue;
        run(`INSERT INTO lockers(id, provider, name, address, city, county, postcode, lat, lng, hours) VALUES (?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT(id) DO UPDATE SET provider=excluded.provider, name=excluded.name, address=excluded.address, city=excluded.city, county=excluded.county,
             postcode=excluded.postcode, lat=excluded.lat, lng=excluded.lng, hours=excluded.hours, active=1`,
        clean(l.id, 60), clean(l.provider, 20), clean(l.name, 120), clean(l.address, 200), clean(l.city, 80), clean(l.county, 40), clean(l.postcode, 10), lat, lng, clean(l.hours || 'Non-stop', 80));
        n++;
      }
    });
    invalidateLockers();
    log(ctx, 'lockers.import', 'lockers', null, { count: n });
    ctx.flash('success', `${n} lockere importate.`);
    ctx.redirect('/admin/livrare');
  });

  // ─── emailuri ──────────────────────────────────────────────────────────
  const TPL = [['verify', 'Confirmare email'], ['welcome', 'Bun venit'], ['order', 'Comandă plasată'], ['shipped', 'Comandă expediată'], ['reset', 'Resetare parolă'], ['admin', 'Notificare admin']];
  function sampleMail(key, u) {
    const lastOrder = one('SELECT id FROM orders ORDER BY id DESC LIMIT 1');
    const o = lastOrder ? orders.orderById(lastOrder.id) : null;
    const items = o ? orders.orderItems(o.id) : [];
    const meta = { when: new Date().toLocaleString('ro-RO', { timeZone: 'Europe/Bucharest' }), device: 'Safari pe iPhone', ip: '86.120.14.•••' };
    const link = `${config.publicUrl}/cont/confirma?token=exemplu`;
    switch (key) {
      case 'verify': return T.verifyEmail({ user: u, code: '482913', link, meta });
      case 'welcome': return T.welcome({ user: u });
      case 'reset': return T.passwordReset({ user: u, link: `${config.publicUrl}/cont/parola-noua?token=exemplu`, meta });
      case 'order': return o && T.orderConfirmation({ order: { ...o, status: 'new' }, items, link: orders.orderLink(o) });
      case 'shipped': return o && T.orderStatus({ order: { ...o, status: 'shipped', awb: o.awb || '2345678901' }, items, link: orders.orderLink(o) });
      case 'admin': return o && T.adminNewOrder({ order: o, items, link: `${config.publicUrl}/admin/comenzi/${o.id}` });
      default: return null;
    }
  }
  r.get('/admin/emailuri', requireStaff, (ctx) => ctx.html(V.emailsPage(ctx, tr(ctx), { log: all('SELECT * FROM email_log ORDER BY id DESC LIMIT 60'), templates: TPL })));
  r.get('/admin/emailuri/previzualizare/:key', requireStaff, (ctx) => {
    const m = sampleMail(ctx.params.key, ctx.user);
    if (!m) throw new HttpError(404, 'Șablon inexistent.');
    ctx.send('text/html; charset=utf-8', m.html.replaceAll('cid:livada-logo', '/img/brand/email-logo.png'), 200, {
      'Content-Security-Policy': "default-src 'none'; img-src 'self' data: https: http:; style-src 'unsafe-inline'", 'Cache-Control': 'no-store' });
  });
  r.post('/admin/emailuri/test', requireStaff, async (ctx) => {
    limit(ctx, 'mail-test', 5, 10 * 60_000, ctx.user.id);
    const m = sampleMail('verify', ctx.user);
    const res = await sendMail({ to: ctx.user.email, subject: `[Test] ${m.subject}`, html: m.html, text: m.text, template: 'test' });
    ctx.flash(res.status === 'failed' ? 'error' : 'success', res.status === 'failed' ? `Eroare SMTP: ${res.error}` : `Email de test ${res.status === 'sent' ? 'trimis' : 'salvat în outbox'} (${ctx.user.email}).`);
    ctx.redirect('/admin/emailuri');
  });

  // ─── mesaje ────────────────────────────────────────────────────────────
  r.get('/admin/mesaje', requireStaff, (ctx) => ctx.html(V.messagesPage(ctx, tr(ctx), all('SELECT * FROM contact_messages ORDER BY handled, id DESC LIMIT 100'))));
  r.post('/admin/mesaje/:id', requireStaff, (ctx) => { run('UPDATE contact_messages SET handled = 1 WHERE id = ?', Number(ctx.params.id)); ctx.redirect('/admin/mesaje'); });

  // ─── setări ────────────────────────────────────────────────────────────
  r.get('/admin/setari', requireAdmin, (ctx) => ctx.html(V.settingsPage(ctx, tr(ctx), { company: getSetting('company'), showroom: getSetting('showroom'), store: getSetting('store') })));
  r.post('/admin/setari', requireAdmin, (ctx) => {
    const company = { ...getSetting('company') };
    const showroom = { ...getSetting('showroom') };
    const store = { ...getSetting('store') };
    for (const [k, v] of Object.entries(ctx.body)) {
      const [group, key] = k.split('.');
      if (group === 'company' && key in company) company[key] = clean(v, 200);
      if (group === 'showroom' && key in showroom) showroom[key] = ['lat', 'lng'].includes(key) ? Number(v) || showroom[key] : clean(v, 200);
    }
    const num = (v, d) => (Number.isFinite(Number(String(v).replace(',', '.'))) ? Number(String(v).replace(',', '.')) : d);
    store.cod_fee = toBani(num(ctx.body['store.cod_fee'], store.cod_fee / 100));
    store.cod_max = toBani(num(ctx.body['store.cod_max'], store.cod_max / 100));
    store.cutoff_hour = Math.min(23, Math.max(8, Math.round(num(ctx.body['store.cutoff_hour'], store.cutoff_hour))));
    store.express_cutoff_hour = Math.min(23, Math.max(8, Math.round(num(ctx.body['store.express_cutoff_hour'], store.express_cutoff_hour))));
    store.installments_min = toBani(num(ctx.body['store.installments_min'], store.installments_min / 100));
    store.order_prefix = clean(ctx.body['store.order_prefix'], 4).toUpperCase().replace(/[^A-Z]/g, '') || store.order_prefix;
    store.announcement = String(ctx.body['store.announcement'] || '').split('\n').map((s) => clean(s, 80)).filter(Boolean).slice(0, 6);
    if (company.email && !isEmail(company.email)) { ctx.flash('error', 'Emailul de contact nu este valid.'); return ctx.redirect('/admin/setari'); }
    setSetting('company', company); setSetting('showroom', showroom); setSetting('store', store);
    invalidateNav();
    log(ctx, 'settings.update', 'settings');
    saved(ctx, '/admin/setari');
  });

  // ─── jurnal ────────────────────────────────────────────────────────────
  r.get('/admin/jurnal', requireAdmin, (ctx) => ctx.html(V.auditPage(ctx, tr(ctx), all('SELECT a.*, u.email FROM audit_log a LEFT JOIN users u ON u.id = a.user_id ORDER BY a.id DESC LIMIT 200'))));
}
