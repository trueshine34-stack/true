import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { startApp } from './helpers.js';

let app;
before(async () => { app = await startApp(); });
after(async () => { await app.stop(); });

test('html: escapare și ghilimele automate pentru atribute', async () => {
  const { html, raw } = await import('../src/lib/html.js');
  const cls = 'a b';
  const out = html`<p class=${cls} title=${'"x" <y>'}>${'<script>alert(1)</script>'}${raw('<b>ok</b>')}${0}${null}${false}</p>`.s;
  assert.equal(out, '<p class="a b" title="&quot;x&quot; &lt;y&gt;">&lt;script&gt;alert(1)&lt;/script&gt;<b>ok</b>0</p>');
  assert.equal(html`<a href="${'x y'}">`.s, '<a href="x y">'); // deja între ghilimele → neschimbat
});

test('format: lei, plural, telefon, slug', async () => {
  const f = await import('../src/lib/format.js');
  assert.equal(f.lei(764999), '7.649,99 lei');
  assert.equal(f.lei(9999), '99,99 lei');
  assert.equal(f.leiShort(50000), '500 lei');
  assert.equal(f.plural(1, 'produs', 'produse', 'de produse'), '1 produs');
  assert.equal(f.plural(5, 'produs', 'produse', 'de produse'), '5 produse');
  assert.equal(f.plural(21, 'produs', 'produse', 'de produse'), '21 de produse');
  assert.equal(f.phone('0722123456'), '+40 722 123 456');
  assert.equal(f.slugify('Husă din silicon — iPhone 18 Pro'), 'husa-din-silicon-iphone-18-pro');
  assert.equal(f.vatIncluded(12100), 2100);
  assert.equal(f.asDate('2026-10-11 09:15:00').toISOString(), '2026-10-11T09:15:00.000Z');
});

test('validare: telefon RO, CUI cu cifră de control, parolă', async () => {
  const v = await import('../src/lib/validate.js');
  assert.equal(v.normalizePhone('+40 722 123 456'), '0722123456');
  assert.equal(v.normalizePhone('0040722123456'), '0722123456');
  assert.equal(v.normalizePhone('021 555 12 34'), '0215551234');
  assert.equal(v.normalizePhone('12345'), null);
  assert.equal(v.isCui('RO18547290'), true); // CUI valid (cifră de control corectă)
  assert.equal(v.isCui('18547291'), false);
  assert.equal(v.isCui('RO48731906'), false); // CUI-ul placeholder al magazinului NU e valid → nu aparține unei firme reale
  assert.ok(v.passwordProblem('scurt'));
  assert.ok(v.passwordProblem('doarlitere'));
  assert.equal(v.passwordProblem('Livada2026'), null);
  assert.equal(v.isEmail('ana@exemplu.ro'), true);
  assert.equal(v.isEmail('ana@exemplu'), false);
});

test('coș: prețuri, coduri promo, livrare gratuită, ramburs', async () => {
  const cart = await import('../src/services/cart.js');
  const { one } = await import('../src/db/index.js');
  const cartId = 'test-cart-1';
  const iphone = one("SELECT v.id, v.price FROM variants v JOIN products p ON p.id = v.product_id WHERE p.slug = 'iphone-17' AND v.stock > 1 LIMIT 1");
  const cable = one("SELECT v.id, v.price FROM variants v JOIN products p ON p.id = v.product_id JOIN categories c ON c.id = p.category_id WHERE c.slug = 'accesorii' AND v.stock >= 5 AND v.price < 20000 ORDER BY v.price LIMIT 1");
  cart.addItem(cartId, cable.id, 1);
  let t = cart.priceCart(cart.loadCart(cartId), { deliveryCode: 'courier_fan', payment: 'cod' });
  assert.equal(t.subtotal, cable.price);
  assert.equal(t.shipping, 1999, 'sub 500 lei se plătește livrarea');
  assert.equal(t.codFee, 999);
  assert.equal(t.total, cable.price + 1999 + 999);
  assert.ok(t.freeShippingLeft > 0);

  cart.addItem(cartId, iphone.id, 1);
  t = cart.priceCart(cart.loadCart(cartId), { deliveryCode: 'courier_fan', payment: 'card' });
  assert.equal(t.shipping, 0, 'peste 500 lei livrarea e gratuită');
  assert.equal(t.vat, Math.round(t.total - t.total / 1.21));

  cart.setPromo(cartId, 'LIVADA10');
  t = cart.priceCart(cart.loadCart(cartId), { deliveryCode: 'locker_easybox' });
  assert.equal(t.discount, Math.min(30000, Math.round((iphone.price + cable.price) * 0.1)), '10% plafonat la 300 lei');

  cart.setPromo(cartId, 'VARA2026');
  t = cart.priceCart(cart.loadCart(cartId));
  assert.match(t.promoError, /expirat/);
  assert.equal(t.discount, 0);

  cart.setPromo(cartId, 'TRANSPORT0');
  cart.removeItem(cartId, iphone.id);
  t = cart.priceCart(cart.loadCart(cartId), { deliveryCode: 'courier_dpd' });
  assert.equal(t.shipping, 0, 'cod transport gratuit');

  // limită cantitate
  assert.throws(() => { for (let i = 0; i < 10; i++) cart.addItem(cartId, cable.id, 1); }, /maximum/);
});

