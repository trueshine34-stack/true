import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { startApp, Client, lastMail, mails } from './helpers.js';

let app;
before(async () => { app = await startApp(); });
after(async () => { await app.stop(); });
beforeEach(async () => { (await import('../src/lib/security.js')).resetRateLimits(); });

const codeFrom = (mail) => mail.text.match(/(\d{3}) (\d{3})/).slice(1).join('');
const linkFrom = (mail) => mail.text.match(/https?:\/\/\S+\/cont\/confirma\?token=\S+/)[0];

test('paginile publice răspund, cu antete de securitate', async () => {
  const c = new Client(app.base);
  for (const p of ['/', '/iphone', '/mac', '/ipad', '/watch', '/airpods', '/tv-home', '/accesorii', '/produs/iphone-18-pro', '/produs/iphone-duo',
    '/produs/apple-watch-series-12?v=nonexistent', '/cos', '/cont/autentificare', '/cont/inregistrare', '/livrare', '/retur', '/garantie',
    '/termeni', '/confidentialitate', '/cookies', '/ajutor', '/despre', '/contact', '/oferte', '/noutati', '/cautare?q=magsafe', '/sitemap.xml', '/robots.txt']) {
    const r = await c.get(p);
    assert.equal(r.status, 200, p);
  }
  const r = await c.get('/');
  assert.match(r.headers.get('content-security-policy'), /default-src 'self'/);
  assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
  assert.match(r.body, /<html lang="ro">/);
  assert.equal((await c.get('/nu-exista')).status, 404);
  assert.equal((await c.get('/produs/nu-exista')).status, 404);
});

test('fișierele statice nu permit path traversal', async () => {
  const c = new Client(app.base);
  for (const p of ['/css/../../src/config.js', '/css/..%2f..%2fsrc%2fconfig.js', '/img/%2e%2e/%2e%2e/package.json', '/uploads/../livada.db']) {
    const r = await c.get(p);
    assert.notEqual(r.status, 200, p);
    assert.ok(!r.body.includes('APP_SECRET') && !r.body.includes('"name": "livada"'), p);
  }
  const css = await c.get('/css/store.css');
  assert.equal(css.status, 200);
  assert.match(css.headers.get('cache-control'), /max-age/);
});

test('CSRF: POST fără token sau din altă origine e respins', async () => {
  const c = new Client(app.base);
  await c.get('/');
  const noToken = await c.post('/newsletter', { email: 'x@exemplu.ro' }, { csrf: false });
  assert.equal(noToken.status, 403);
  const badOrigin = await c.post('/newsletter', { email: 'x@exemplu.ro' }, { headers: { origin: 'https://evil.example' } });
  assert.equal(badOrigin.status, 403);
  const ok = await c.post('/newsletter', { email: 'x@exemplu.ro' });
  assert.equal(ok.status, 200);
});

