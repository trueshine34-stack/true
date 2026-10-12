import { one, all, run, tx, json, getSetting } from '../db/index.js';
import { config } from '../config.js';
import { randomToken } from '../lib/security.js';
import { ValidationError, clean, cleanText, isEmail, normalizePhone, isCui, address as parseAddress } from '../lib/validate.js';
import { sendMail } from '../lib/mailer.js';
import * as T from './mail-templates.js';
import { loadCart, priceCart, clearCart, findPromo } from './cart.js';
import { methodByCode, lockerById, estimate, carrier } from './shipping.js';
import { saveAddress } from './users.js';
import { t, lang } from '../lib/i18n.js';

export const STATUSES = {
  new: { label: 'Nouă', tone: 'blue' },
  confirmed: { label: 'Confirmată', tone: 'leaf' },
  processing: { label: 'În pregătire', tone: 'amber' },
  shipped: { label: 'Expediată', tone: 'violet' },
  ready: { label: 'Gata de ridicare', tone: 'violet' },
  delivered: { label: 'Livrată', tone: 'green' },
  cancelled: { label: 'Anulată', tone: 'red' },
  returned: { label: 'Returnată', tone: 'gray' },
};
export const PAYMENT_STATUSES = {
  pending: { label: 'În așteptare', tone: 'amber' }, paid: { label: 'Plătită', tone: 'green' }, cod: { label: 'Ramburs', tone: 'blue' },
  refunded: { label: 'Rambursată', tone: 'gray' }, failed: { label: 'Eșuată', tone: 'red' },
};
export const PAYMENT_METHODS = {
  card: 'Card online', cod: 'Ramburs la livrare', transfer: 'Transfer bancar (OP)', installments: 'Rate fără dobândă',
};
// tranziții permise din panoul de administrare
export const TRANSITIONS = {
  new: ['confirmed', 'processing', 'cancelled'],
  confirmed: ['processing', 'shipped', 'ready', 'cancelled'],
  processing: ['shipped', 'ready', 'cancelled'],
  shipped: ['delivered', 'returned'],
  ready: ['delivered', 'cancelled'],
  delivered: ['returned'],
  cancelled: [],
  returned: [],
};

export function hydrateOrder(o) {
  if (!o) return null;
  const d = json(o.delivery, {});
  const method = one('SELECT * FROM delivery_methods WHERE code = ?', o.delivery_method);
  return {
    ...o, billing: json(o.billing, {}), company: json(o.company, null), delivery: d,
    delivery_title: method?.name || o.delivery_method, delivery_label: method?.type === 'courier' || method?.type === 'express' ? carrier(method.carrier).name : '',
    delivery_eta: null, method,
  };
}

export const orderItems = (orderId) => all('SELECT * FROM order_items WHERE order_id = ? ORDER BY id', orderId);
export const orderEvents = (orderId) => all('SELECT * FROM order_events WHERE order_id = ? ORDER BY id', orderId);
export const orderById = (id) => hydrateOrder(one('SELECT * FROM orders WHERE id = ?', id));
export const orderByToken = (t) => (t ? hydrateOrder(one('SELECT * FROM orders WHERE public_token = ?', t)) : null);
export const orderByNumber = (n) => hydrateOrder(one('SELECT * FROM orders WHERE number = ?', n));
export const ordersForUser = (userId) => all('SELECT * FROM orders WHERE user_id = ? ORDER BY id DESC', userId).map(hydrateOrder);
export const orderLink = (o) => `${config.publicUrl}/comanda/${o.public_token}`;

function nextNumber() {
  const prefix = (getSetting('store') || {}).order_prefix || 'LV';
  const yy = String(new Date().getUTCFullYear() % 100).padStart(2, '0');
  const last = one('SELECT number FROM orders WHERE number LIKE ? ORDER BY id DESC LIMIT 1', `${prefix}${yy}-%`);
  const n = last ? Number(last.number.split('-').pop()) + 1 : 10001;
  return `${prefix}${yy}-${String(n).padStart(5, '0')}`;
}

/**
 * Validează formularul de checkout și creează comanda.
 * @returns {object} comanda creată
 */
