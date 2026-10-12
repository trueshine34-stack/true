import { html } from '../lib/html.js';
import { localizePath, t } from '../lib/i18n.js';
import { config } from '../config.js';
import { HttpError } from '../lib/http.js';
import { run, all } from '../db/index.js';
import { done, limit, backUrl } from './helpers.js';
import { ensureCartId } from '../app.js';
import { clean, cleanText, isEmail, ValidationError } from '../lib/validate.js';
import { sendMail } from '../lib/mailer.js';
import * as T from '../services/mail-templates.js';
import * as catalog from '../services/catalog.js';
import * as cartSvc from '../services/cart.js';
import * as orders from '../services/orders.js';
import { methods } from '../services/shipping.js';
import { addresses as userAddresses } from '../services/users.js';
import { homePage } from '../views/store/home.js';
import { listingPage, parsePriceBand } from '../views/store/category.js';
import { productPage } from '../views/store/product.js';
import { cartPage, cartSection, cartDrawer } from '../views/store/cart.js';
import { checkoutPage } from '../views/store/checkout.js';
import { orderPage, paymentPage } from '../views/store/order.js';
import { contactPage, contactSentBlock, STATIC_PAGES } from '../views/store/pages.js';
import { page } from '../views/layout.js';
import { emptyState } from '../views/components.js';

function listingParams(q) {
  const { min, max } = parsePriceBand(q.get('pret'));
  return { group: q.get('grup') || undefined, sort: q.get('sort') || 'recommended', min, max, inStock: q.get('stoc') === '1', promo: q.get('oferte') === '1' };
}