test('înregistrare → email cu cod de 6 cifre → confirmare; încercări limitate', async () => {
  const c = new Client(app.base);
  await c.get('/cont/inregistrare');
  const bad = await c.post('/cont/inregistrare', { first_name: 'Ana', last_name: 'Pop', email: 'ana', password: '123' });
  assert.equal(bad.status, 422);
  assert.ok(bad.json.errors.email && bad.json.errors.password && bad.json.errors.terms);

  const r = await c.post('/cont/inregistrare', { first_name: 'Ana', last_name: 'Pop', email: 'ana.pop@exemplu.ro', password: 'Livada2026', terms: 'on' });
  assert.equal(r.status, 200, r.body);
  assert.equal(r.json.redirect, '/cont/verificare');
  const cookie = c.jar.get('lv_sid');
  assert.ok(cookie);

  // contul neconfirmat nu are acces la cont
  const acc = await c.get('/cont');
  assert.equal(acc.status, 303);
  assert.equal(acc.headers.get('location'), '/cont/verificare');

  const mail = lastMail(app.dir, 'ana.pop@exemplu.ro');
  assert.match(mail.subject, /^\d{3} \d{3} este codul tău de confirmare Livada$/);
  assert.match(mail.html, /Confirmă adresa de email/);
  const code = codeFrom(mail);
  const wrong = String((Number(code) + 1) % 1000000).padStart(6, '0');

  const w = await c.post('/cont/verificare', { code: wrong });
  assert.equal(w.status, 422);
  assert.match(w.json.errors.code, /Mai ai 4 încercări/);

  // retrimiterea imediată e blocată de cooldown
  const resend = await c.post('/cont/verificare/retrimite', {});
  assert.equal(resend.status, 429);

  const ok = await c.post('/cont/verificare', { code });
  assert.equal(ok.status, 200, ok.body);
  assert.match(ok.json.redirect, /^\/cont/);
  const welcome = lastMail(app.dir, 'ana.pop@exemplu.ro');
  assert.match(welcome.subject, /activ/);
  assert.match(welcome.html, /BUNVENIT50/);
  assert.equal((await c.get('/cont')).status, 200);

  // același email nu se poate înregistra din nou
  const dup = await new Client(app.base).post('/cont/inregistrare', { first_name: 'X', last_name: 'Y', email: 'ANA.POP@exemplu.ro', password: 'Livada2026', terms: 'on' });
  assert.equal(dup.status, 422);
  assert.match(dup.json.errors.email, /Există deja/);
});

test('confirmare prin link (pe alt dispozitiv) + link folosit o singură dată', async () => {
  const c = new Client(app.base);
  await c.post('/cont/inregistrare', { first_name: 'Mihai', last_name: 'Ionescu', email: 'mihai@exemplu.ro', password: 'Parola123', terms: 'on' });
  const link = linkFrom(lastMail(app.dir, 'mihai@exemplu.ro'));
  const other = new Client(app.base);
  const r = await other.get(link.replace(/^https?:\/\/[^/]+/, ''));
  assert.equal(r.status, 303);
  assert.match(r.headers.get('location'), /^\/cont/);
  assert.ok(other.jar.get('lv_sid'), 'linkul autentifică utilizatorul');
  const again = await new Client(app.base).get(link.replace(/^https?:\/\/[^/]+/, ''));
  assert.equal(again.headers.get('location'), '/cont/autentificare');
});

test('autentificare: parolă greșită, limitare, resetare parolă', async () => {
  const c = new Client(app.base);
  const bad = await c.post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'gresit' });
  assert.equal(bad.status, 401);
  for (let i = 0; i < 8; i++) await c.post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'gresit' });
  const limited = await c.post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'Livada2026' });
  assert.equal(limited.status, 429, 'după prea multe încercări, chiar și parola corectă e blocată temporar');
  (await import('../src/lib/security.js')).resetRateLimits();

  const fresh = new Client(app.base);
  const forgot = await fresh.post('/cont/parola-uitata', { email: 'ana.pop@exemplu.ro' });
  assert.equal(forgot.status, 200);
  const unknown = await fresh.post('/cont/parola-uitata', { email: 'nimeni@exemplu.ro' });
  assert.equal(unknown.status, 200, 'răspuns identic pentru emailuri inexistente');
  const mail = lastMail(app.dir, 'ana.pop@exemplu.ro');
  assert.match(mail.subject, /Resetarea parolei/);
  const token = mail.text.match(/token=([\w-]+)/)[1];
  const reset = await fresh.post('/cont/parola-noua', { token, password: 'NouaParola99' });
  assert.equal(reset.status, 200);
  const reuse = await fresh.post('/cont/parola-noua', { token, password: 'AltaParola99' });
  assert.equal(reuse.status, 410);
  const login = await new Client(app.base).post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'NouaParola99' });
  assert.equal(login.status, 200);
  const cookie = login.headers.getSetCookie().find((x) => x.startsWith('lv_sid='));
  assert.match(cookie, /HttpOnly/);
  assert.match(cookie, /SameSite=Lax/);
});