export async function placeOrder({ cartId, user, body, ip }) {
  const cart = loadCart(cartId);
  if (!cart.items.length) throw Object.assign(new Error(t('Coșul tău este gol.')), { status: 400 });
  const unavailable = cart.items.filter((i) => !i.available || i.stock_issue);
  if (unavailable.length) {
    throw Object.assign(new Error(t('Stoc insuficient pentru: {names}. Actualizează coșul.', { names: unavailable.map((i) => i.name).join(', ') })), { status: 409 });
  }

  const errors = {};
  const email = clean(body.email, 190).toLowerCase();
  const phone = normalizePhone(body.phone);
  if (!isEmail(email)) errors.email = t('Adresa de email nu pare corectă.');
  if (!phone) errors.phone = t('Numărul de telefon nu este valid (ex: 0722 123 456).');

  const method = methodByCode(body.delivery_method);
  if (!method) errors.delivery_method = t('Alege metoda de livrare.');
  const customerType = body.customer_type === 'pj' ? 'pj' : 'pf';
  let delivery = null, shippingAddr = null;
  const firstName = clean(body.first_name, 60), lastName = clean(body.last_name, 60);
  if (!firstName) errors.first_name = t('Completează prenumele.');
  if (!lastName) errors.last_name = t('Completează numele.');

  if (method) {
    if (method.type === 'courier' || method.type === 'express') {
      shippingAddr = parseAddress({ ...body, ship_first_name: firstName, ship_last_name: lastName }, 'ship_', errors);
      if (method.type === 'express' && shippingAddr.county && !['București', 'Ilfov'].includes(shippingAddr.county)) {
        errors.delivery_method = t('Livrarea în aceeași zi este disponibilă doar în București și Ilfov.');
      }
      delivery = { type: method.type, carrier: method.carrier, address: shippingAddr };
    } else if (method.type === 'locker') {
      const l = lockerById(clean(body.locker_id, 60));
      if (!l) errors.locker_id = t('Alege un locker de pe hartă.');
      else if (l.provider !== method.carrier) errors.locker_id = t('Lockerul ales nu aparține rețelei selectate.');
      else delivery = { type: 'locker', carrier: method.carrier, locker: { id: l.id, name: l.name, address: l.address, city: l.city, county: l.county, postcode: l.postcode, lat: l.lat, lng: l.lng, hours: l.hours } };
    } else {
      delivery = { type: 'pickup', carrier: 'livada', store: getSetting('showroom') };
    }
  }

  // facturare
  let billing;
  if (body.billing_same === 'on' && shippingAddr) billing = { ...shippingAddr };
  else billing = parseAddress({ ...body, bill_first_name: firstName, bill_last_name: lastName }, 'bill_', errors);
  let company = null;
  if (customerType === 'pj') {
    company = { name: clean(body.company_name, 120), cui: clean(body.company_cui, 14).toUpperCase().replace(/\s/g, ''), reg_com: clean(body.company_reg, 40) };
    if (company.name.length < 2) errors.company_name = t('Completează denumirea firmei.');
    if (!isCui(company.cui)) errors.company_cui = t('CUI invalid (verifică cifra de control).');
  }

  const payment = ['card', 'cod', 'transfer', 'installments'].includes(body.payment_method) ? body.payment_method : null;
  if (!payment) errors.payment_method = t('Alege metoda de plată.');
  else if (['card', 'installments'].includes(payment) && !config.paymentMock) errors.payment_method = t('Plata online nu este disponibilă momentan. Alege ramburs sau transfer bancar.');
  if (!(body.terms === 'on' || body.terms === true)) errors.terms = t('Trebuie să accepți Termenii și condițiile.');

  const totals = priceCart(cart, { deliveryCode: method?.code, payment, customer: { userId: user?.id, email } });
  if (payment === 'cod' && !totals.codAllowed) errors.payment_method = method && !method.cod_allowed ? t('Plata ramburs nu este disponibilă pentru {method}.', { method: t(method.name) }) : t('Plata ramburs nu este disponibilă pentru această valoare.');
  if (payment === 'installments' && totals.total < ((getSetting('store') || {}).installments_min ?? 50000)) errors.payment_method = t('Ratele sunt disponibile pentru comenzi de minimum 500 lei.');
  if (cart.promo_code && totals.promoError) errors.promo = totals.promoError;
  if (Object.keys(errors).length) throw new ValidationError(errors);

  const installments = payment === 'installments' ? [3, 6, 12].includes(Number(body.installments)) ? Number(body.installments) : 6 : null;
  const notes = cleanText(body.notes, 500);

  const order = tx(() => {
    // verificare finală a stocului în tranzacție
    for (const it of cart.items) {
      if (it.preorder) continue;
      const s = one('SELECT stock FROM variants WHERE id = ?', it.variant_id)?.stock ?? 0;
      if (s < it.qty) throw Object.assign(new Error(t('Stoc insuficient pentru {name}.', { name: it.name })), { status: 409 });
    }
    const number = nextNumber();
    const token = randomToken(18);
    const r = run(`INSERT INTO orders(number, public_token, user_id, email, phone, first_name, last_name, customer_type, company, billing,
        delivery_method, delivery, payment_method, payment_status, status, subtotal, discount, shipping_fee, cod_fee, total, vat,
        promo_code, notes, installments, ip, lang) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'new', ?,?,?,?,?,?,?,?,?,?,?)`,
    number, token, user?.id ?? null, email, phone, firstName, lastName, customerType, company ? JSON.stringify(company) : null,
    JSON.stringify(billing), method.code, JSON.stringify(delivery), payment, payment === 'cod' ? 'cod' : 'pending',
    totals.subtotal, totals.discount, totals.shipping || 0, totals.codFee, totals.total, totals.vat, totals.promo?.code || null, notes, installments, ip || '', lang());
    const orderId = Number(r.lastInsertRowid);
    for (const it of cart.items) {
      run(`INSERT INTO order_items(order_id, product_id, variant_id, sku, mpn, name, variant_label, image, slug, unit_price, qty, line_total)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`, orderId, it.product_id, it.variant_id, it.sku, it.mpn, it.name, it.label, it.image, it.slug, it.price, it.qty, it.line);
      if (!it.preorder) run('UPDATE variants SET stock = stock - ? WHERE id = ?', it.qty, it.variant_id);
    }
    if (totals.promo) run('UPDATE promo_codes SET used = used + 1 WHERE code = ? COLLATE NOCASE', totals.promo.code);
    run("INSERT INTO order_events(order_id, status, note, actor) VALUES (?, 'new', 'Comandă plasată pe site', 'customer')", orderId);
    if (user && shippingAddr) saveAddress(user.id, { ...shippingAddr, phone });
    if (user && !user.phone) run('UPDATE users SET phone = ? WHERE id = ?', phone, user.id);
    clearCart(cartId);
    return orderById(orderId);
  });

  order.delivery_eta = order.method ? estimate(order.method) : null;
  await notifyNewOrder(order);
  return order;
}