export default function storeRoutes(r) {
  r.get('/', (ctx) => ctx.html(homePage(ctx)));

  // ─── catalog ───────────────────────────────────────────────────────────
  r.get('/produs/:slug', (ctx) => {
    const p = catalog.productBySlug(ctx.params.slug);
    if (!p) throw new HttpError(404, t('Produsul nu există.'));
    ctx.html(productPage(ctx, p, catalog.relatedFor(p)));
  });

  r.get('/cautare', (ctx) => {
    const q = clean(ctx.query.get('q'), 80);
    const params = listingParams(ctx.query);
    let products = q ? catalog.search(q, 60) : [];
    if (params.min) products = products.filter((p) => p.price_from >= params.min);
    if (params.max) products = products.filter((p) => p.price_from <= params.max);
    if (params.inStock) products = products.filter((p) => p.in_stock);
    if (params.promo) products = products.filter((p) => p.compare_from);
    if (params.sort === 'price-asc') products.sort((a, b) => a.price_from - b.price_from);
    if (params.sort === 'price-desc') products.sort((a, b) => b.price_from - a.price_from);
    ctx.html(listingPage(ctx, { title: q ? t('Rezultate pentru „{q}”', { q }) : t('Caută'), metaTitle: q ? t('Căutare: {q}', { q }) : t('Caută'), products, base: '/cautare',
      query: ctx.query, crumbs: [{ label: t('Căutare') }], tagline: q ? '' : t('Scrie numele unui produs în bara de căutare.') }));
  });

  r.get('/oferte', (ctx) => {
    const products = catalog.listProducts({ ...listingParams(ctx.query), promo: true });
    ctx.html(listingPage(ctx, { title: t('Oferte'), tagline: t('Reduceri reale la produse Apple noi și sigilate.'), products, base: '/oferte', query: ctx.query, crumbs: [{ label: t('Oferte') }] }));
  });

  r.get('/noutati', (ctx) => {
    const products = catalog.listProducts({ ...listingParams(ctx.query), sort: ctx.query.get('sort') || 'new' }).filter((p) => p.badge || p.featured);
    ctx.html(listingPage(ctx, { title: t('Noutăți'), tagline: t('Cele mai noi produse Apple, inclusiv precomenzi.'), products, base: '/noutati', query: ctx.query, crumbs: [{ label: t('Noutăți') }] }));
  });

  // ─── coș ───────────────────────────────────────────────────────────────
  const customerOf = (ctx) => (ctx.user ? { userId: ctx.user.id, email: ctx.user.email } : null);
  const cartState = (ctx) => {
    const cart = cartSvc.loadCart(ctx.cartId);
    return { cart, totals: cartSvc.priceCart(cart, { customer: customerOf(ctx) }) };
  };

  r.get('/cos', (ctx) => {
    const { cart, totals } = cartState(ctx);
    const suggestions = cart.items.length ? [] : catalog.listProducts({ featured: true, limit: 4 });
    ctx.html(cartPage(ctx, cart, totals, suggestions));
  });
  r.get('/cos/fragment', (ctx) => {
    const { cart, totals } = cartState(ctx);
    ctx.html(cartSection(ctx, cart, totals));
  });
  r.get('/cos/drawer', (ctx) => {
    const { cart, totals } = cartState(ctx);
    const d = cartDrawer(ctx, cart, totals);
    ctx.json({ count: totals.count, body: d.body.s ?? String(d.body), foot: d.foot ? (d.foot.s ?? String(d.foot)) : '' });
  });

  r.post('/cos/adauga', (ctx) => {
    limit(ctx, 'cart', 60, 60_000);
    const cartId = ensureCartId(ctx);
    if (ctx.user) cartSvc.ensureCart(cartId, ctx.user.id);
    const variantId = Number(ctx.body.variant_id);
    try {
      cartSvc.addItem(cartId, variantId, Number(ctx.body.qty) || 1, ctx.user?.id);
    } catch (e) {
      if (ctx.wantsJson) throw e;
      ctx.flash('error', e.message);
      return ctx.redirect(backUrl(ctx, '/cos'));
    }
    const count = cartSvc.cartCount(cartId);
    return done(ctx, { redirect: '/cos', message: t('Produsul a fost adăugat în coș.'), data: { count } });
  });

  r.post('/cos/actualizeaza', (ctx) => {
    if (!ctx.cartId) return done(ctx, { redirect: '/cos' });
    cartSvc.setQty(ctx.cartId, Number(ctx.body.variant_id), Number(ctx.body.qty));
    return done(ctx, { redirect: '/cos', data: { count: cartSvc.cartCount(ctx.cartId) } });
  });

  r.post('/cos/sterge', (ctx) => {
    if (ctx.cartId) cartSvc.removeItem(ctx.cartId, Number(ctx.body.variant_id));
    return done(ctx, { redirect: '/cos', data: { count: cartSvc.cartCount(ctx.cartId) } });
  });

  r.post('/cos/promo', (ctx) => {
    limit(ctx, 'promo', 15, 60_000);
    const cartId = ensureCartId(ctx);
    const code = clean(ctx.body.code, 40).toUpperCase();
    const cart = cartSvc.loadCart(cartId);
    const subtotal = cartSvc.priceCart(cart).subtotal;
    const problem = cartSvc.promoProblem(cartSvc.findPromo(code), subtotal, customerOf(ctx));
    if (problem) {
      if (ctx.wantsJson) throw new ValidationError({ code: problem });
      ctx.flash('error', problem);
      return ctx.redirect('/cos');
    }
    cartSvc.setPromo(cartId, code);
    return done(ctx, { redirect: ctx.req.headers.referer?.includes('/checkout') ? '/checkout' : '/cos', message: t('Codul {code} a fost aplicat.', { code }) });
  });

  r.post('/cos/promo/sterge', (ctx) => {
    if (ctx.cartId) cartSvc.setPromo(ctx.cartId, null);
    return done(ctx, { redirect: ctx.req.headers.referer?.includes('/checkout') ? '/checkout' : '/cos' });
  });

  // ─── checkout ──────────────────────────────────────────────────────────
  r.get('/checkout', (ctx) => {
    const cart = cartSvc.loadCart(ctx.cartId);
    if (!cart.items.length) return ctx.redirect('/cos');
    const totals = cartSvc.priceCart(cart, { customer: customerOf(ctx) });
    ctx.html(checkoutPage(ctx, { cart, totals, methods: methods(), addresses: ctx.user ? userAddresses(ctx.user.id) : [], user: ctx.user }));
  });

  r.post('/checkout', async (ctx) => {
    limit(ctx, 'checkout', 12, 10 * 60_000);
    if (!ctx.cartId) throw new HttpError(400, t('Coșul tău este gol.'));
    let order;
    try {
      order = await orders.placeOrder({ cartId: ctx.cartId, user: ctx.user, body: ctx.body, ip: ctx.ip });
    } catch (e) {
      if (ctx.wantsJson) throw e;
      ctx.flash('error', e instanceof ValidationError ? Object.values(e.errors)[0] : e.message);
      return ctx.redirect('/checkout');
    }
    const redirect = ['card', 'installments'].includes(order.payment_method) ? `/plata/${order.public_token}` : `/comanda/${order.public_token}?nou=1`;
    return done(ctx, { redirect, data: { number: order.number } });
  });

  // ─── comandă + plată ───────────────────────────────────────────────────
  r.get('/comanda/:token', (ctx) => {
    const o = orders.orderByToken(ctx.params.token);
    if (!o) throw new HttpError(404, t('Comanda nu există.'));
    ctx.html(orderPage(ctx, o, orders.orderItems(o.id), orders.orderEvents(o.id), { fresh: ctx.query.get('nou') === '1' }));
  });

  // simularea procesatorului de plăți: doar pentru comenzile cu card/rate, neplătite și active
  const payable = (o) => o && config.paymentMock && ['card', 'installments'].includes(o.payment_method)
    && o.payment_status !== 'paid' && !['cancelled', 'returned'].includes(o.status);

  r.get('/plata/:token', (ctx) => {
    const o = orders.orderByToken(ctx.params.token);
    if (!o || !config.paymentMock) throw new HttpError(404, t('Comanda nu există.'));
    if (o.payment_status === 'paid' || !['card', 'installments'].includes(o.payment_method)) return ctx.redirect(`/comanda/${o.public_token}`);
    ctx.html(paymentPage(ctx, o));
  });

  r.post('/plata/:token', async (ctx) => {
    limit(ctx, 'pay', 10, 10 * 60_000);
    const o = orders.orderByToken(ctx.params.token);
    if (!o || !config.paymentMock) throw new HttpError(404, t('Comanda nu există.'));
    if (!payable(o)) return ctx.redirect(`/comanda/${o.public_token}`);
    if (ctx.body.result === 'fail') {
      ctx.flash('error', t('Plata a fost respinsă de bancă. Poți încerca din nou sau alege altă metodă.'));
      return ctx.redirect(`/plata/${o.public_token}`);
    }
    await orders.completeCardPayment(o);
    return ctx.redirect(`/comanda/${o.public_token}?nou=1`);
  });

  // ─── pagini informative ────────────────────────────────────────────────
  for (const [slug, fn] of Object.entries(STATIC_PAGES)) r.get(`/${slug}`, (ctx) => ctx.html(fn(ctx)));

  r.get('/contact', (ctx) => ctx.html(contactPage(ctx)));
  r.post('/contact', async (ctx) => {
    limit(ctx, 'contact', 5, 10 * 60_000);
    if (ctx.body.website) return done(ctx, { redirect: '/contact', message: t('Mesaj trimis.') }); // honeypot
    const data = { name: clean(ctx.body.name, 80), email: clean(ctx.body.email, 190), phone: clean(ctx.body.phone, 20), topic: clean(ctx.body.topic, 120), message: cleanText(ctx.body.message, 2000) };
    const errors = {};
    if (data.name.length < 2) errors.name = t('Completează numele.');
    if (!isEmail(data.email)) errors.email = t('Adresa de email nu pare corectă.');
    if (data.message.length < 10) errors.message = t('Scrie-ne câteva cuvinte (minimum 10 caractere).');
    if (Object.keys(errors).length) {
      if (ctx.wantsJson) throw new ValidationError(errors);
      return ctx.html(contactPage(ctx, { errors, values: data }), 422);
    }
    run('INSERT INTO contact_messages(name, email, phone, topic, message) VALUES (?,?,?,?,?)', data.name, data.email, data.phone, data.topic, data.message);
    const m = T.contactReceived({ name: data.name });
    await sendMail({ to: data.email, subject: m.subject, html: m.html, text: m.text, template: 'contact' });
    if (ctx.wantsJson) return ctx.json({ ok: true, replace: contactSentBlock().s, message: t('Mesajul a fost trimis. Mulțumim!') });
    return ctx.html(contactPage(ctx, { sent: true }));
  });

  r.post('/newsletter', (ctx) => {
    limit(ctx, 'newsletter', 5, 10 * 60_000);
    const email = clean(ctx.body.email, 190).toLowerCase();
    if (!isEmail(email)) throw new ValidationError({ email: t('Adresa de email nu pare corectă.') });
    run('INSERT OR IGNORE INTO newsletter(email) VALUES (?)', email);
    return done(ctx, { message: t('Gata! Te-ai abonat la noutățile Livada.') });
  });

  // ─── SEO ───────────────────────────────────────────────────────────────
  r.get('/robots.txt', (ctx) => ctx.send('text/plain; charset=utf-8',
    `User-agent: *\nDisallow: /admin\nDisallow: /cont\nDisallow: /cos\nDisallow: /checkout\nDisallow: /comanda\nDisallow: /plata\nDisallow: /api\nSitemap: ${config.publicUrl}/sitemap.xml\n`, 200, { 'Cache-Control': 'public, max-age=3600' }));

  r.get('/sitemap.xml', (ctx) => {
    const urls = ['/', '/noutati', '/oferte', ...catalog.categories().map((c) => `/${c.slug}`),
      ...all('SELECT slug, updated_at FROM products WHERE active = 1').map((p) => ({ loc: `/produs/${p.slug}`, lastmod: p.updated_at.slice(0, 10) })),
      ...Object.keys(STATIC_PAGES).map((s) => `/${s}`), '/contact'];
    // fiecare pagină în ambele limbi, cu legături hreflang între versiuni
    const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${urls.flatMap((u) => {
      const loc = typeof u === 'string' ? u : u.loc;
      const ro = `${config.publicUrl}${loc}`;
      const en = `${config.publicUrl}${localizePath(loc, 'en')}`;
      const alts = `<xhtml:link rel="alternate" hreflang="ro" href="${ro}"/><xhtml:link rel="alternate" hreflang="en" href="${en}"/>`;
      return [ro, en].map((l) => `  <url><loc>${l}</loc>${u.lastmod ? `<lastmod>${u.lastmod}</lastmod>` : ''}${alts}</url>`);
    }).join('\n')}\n</urlset>\n`;
    ctx.send('application/xml; charset=utf-8', xml, 200, { 'Cache-Control': 'public, max-age=3600' });
  });

  r.get('/site.webmanifest', (ctx) => ctx.send('application/manifest+json', JSON.stringify({
    name: 'Livada', short_name: 'Livada', start_url: '/', display: 'standalone', background_color: '#FFFFFF', theme_color: '#0E1411',
    icons: [{ src: '/img/brand/icon-192.png', sizes: '192x192', type: 'image/png' }, { src: '/img/brand/icon-512.png', sizes: '512x512', type: 'image/png' }],
  }), 200, { 'Cache-Control': 'public, max-age=86400' }));

  // ─── categorii (ultimele: /:slug generic) ───────────────────────────────
  r.get('/:category', (ctx) => {
    const c = catalog.categoryBySlug(ctx.params.category);
    if (!c) throw new HttpError(404, t('Pagina nu există.'));
    const params = listingParams(ctx.query);
    const products = catalog.listProducts({ category: c.slug, ...params });
    // titlul și sloganul categoriei vin din baza de date (în română) → traduse la afișare
    const title = t(c.title), tagline = t(c.tagline);
    ctx.html(listingPage(ctx, { category: c, title, tagline, groups: c.groups.filter((g) => catalog.listProducts({ category: c.slug, group: g }).length),
      products, base: `/${c.slug}`, query: ctx.query, crumbs: [{ label: title }], description: t('{title} la Livada: {tagline} Livrare în 24h în toată România, retur 30 de zile, rate 0%.', { title, tagline }),
      canonical: `/${c.slug}` }));
  });
}

export { html, page, emptyState };