async function firstVariant(where) {
  const { one } = await import('../src/db/index.js');
  return one(`SELECT v.id, v.price, v.stock FROM variants v JOIN products p ON p.id = v.product_id JOIN categories c ON c.id = p.category_id WHERE ${where} ORDER BY v.id LIMIT 1`);
}

test('coș + checkout cu easybox și ramburs: comandă, stoc, emailuri', async () => {
  const c = new Client(app.base);
  await c.get('/');
  const v = await firstVariant("p.slug = 'airpods-pro-3' AND v.stock >= 2");
  const add = await c.post('/cos/adauga', { variant_id: v.id });
  assert.equal(add.status, 200);
  assert.equal(add.json.count, 1);
  const drawer = await c.get('/cos/drawer', { json: true });
  assert.match(drawer.json.body, /AirPods Pro 3/);

  // promo invalid
  const promo = await c.post('/cos/promo', { code: 'NUEXISTA' });
  assert.equal(promo.status, 422);

  const page = await c.get('/checkout');
  assert.equal(page.status, 200);
  assert.match(page.body, /Caută lockerul sau punctul de ridicare/);
  assert.match(page.body, /\/img\/carriers\/sameday-mark\.svg/, 'sigla originală a rețelei în checkout');

  // locker lipsă → eroare pe câmp
  const base = { first_name: 'Ioana', last_name: 'Marin', email: 'ioana@exemplu.ro', phone: '0722 123 456', customer_type: 'pf',
    bill_county: 'Cluj', bill_city: 'Cluj-Napoca', bill_street: 'Str. Republicii 12', payment_method: 'cod', terms: 'on' };
  const noLocker = await c.post('/checkout', { ...base, delivery_method: 'locker_easybox' });
  assert.equal(noLocker.status, 422);
  assert.ok(noLocker.json.errors.locker_id);

  const lockers = await c.get('/api/lockers', { json: true });
  const F = Object.fromEntries(lockers.json.fields.map((f, i) => [f, i]));
  const easy = lockers.json.rows.find((r) => r[F.provider] === 'easybox' && r[F.city] === 'Cluj-Napoca');
  const fan = lockers.json.rows.find((r) => r[F.provider] === 'fanbox');
  assert.ok(lockers.json.rows.length > 10000, 'lista completă a lockerelor și punctelor');
  assert.ok(lockers.json.rows.some((r) => r[F.provider] === 'gls' && r[F.kind] === 1), 'punctele de ridicare (ParcelShop) sunt marcate');
  assert.ok(fan[F.note], 'FANbox are reperul de găsire');
  // ETag: a doua cerere nu mai descarcă lista
  const again = await c.get('/api/lockers', { headers: { 'if-none-match': lockers.headers.get('etag') } });
  assert.equal(again.status, 304);
  const mismatch = await c.post('/checkout', { ...base, delivery_method: 'locker_easybox', locker_id: fan[0] });
  assert.equal(mismatch.status, 422);

  const quote = await c.post('/api/checkout/quote', { delivery_method: 'locker_easybox', payment_method: 'cod' });
  assert.equal(quote.status, 200);
  assert.ok(quote.json.codAllowed);

  const ok = await c.post('/checkout', { ...base, delivery_method: 'locker_easybox', locker_id: easy[0] });
  assert.equal(ok.status, 200, ok.body);
  assert.match(ok.json.redirect, /^\/comanda\/[\w-]+\?nou=1$/);
  const order = await c.get(ok.json.redirect);
  assert.equal(order.status, 200);
  assert.match(order.body, /Mulțumim, Ioana/);
  assert.match(order.body, new RegExp(easy[F.name].replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));

  // simularea plății cu cardul nu poate marca plătită o comandă cu ramburs
  const token = ok.json.redirect.match(/\/comanda\/([\w-]+)/)[1];
  const forged = await c.post(`/plata/${token}`, { result: 'success' }, { json: false });
  assert.equal(forged.status, 303);
  const { one } = await import('../src/db/index.js');
  assert.equal(one('SELECT payment_status FROM orders WHERE public_token = ?', token).payment_status, 'cod');

  const after = await firstVariant(`v.id = ${v.id}`);
  assert.equal(after.stock, v.stock - 1, 'stocul scade');
  const conf = lastMail(app.dir, 'ioana@exemplu.ro');
  assert.match(conf.subject, /Comanda LV\d{2}-\d{5} a fost înregistrată/);
  assert.match(conf.html, /AirPods Pro 3/);
  assert.ok(mails(app.dir, 'comenzi@livada.ro').some((m) => m.subject.includes(ok.json.number)), 'notificare către magazin');
  const empty = await c.get('/cos/drawer', { json: true });
  assert.equal(empty.json.count, 0, 'coșul se golește');
});