test('livrare: estimare în zile lucrătoare (fără duminică)', async () => {
  const { estimate, methodByCode } = await import('../src/services/shipping.js');
  const fan = methodByCode('courier_fan');
  // sâmbătă 10 oct 2026, 18:00 ora României → expediere luni → livrare marți
  assert.match(estimate(fan, new Date('2026-10-10T15:00:00Z')), /marți 13 octombrie/i);
  // marți 13 oct, 10:00 → mâine, miercuri
  assert.match(estimate(fan, new Date('2026-10-13T07:00:00Z')), /^Mâine, miercuri 14 octombrie/);
  const express = methodByCode('express_bucuresti');
  assert.match(estimate(express, new Date('2026-10-13T08:00:00Z')), /^Azi/);
  assert.match(estimate(express, new Date('2026-10-13T13:00:00Z')), /^Mâine/);
});

test('email: MIME multipart cu subiect UTF-8 și logo inline', async () => {
  const { buildMime } = await import('../src/lib/mailer.js');
  const mime = buildMime({ from: 'Livada <comenzi@livada.ro>', to: 'Ana <ana@exemplu.ro>', subject: '482 913 este codul tău — Livada', text: 'Cod: 482913', html: '<p>Cod</p>',
    inline: [{ cid: 'livada-logo', filename: 'l.png', type: 'image/png', data: Buffer.from('png') }] });
  assert.match(mime, /^Subject: =\?UTF-8\?B\?/m);
  assert.match(mime, /multipart\/related/);
  assert.match(mime, /Content-ID: <livada-logo>/);
  assert.match(mime, /Content-Type: text\/plain; charset=UTF-8/);
});

test('SMTP: clientul vorbește corect cu un server SMTP (EHLO, AUTH, DATA)', async () => {
  const { smtpSend, buildMime } = await import('../src/lib/mailer.js');
  const log = [];
  let data = '';
  const server = net.createServer((sock) => {
    let inData = false;
    sock.write('220 test.local ESMTP\r\n');
    let buf = '';
    sock.on('data', (d) => {
      buf += d.toString();
      let i;
      while ((i = buf.indexOf('\r\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 2);
        if (inData) {
          if (line === '.') { inData = false; sock.write('250 OK queued\r\n'); } else data += `${line}\n`;
          continue;
        }
        log.push(line);
        if (/^EHLO/.test(line)) sock.write('250-test.local\r\n250-AUTH PLAIN LOGIN\r\n250 8BITMIME\r\n');
        else if (/^AUTH PLAIN/.test(line)) sock.write('235 ok\r\n');
        else if (/^MAIL FROM/.test(line) || /^RCPT TO/.test(line)) sock.write('250 ok\r\n');
        else if (line === 'DATA') { inData = true; sock.write('354 go\r\n'); } else if (line === 'QUIT') { sock.write('221 bye\r\n'); sock.end(); }
      }
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const message = buildMime({ from: 'comenzi@livada.ro', to: 'ana@exemplu.ro', subject: 'Test', text: 'salut', html: '<p>salut</p>' });
  const res = await smtpSend({ host: '127.0.0.1', port: server.address().port, secure: false, user: 'u', pass: 'p', from: 'comenzi@livada.ro', to: 'ana@exemplu.ro', message });
  server.close();
  assert.equal(res.ok, true);
  assert.ok(log.some((l) => l.startsWith('AUTH PLAIN ')));
  assert.ok(log.includes('MAIL FROM:<comenzi@livada.ro>'));
  assert.ok(log.includes('RCPT TO:<ana@exemplu.ro>'));
  assert.match(data, /Subject: Test/);
});

test('căutare: fără diacritice, cu prefix', async () => {
  const { search } = await import('../src/services/catalog.js');
  const r = search('husa 18 pro max');
  assert.ok(r.length > 0);
  assert.ok(r.every((p) => /18 Pro Max/.test(p.name)));
  assert.ok(search('airp').some((p) => p.name === 'AirPods Pro 3'));
  assert.equal(search('"; DROP TABLE products; --').length, 0);
});

test('catalog: toate produsele au imagini existente și prețuri pozitive', async () => {
  const fs = await import('node:fs');
  const path = await import('node:path');
  const { all } = await import('../src/db/index.js');
  const { ROOT } = await import('../src/config.js');
  const products = all('SELECT slug, images FROM products');
  assert.ok(products.length >= 90);
  for (const p of products) {
    const imgs = JSON.parse(p.images);
    assert.ok(Object.keys(imgs).length, `${p.slug} fără imagini`);
    for (const list of Object.values(imgs)) for (const i of list) assert.ok(fs.existsSync(path.join(ROOT, 'public', i['480'])), `lipsă ${i['480']}`);
  }
  assert.equal(all('SELECT COUNT(*) n FROM variants WHERE price <= 0')[0].n, 0);
  assert.ok(all('SELECT COUNT(*) n FROM lockers')[0].n > 1000);
});