export async function notifyNewOrder(order) {
  const items = orderItems(order.id);
  const link = orderLink(order);
  const mail = T.orderConfirmation({ order, items, link });
  await sendMail({ to: order.email, subject: mail.subject, html: mail.html, text: mail.text, template: 'order' });
  const admin = T.adminNewOrder({ order, items, link: `${config.publicUrl}/admin/comenzi/${order.id}` });
  await sendMail({ to: config.shopNotifyEmail, subject: admin.subject, html: admin.html, text: admin.text, template: 'admin-order' });
}

/** schimbarea statusului din admin (cu notificare pe email) */
export async function setStatus(orderId, status, { note = '', awb, actor = 'system', notify = true, force = false } = {}) {
  const o = orderById(orderId);
  if (!o) throw Object.assign(new Error('Comanda nu există.'), { status: 404 });
  if (!STATUSES[status]) throw Object.assign(new Error('Status invalid.'), { status: 400 });
  if (!force && status !== o.status && !TRANSITIONS[o.status].includes(status)) {
    throw Object.assign(new Error(`Nu se poate trece din „${STATUSES[o.status].label}” în „${STATUSES[status].label}”.`), { status: 409 });
  }
  tx(() => {
    run("UPDATE orders SET status = ?, awb = COALESCE(?, awb), updated_at = datetime('now') WHERE id = ?", status, awb ? clean(awb, 40) : null, orderId);
    if (status === 'cancelled' && o.status !== 'cancelled') {
      // repunem stocul și marcăm plata
      for (const it of orderItems(orderId)) {
        const pre = one('SELECT preorder FROM products WHERE id = ?', it.product_id)?.preorder;
        if (!pre && it.variant_id) run('UPDATE variants SET stock = stock + ? WHERE id = ?', it.qty, it.variant_id);
      }
      run(`UPDATE orders SET payment_status = CASE WHEN payment_status = 'paid' THEN 'refunded' WHEN payment_status IN ('pending','cod') THEN 'failed' ELSE payment_status END WHERE id = ?`, orderId);
    }
    if (status === 'delivered' && o.payment_method === 'cod') run("UPDATE orders SET payment_status = 'paid' WHERE id = ?", orderId);
    if (status === 'returned') run("UPDATE orders SET payment_status = CASE WHEN payment_status = 'paid' THEN 'refunded' ELSE payment_status END WHERE id = ?", orderId);
    run('INSERT INTO order_events(order_id, status, note, actor, notified) VALUES (?,?,?,?,?)', orderId, status, clean(note, 500), actor, notify ? 1 : 0);
  });
  const updated = orderById(orderId);
  if (notify && status !== o.status) {
    const mail = T.orderStatus({ order: updated, items: orderItems(orderId), link: orderLink(updated), note });
    await sendMail({ to: updated.email, subject: mail.subject, html: mail.html, text: mail.text, template: `status-${status}` });
  }
  return updated;
}