test('BUNVENIT50 se aplică doar la prima comandă (după email)', async () => {
  const c = new Client(app.base);
  const v = await firstVariant("p.slug = 'iphone-17' AND v.stock >= 2");
  await c.post('/cos/adauga', { variant_id: v.id });
  assert.equal((await c.post('/cos/promo', { code: 'bunvenit50' })).status, 200, 'vizitator anonim: codul se aplică provizoriu');
  // ioana@exemplu.ro a comandat deja în testul anterior
  const quote = await c.post('/api/checkout/quote', { delivery_method: 'courier_fan', payment_method: 'cod', email: 'ioana@exemplu.ro' });
  assert.match(quote.json.promo, /doar la prima comandă/);
  const base = { first_name: 'Ioana', last_name: 'Marin', phone: '0722 123 456', customer_type: 'pf', delivery_method: 'courier_fan',
    ship_county: 'Cluj', ship_city: 'Cluj-Napoca', ship_street: 'Str. Republicii 12', billing_same: 'on', payment_method: 'cod', terms: 'on' };
  const again = await c.post('/checkout', { ...base, email: 'IOANA@exemplu.ro' });
  assert.equal(again.status, 422);
  assert.match(again.json.errors.promo, /doar la prima comandă/);
  const fresh = await c.post('/api/checkout/quote', { delivery_method: 'courier_fan', payment_method: 'cod', email: 'nou@exemplu.ro' });
  assert.doesNotMatch(fresh.json.rows, /doar la prima/);
  assert.match(fresh.json.rows, /Reducere BUNVENIT50/);
  await c.post('/cos/promo/sterge', {});
});

