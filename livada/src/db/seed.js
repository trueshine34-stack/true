import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, config } from '../config.js';
import { openDb, one, all, run, tx, setSetting, getSetting, clearSettingsCache } from './index.js';
import { DEFAULT_SETTINGS, DELIVERY_METHODS, PROMO_CODES } from './defaults.js';
import { hashPassword, randomToken } from '../lib/security.js';
import { fold } from '../lib/format.js';

const bani = (lei) => Math.round(lei * 100);

export function seedCatalog() {
  const data = JSON.parse(fs.readFileSync(path.join(ROOT, 'data/catalog.json'), 'utf8'));
  tx(() => {
    data.categories.forEach((c, i) => {
      run(`INSERT INTO categories(slug, name, title, tagline, groups, sort) VALUES (?,?,?,?,?,?)
           ON CONFLICT(slug) DO UPDATE SET name=excluded.name, title=excluded.title, tagline=excluded.tagline, groups=excluded.groups, sort=excluded.sort`,
      c.slug, c.name, c.title, c.tagline, JSON.stringify(c.groups), i);
    });
    const catId = Object.fromEntries(all('SELECT slug, id FROM categories').map((r) => [r.slug, r.id]));
    data.products.forEach((p, i) => {
      const exists = one('SELECT id FROM products WHERE slug = ?', p.slug);
      if (exists) return; // nu suprascriem modificările din admin
      const r = run(`INSERT INTO products(slug, category_id, grp, name, tagline, badge, featured, release_note, description, highlights,
                      specs, in_box, options, images, image_key, preorder, sort) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      p.slug, catId[p.category], p.group || '', p.name, p.tagline || '', p.badge || null, p.featured ? 1 : 0, p.release || null,
      JSON.stringify(p.description || []), JSON.stringify(p.highlights || []), JSON.stringify(p.specs || []),
      JSON.stringify(p.in_box || []), JSON.stringify(p.options || []), JSON.stringify(p.images || {}), p.image_key || null,
      p.preorder ? 1 : 0, i);
      const pid = Number(r.lastInsertRowid);
      p.variants.forEach((v, j) => {
        run('INSERT INTO variants(product_id, sku, mpn, options, price, compare_at, stock, sort) VALUES (?,?,?,?,?,?,?,?)',
          pid, v.sku, v.mpn || null, JSON.stringify(v.options), bani(v.price), v.compare_at ? bani(v.compare_at) : null, v.stock, j);
      });
    });
  });
  rebuildSearchIndex();
  return data.products.length;
}

export function rebuildSearchIndex() {
  tx(() => {
    run('DELETE FROM product_search');
    for (const p of all(`SELECT p.id, p.name, p.tagline, p.grp, p.highlights, p.specs, p.options, c.name AS cat
                         FROM products p JOIN categories c ON c.id = p.category_id WHERE p.active = 1`)) {
      const opts = JSON.parse(p.options).flatMap((o) => o.values.map((v) => v.label));
      const specs = JSON.parse(p.specs).flatMap((g) => g.rows.map((r) => r.v));
      const mpns = all('SELECT mpn FROM variants WHERE product_id = ? AND mpn IS NOT NULL', p.id).map((v) => v.mpn.replace('/', ''));
      const body = [...JSON.parse(p.highlights), ...opts, ...specs, ...mpns].join(' · ');
      run('INSERT INTO product_search(name, tagline, grp, category, body, product_id) VALUES (?,?,?,?,?,?)',
        p.name, p.tagline, p.grp, p.cat, body, p.id);
    }
  });
}

export function seedLockers() {
  const file = path.join(ROOT, 'data/lockers.json');
  if (!fs.existsSync(file)) return 0;
  const { lockers } = JSON.parse(fs.readFileSync(file, 'utf8'));
  tx(() => {
    for (const l of lockers) {
      run(`INSERT INTO lockers(id, provider, name, address, city, county, postcode, lat, lng, hours) VALUES (?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(id) DO UPDATE SET name=excluded.name, address=excluded.address, city=excluded.city, county=excluded.county,
           postcode=excluded.postcode, lat=excluded.lat, lng=excluded.lng, hours=excluded.hours`,
      l.id, l.provider, l.name, l.address, l.city, l.county, l.postcode, l.lat, l.lng, l.hours);
    }
  });
  return lockers.length;
}

export function seedStore() {
  tx(() => {
    for (const [k, v] of Object.entries(DEFAULT_SETTINGS)) if (getSetting(k) === null) setSetting(k, v);
    for (const m of DELIVERY_METHODS) {
      run(`INSERT OR IGNORE INTO delivery_methods(code, type, carrier, name, description, eta, price, free_over, cod_allowed, sort)
           VALUES (?,?,?,?,?,?,?,?,?,?)`, m.code, m.type, m.carrier, m.name, m.description, m.eta, m.price, m.free_over, m.cod_allowed, m.sort);
    }
    for (const p of PROMO_CODES) {
      run(`INSERT OR IGNORE INTO promo_codes(code, description, type, value, min_subtotal, max_discount, max_uses, ends_at, first_order_only)
           VALUES (?,?,?,?,?,?,?,?,?)`, p.code, p.description, p.type, p.value, p.min_subtotal, p.max_discount, p.max_uses, p.ends_at || null, p.first_order_only ? 1 : 0);
    }
  });
}

export async function ensureAdmin({ log = true } = {}) {
  if (one("SELECT id FROM users WHERE role = 'admin' LIMIT 1")) return null;
  let password = config.admin.password;
  if (!password) {
    if (config.production) throw new Error('Setează ADMIN_PASSWORD pentru a crea contul de administrator.');
    password = 'Livada2026!';
  }
  run(`INSERT INTO users(email, password_hash, first_name, last_name, role, email_verified_at, admin_lang)
       VALUES (?,?,?,?, 'admin', datetime('now'), 'ro')`, config.admin.email, await hashPassword(password), 'Administrator', 'Livada');
  if (log) console.log(`[livada] cont admin creat: ${config.admin.email}${config.admin.password ? '' : ' / parola demo: Livada2026!'}`);
  return { email: config.admin.email, password };
}

// ─── date demo pentru panoul de administrare ────────────────────────────────
function rng(seed) {
  let s = seed >>> 0;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32);
}

const FIRST = ['Andrei', 'Ioana', 'Mihai', 'Elena', 'Alexandru', 'Maria', 'Ștefan', 'Ana', 'Radu', 'Cristina', 'Bogdan', 'Raluca',
  'Vlad', 'Andreea', 'George', 'Diana', 'Tudor', 'Bianca', 'Matei', 'Irina', 'Cosmin', 'Larisa', 'Adrian', 'Oana', 'Paul', 'Simona'];
const LAST = ['Popescu', 'Ionescu', 'Popa', 'Dumitru', 'Stan', 'Stoica', 'Gheorghe', 'Rusu', 'Munteanu', 'Matei', 'Constantin',
  'Marin', 'Tudor', 'Florea', 'Barbu', 'Nistor', 'Dinu', 'Moldovan', 'Lazăr', 'Ene', 'Toma', 'Sandu', 'Cristea', 'Mocanu'];
const CITIES = [['București', 'București', '010101'], ['Cluj-Napoca', 'Cluj', '400001'], ['Iași', 'Iași', '700001'],
  ['Timișoara', 'Timiș', '300001'], ['Brașov', 'Brașov', '500001'], ['Constanța', 'Constanța', '900001'],
  ['Sibiu', 'Sibiu', '550001'], ['Oradea', 'Bihor', '410001'], ['Ploiești', 'Prahova', '100001'], ['Craiova', 'Dolj', '200001']];
const STREETS = ['Str. Mihai Eminescu', 'Bd. Unirii', 'Str. Republicii', 'Calea Victoriei', 'Str. Avram Iancu', 'Bd. Independenței',
  'Str. Florilor', 'Aleea Teilor', 'Str. Libertății', 'Bd. 1 Decembrie 1918'];

export async function seedDemo({ orders = 160 } = {}) {
  if (one('SELECT COUNT(*) n FROM orders').n > 0) return 0;
  const R = rng(20261011);
  const pick = (a) => a[Math.floor(R() * a.length)];
  const pw = await hashPassword(randomToken(12));
  const variants = all(`SELECT v.id, v.sku, v.mpn, v.price, v.options, p.id pid, p.name, p.slug, p.images, p.image_key, p.options popts, c.slug cat
                        FROM variants v JOIN products p ON p.id = v.product_id JOIN categories c ON c.id = p.category_id
                        WHERE p.preorder = 0`);
  const byCat = {};
  for (const v of variants) (byCat[v.cat] ||= []).push(v);
  const methods = all('SELECT * FROM delivery_methods');
  const lockers = all("SELECT * FROM lockers WHERE provider IN ('easybox','fanbox') ORDER BY id");
  const customers = [];
  tx(() => {
    for (let i = 0; i < 48; i++) {
      const fn = pick(FIRST), ln = pick(LAST);
      const email = `${fold(fn)}.${fold(ln)}${i}@example.com`;
      const days = Math.floor(R() * 120);
      const r = run(`INSERT INTO users(email, password_hash, first_name, last_name, phone, email_verified_at, marketing_opt_in, created_at)
                     VALUES (?,?,?,?,?, datetime('now', ?), ?, datetime('now', ?))`,
      email, pw, fn, ln, `07${Math.floor(20000000 + R() * 79999999)}`, `-${days} days`, R() < 0.4 ? 1 : 0, `-${days} days`);
      customers.push({ id: Number(r.lastInsertRowid), fn, ln, email, days });
    }
  });
  const prefix = getSetting('store').order_prefix;
  const catWeights = [['iphone', 30], ['accesorii', 30], ['airpods', 14], ['mac', 10], ['watch', 9], ['ipad', 9], ['tv-home', 5]];
  const wsum = catWeights.reduce((s, [, w]) => s + w, 0);
  const pickCat = () => { let x = R() * wsum; for (const [c, w] of catWeights) { if ((x -= w) < 0) return c; } return 'accesorii'; };
  const label = (v) => {
    const o = JSON.parse(v.options);
    const popts = JSON.parse(v.popts);
    return popts.map((po) => po.values.find((x) => x.value === o[po.key])?.label).filter(Boolean).join(' · ');
  };
  const imageOf = (v) => {
    const imgs = JSON.parse(v.images);
    const o = JSON.parse(v.options);
    const key = v.image_key && imgs[o[v.image_key]] ? o[v.image_key] : Object.keys(imgs)[0];
    return imgs[key]?.[0]?.['480'] || null;
  };
  tx(() => {
    for (let n = 0; n < orders; n++) {
      const daysAgo = Math.floor((R() ** 1.4) * 75);
      const minutes = Math.floor(R() * 14 * 60) + 8 * 60;
      const c = R() < 0.7 ? pick(customers) : null;
      const fn = c?.fn || pick(FIRST), ln = c?.ln || pick(LAST);
      const email = c?.email || `${fold(fn)}.${fold(ln)}.g${n}@example.com`;
      const [city, county, zip] = pick(CITIES);
      const items = [];
      const nItems = R() < 0.65 ? 1 : R() < 0.85 ? 2 : 3;
      for (let k = 0; k < nItems; k++) {
        const list = byCat[k === 0 ? pickCat() : (R() < 0.7 ? 'accesorii' : pickCat())];
        const v = pick(list);
        if (items.find((x) => x.v.id === v.id)) continue;
        items.push({ v, qty: v.cat === 'accesorii' && R() < 0.2 ? 2 : 1 });
      }
      const subtotal = items.reduce((s, x) => s + x.v.price * x.qty, 0);
      const mType = R();
      const code = mType < 0.42 ? pick(['locker_easybox', 'locker_easybox', 'locker_fanbox'])
        : mType < 0.9 ? pick(['courier_fan', 'courier_sameday', 'courier_cargus', 'courier_dpd', 'courier_gls'])
          : mType < 0.95 ? 'pickup_showroom' : 'express_bucuresti';
      const method = methods.find((m) => m.code === code);
      const fee = method.free_over && subtotal >= method.free_over ? 0 : method.price;
      const pay = R();
      const payment = pay < 0.55 ? 'card' : pay < 0.85 ? 'cod' : pay < 0.95 ? 'installments' : 'transfer';
      const codFee = payment === 'cod' ? 999 : 0;
      const total = subtotal + fee + codFee;
      const status = daysAgo > 6 ? (R() < 0.94 ? 'delivered' : R() < 0.5 ? 'cancelled' : 'returned')
        : daysAgo > 2 ? pick(['shipped', 'delivered', 'delivered']) : daysAgo > 0 ? pick(['confirmed', 'processing', 'shipped']) : pick(['new', 'new', 'confirmed']);
      const paymentStatus = status === 'cancelled' ? (payment === 'card' ? 'refunded' : 'failed')
        : payment === 'cod' ? (status === 'delivered' ? 'paid' : 'cod') : payment === 'transfer' && status === 'new' ? 'pending' : 'paid';
      let delivery;
      if (method.type === 'locker') {
        const l = lockers.length ? pick(lockers) : null;
        delivery = { type: 'locker', carrier: method.carrier, locker: l && { id: l.id, name: l.name, address: l.address, city: l.city, county: l.county } };
      } else if (method.type === 'pickup') {
        delivery = { type: 'pickup', carrier: 'livada', store: getSetting('showroom') };
      } else {
        delivery = { type: method.type, carrier: method.carrier, address: { first_name: fn, last_name: ln, county, city, street: `${pick(STREETS)} nr. ${1 + Math.floor(R() * 120)}`, postcode: zip } };
      }
      const created = `-${daysAgo} days`;
      const year = new Date().getUTCFullYear() % 100;
      const number = `${prefix}${year}-${String(10240 + n).padStart(5, '0')}`;
      const r = run(`INSERT INTO orders(number, public_token, user_id, email, phone, first_name, last_name, billing, delivery_method, delivery,
                      payment_method, payment_status, status, subtotal, shipping_fee, cod_fee, total, vat, awb, created_at, updated_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, datetime('now', ?, ?), datetime('now', ?, ?))`,
      number, randomToken(18), c?.id ?? null, email, `07${Math.floor(20000000 + R() * 79999999)}`, fn, ln,
      JSON.stringify({ first_name: fn, last_name: ln, county, city, street: `${pick(STREETS)} nr. ${1 + Math.floor(R() * 120)}`, postcode: zip }),
      method.code, JSON.stringify(delivery), payment, paymentStatus, status, subtotal, fee, codFee, total,
      Math.round(total - total / 1.21), ['shipped', 'delivered', 'returned'].includes(status) && method.type !== 'pickup' ? String(Math.floor(1e9 + R() * 8e9)) : null,
      created, `-${1440 - minutes} minutes`, created, `-${1440 - minutes - 30} minutes`);
      const oid = Number(r.lastInsertRowid);
      for (const it of items) {
        run(`INSERT INTO order_items(order_id, product_id, variant_id, sku, mpn, name, variant_label, image, slug, unit_price, qty, line_total)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`, oid, it.v.pid, it.v.id, it.v.sku, it.v.mpn, it.v.name, label(it.v), imageOf(it.v), it.v.slug,
        it.v.price, it.qty, it.v.price * it.qty);
      }
      run("INSERT INTO order_events(order_id, status, note, actor, created_at) VALUES (?, 'new', 'Comandă plasată', 'customer', datetime('now', ?, ?))",
        oid, created, `-${1440 - minutes} minutes`);
      if (status !== 'new') run("INSERT INTO order_events(order_id, status, note, actor, created_at) VALUES (?, ?, '', 'system', datetime('now', ?, ?))",
        oid, status, created, `-${1440 - minutes - 30} minutes`);
    }
    for (let i = 0; i < 37; i++) run('INSERT OR IGNORE INTO newsletter(email, created_at) VALUES (?, datetime(\'now\', ?))', `abonat${i}@example.com`, `-${Math.floor(R() * 90)} days`);
    run(`INSERT INTO contact_messages(name, email, phone, topic, message, created_at) VALUES
      ('Mihai Stoica', 'mihai.stoica@example.com', '0722000111', 'Disponibilitate', 'Bună ziua, când reveniți în stoc cu iPhone 18 Pro Max 512 GB Glacier?', datetime('now','-3 hours')),
      ('Ioana Marin', 'ioana.marin@example.com', '', 'Factură PJ', 'Aș dori factura pe firmă pentru comanda de săptămâna trecută. Mulțumesc!', datetime('now','-1 day'))`);
  });
  return orders;
}

export async function seedAll({ demo = false, log = true } = {}) {
  openDb();
  seedStore();
  const n = one('SELECT COUNT(*) n FROM products').n === 0 ? seedCatalog() : 0;
  const l = one('SELECT COUNT(*) n FROM lockers').n === 0 ? seedLockers() : 0;
  await ensureAdmin({ log });
  const d = demo ? await seedDemo() : 0;
  clearSettingsCache();
  if (log && (n || l || d)) console.log(`[livada] seed: ${n} produse, ${l} lockere${d ? `, ${d} comenzi demo` : ''}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await seedAll({ demo: process.argv.includes('--demo') });
}