export function setPaymentStatus(orderId, status, actor = 'system') {
  if (!PAYMENT_STATUSES[status]) throw Object.assign(new Error('Status de plată invalid.'), { status: 400 });
  run("UPDATE orders SET payment_status = ?, updated_at = datetime('now') WHERE id = ?", status, orderId);
  run('INSERT INTO order_events(order_id, status, note, actor) VALUES (?, ?, ?, ?)', orderId, `payment:${status}`, `Plată: ${PAYMENT_STATUSES[status].label}`, actor);
  return orderById(orderId);
}

/** plata simulată cu cardul (mediu de test — se înlocuiește cu Netopia / Stripe / EuPlătesc) */
export async function completeCardPayment(order) {
  if (order.payment_status === 'paid') return order;
  setPaymentStatus(order.id, 'paid', 'gateway');
  if (order.status === 'new') return setStatus(order.id, 'confirmed', { note: 'Plată cu cardul confirmată', actor: 'gateway', notify: false });
  return orderById(order.id);
}

export function listOrders({ q, status, payment, delivery, from, to, limit = 50, offset = 0 } = {}) {
  const where = ['1=1'];
  const p = [];
  if (q) {
    where.push('(o.number LIKE ? OR o.email LIKE ? OR o.phone LIKE ? OR (o.first_name || \' \' || o.last_name) LIKE ? OR o.awb LIKE ?)');
    const like = `%${q.trim()}%`;
    p.push(like, like, like, like, like);
  }
  if (status) { where.push('o.status = ?'); p.push(status); }
  if (payment) { where.push('o.payment_method = ?'); p.push(payment); }
  if (delivery) { where.push('o.delivery_method LIKE ?'); p.push(`${delivery}%`); }
  if (from) { where.push('o.created_at >= ?'); p.push(from); }
  if (to) { where.push('o.created_at < ?'); p.push(to); }
  const w = where.join(' AND ');
  const total = one(`SELECT COUNT(*) n FROM orders o WHERE ${w}`, ...p).n;
  const rows = all(`SELECT o.*, (SELECT SUM(qty) FROM order_items i WHERE i.order_id = o.id) AS items_count FROM orders o WHERE ${w} ORDER BY o.id DESC LIMIT ? OFFSET ?`, ...p, limit, offset)
    .map(hydrateOrder);
  return { total, rows };
}

export const promoInfo = (code) => findPromo(code);