test('checkout cu cardul: plată simulată → plătită și confirmată; firmă cu CUI invalid respinsă', async () => {
  const c = new Client(app.base);
  const v = await firstVariant("p.slug = 'iphone-17' AND v.stock >= 2");
  await c.post('/cos/adauga', { variant_id: v.id });
  const base = { first_name: 'Radu', last_name: 'Stan', email: 'radu@exemplu.ro', phone: '0744111222', delivery_method: 'courier_sameday',
    ship_county: 'București', ship_city: 'București', ship_street: 'Calea Victoriei 100', ship_postcode: '010071', billing_same: 'on', payment_method: 'card', terms: 'on' };
  const pj = await c.post('/checkout', { ...base, customer_type: 'pj', company_name: 'Test SRL', company_cui: 'RO12345678' });
  assert.equal(pj.status, 422);
  assert.ok(pj.json.errors.company_cui);
  const express = await c.post('/checkout', { ...base, delivery_method: 'express_bucuresti', ship_county: 'Cluj' });
  assert.equal(express.status, 422, 'livrarea în aceeași zi doar în București/Ilfov');

  const ok = await c.post('/checkout', { ...base, customer_type: 'pj', company_name: 'Test SRL', company_cui: 'RO18547290', company_reg: 'J12/345/2020' });
  assert.equal(ok.status, 200, ok.body);
  assert.match(ok.json.redirect, /^\/plata\//);
  const pay = await c.get(ok.json.redirect);
  assert.match(pay.body, /Mediu de test/);
  const paid = await c.post(ok.json.redirect, { result: 'success' }, { json: false });
  assert.equal(paid.status, 303);
  const o = await c.get(paid.location);
  assert.match(o.body, /Plătită/);
  assert.match(o.body, /Confirmată/);
});

test('admin: clienții nu au acces; adminul schimbă statusul și clientul primește email', async () => {
  const cust = new Client(app.base);
  await cust.post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'NouaParola99' });
  assert.equal((await cust.get('/admin')).status, 403);
  assert.equal((await new Client(app.base).get('/admin')).status, 303);

  const a = new Client(app.base);
  await a.get('/admin/autentificare');
  const login = await a.post('/admin/autentificare', { email: 'admin@livada.ro', password: 'Admin-Test-2026', next: '/admin' }, { json: false });
  assert.equal(login.status, 303);
  const dash = await a.get('/admin');
  assert.equal(dash.status, 200);
  assert.match(dash.body, /Vânzări 30 de zile/);

  const { one } = await import('../src/db/index.js');
  const order = one("SELECT id, number, email FROM orders WHERE email = 'ioana@exemplu.ro'");
  const st = await a.post(`/admin/comenzi/${order.id}/status`, { status: 'confirmed', notify: '1', note: 'Pregătim coletul' }, { json: false });
  assert.equal(st.status, 303);
  const bad = await a.post(`/admin/comenzi/${order.id}/status`, { status: 'delivered', notify: '1' }, { json: false });
  assert.equal(bad.status, 303);
  assert.equal(one('SELECT status FROM orders WHERE id = ?', order.id).status, 'confirmed', 'tranziția invalidă e refuzată');
  await a.post(`/admin/comenzi/${order.id}/status`, { status: 'shipped', awb: '1234567890', notify: '1' }, { json: false });
  const m = lastMail(app.dir, 'ioana@exemplu.ro');
  assert.match(m.subject, /Expediată/);
  assert.match(m.html, /1234567890/);

  const csv = await a.get('/admin/comenzi.csv');
  assert.match(csv.headers.get('content-type'), /text\/csv/);
  assert.match(csv.body, new RegExp(order.number));

  // limba RU
  await a.post('/admin/limba', { lang: 'ru' }, { json: false });
  assert.match((await a.get('/admin')).body, /Продажи за 30 дней/);
  const audit = one("SELECT COUNT(*) n FROM audit_log WHERE action = 'order.status'");
  assert.ok(audit.n >= 2);
});

test('XSS: datele utilizatorului sunt escapate la afișare', async () => {
  const c = new Client(app.base);
  await c.post('/cont/inregistrare', { first_name: '<img src=x onerror=alert(1)>', last_name: 'Test', email: 'xss@exemplu.ro', password: 'Parola123', terms: 'on' });
  const code = codeFrom(lastMail(app.dir, 'xss@exemplu.ro'));
  await c.post('/cont/verificare', { code });
  const page = await c.get('/cont');
  assert.ok(!page.body.includes('<img src=x onerror'), 'markup brut');
  assert.ok(page.body.includes('&lt;img src=x onerror=alert(1)&gt;'));
  const mail = lastMail(app.dir, 'xss@exemplu.ro');
  assert.ok(!mail.html.includes('<img src=x onerror'));
});

test('favorite și export de date (GDPR)', async () => {
  const c = new Client(app.base);
  const login = await c.post('/cont/autentificare', { email: 'ana.pop@exemplu.ro', password: 'NouaParola99' });
  assert.equal(login.status, 200);
  const { one } = await import('../src/db/index.js');
  const p = one("SELECT id FROM products WHERE slug = 'macbook-neo'");
  const fav = await c.post(`/api/favorites/${p.id}`, {});
  assert.equal(fav.json.on, true);
  assert.match((await c.get('/cont/favorite')).body, /MacBook Neo/);
  const exp = await c.get('/cont/export');
  assert.match(exp.headers.get('content-disposition'), /attachment/);
  const data = JSON.parse(exp.body);
  assert.equal(data.cont.email, 'ana.pop@exemplu.ro');
  assert.deepEqual(data.favorite, [p.id]);
});
