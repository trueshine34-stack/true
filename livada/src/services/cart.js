import { one, all, run, tx, json, getSetting } from '../db/index.js';
import { sha256 } from '../lib/security.js';
import { imageFor, variantLabel, hydrate } from './catalog.js';
import { methodByCode, feeFor } from './shipping.js';
import { vatIncluded } from '../lib/format.js';
import { t, locale } from '../lib/i18n.js';

export const MAX_QTY = 5;          // limită pe produs (anti-revânzare la lansări)
export const MAX_PREORDER_QTY = 2;

export class CartError extends Error {
  constructor(message, status = 400) { super(message); this.status = status; }
}

export const cartIdFromToken = (token) => (token ? sha256(`cart:${token}`) : null);

export function ensureCart(cartId, userId = null) {
  run(`INSERT INTO carts(id, user_id) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET updated_at = datetime('now'),
       user_id = COALESCE(excluded.user_id, carts.user_id)`, cartId, userId);
}

function variantRow(variantId) {
  return one(`SELECT v.*, p.slug, p.name, p.options AS p_options, p.images, p.image_key, p.preorder, p.active AS p_active, p.release_note,
              c.slug AS category FROM variants v JOIN products p ON p.id = v.product_id JOIN categories c ON c.id = p.category_id WHERE v.id = ?`, variantId);
}

function limitFor(v) {
  if (v.preorder) return MAX_PREORDER_QTY;
  return Math.min(MAX_QTY, Math.max(0, v.stock));
}

export function addItem(cartId, variantId, qty = 1, userId = null) {
  const v = variantRow(variantId);
  if (!v || !v.active || !v.p_active) throw new CartError(t('Produsul nu mai este disponibil.'), 404);
  const limit = limitFor(v);
  if (limit <= 0) throw new CartError(t('Ne pare rău, varianta aleasă nu mai este în stoc.'));
  ensureCart(cartId, userId);
  const cur = one('SELECT qty FROM cart_items WHERE cart_id = ? AND variant_id = ?', cartId, variantId)?.qty || 0;
  const next = Math.min(limit, cur + Math.max(1, Math.floor(qty)));
  if (next === cur) throw new CartError(v.preorder ? t('Poți precomanda maximum {n} bucăți.', { n: MAX_PREORDER_QTY })
    : limit === 1 ? t('Poți comanda maximum {n} bucată din acest produs.', { n: limit }) : t('Poți comanda maximum {n} bucăți din acest produs.', { n: limit }));
  run(`INSERT INTO cart_items(cart_id, variant_id, qty) VALUES (?,?,?) ON CONFLICT(cart_id, variant_id) DO UPDATE SET qty = excluded.qty`, cartId, variantId, next);
  return next;
}

export function setQty(cartId, variantId, qty) {
  qty = Math.floor(Number(qty));
  if (!qty || qty < 1) return removeItem(cartId, variantId);
  const v = variantRow(variantId);
  if (!v) return removeItem(cartId, variantId);
  run('UPDATE cart_items SET qty = ? WHERE cart_id = ? AND variant_id = ?', Math.min(qty, Math.max(1, limitFor(v))), cartId, variantId);
  run("UPDATE carts SET updated_at = datetime('now') WHERE id = ?", cartId);
}

export function removeItem(cartId, variantId) {
  run('DELETE FROM cart_items WHERE cart_id = ? AND variant_id = ?', cartId, variantId);
}

export const clearCart = (cartId) => tx(() => {
  run('DELETE FROM cart_items WHERE cart_id = ?', cartId);
  run('UPDATE carts SET promo_code = NULL WHERE id = ?', cartId);
});

/** la autentificare: mută produsele din coșul anonim în coșul legat de cont */
export function mergeCarts(fromId, intoId, userId) {
  if (!fromId || fromId === intoId) return;
  tx(() => {
    ensureCart(intoId, userId);
    for (const it of all('SELECT variant_id, qty FROM cart_items WHERE cart_id = ?', fromId)) {
      try {
        const cur = one('SELECT qty FROM cart_items WHERE cart_id = ? AND variant_id = ?', intoId, it.variant_id)?.qty || 0;
        const v = variantRow(it.variant_id);
        if (!v) continue;
        const q = Math.min(limitFor(v) || 1, Math.max(cur, it.qty));
        run('INSERT INTO cart_items(cart_id, variant_id, qty) VALUES (?,?,?) ON CONFLICT(cart_id, variant_id) DO UPDATE SET qty = excluded.qty', intoId, it.variant_id, q);
      } catch { /* ignoră produsele dispărute */ }
    }
    const promo = one('SELECT promo_code FROM carts WHERE id = ?', fromId)?.promo_code;
    if (promo) run('UPDATE carts SET promo_code = COALESCE(promo_code, ?) WHERE id = ?', promo, intoId);
    run('DELETE FROM carts WHERE id = ?', fromId);
  });
}

// ─── coduri promoționale ───────────────────────────────────────────────────
export function findPromo(code) {
  if (!code) return null;
  return one('SELECT * FROM promo_codes WHERE code = ? COLLATE NOCASE', String(code).trim());
}

/** clientul (cont sau email) are deja o comandă necontestată? */
export function hasOrdered(customer) {
  if (!customer || (!customer.userId && !customer.email)) return false;
  return !!one("SELECT 1 FROM orders WHERE (user_id = ? OR email = ? COLLATE NOCASE) AND status NOT IN ('cancelled') LIMIT 1",
    customer.userId ?? null, customer.email || null);
}

/**
 * @param {{userId?:number, email?:string}|null} customer — pentru codurile valabile doar la prima comandă
 * @returns {string|null} motivul pentru care codul nu se aplică
 */
export function promoProblem(p, subtotal, customer = null) {
  if (!p || !p.active) return t('Codul promoțional nu există.');
  const now = new Date().toISOString().replace('T', ' ').slice(0, 19);
  if (p.starts_at && p.starts_at > now) return t('Codul nu este încă activ.');
  if (p.ends_at && p.ends_at < now) return t('Codul promoțional a expirat.');
  if (p.max_uses !== null && p.used >= p.max_uses) return t('Codul a atins numărul maxim de utilizări.');
  if (subtotal < p.min_subtotal) return t('Codul se aplică la comenzi de minimum {sum} lei.', { sum: (p.min_subtotal / 100).toLocaleString(locale()) });
  if (p.first_order_only && hasOrdered(customer)) return t('Codul {code} este valabil doar la prima comandă.', { code: p.code });
  return null;
}

export function promoDiscount(p, subtotal) {
  if (!p) return 0;
  if (p.type === 'percent') {
    const d = Math.round((subtotal * p.value) / 100);
    return p.max_discount ? Math.min(d, p.max_discount) : d;
  }
  if (p.type === 'fixed') return Math.min(p.value, subtotal);
  return 0;
}

export function setPromo(cartId, code) {
  ensureCart(cartId);
  run('UPDATE carts SET promo_code = ? WHERE id = ?', code ? String(code).trim().toUpperCase() : null, cartId);
}

// ─── citire + calcul total ─────────────────────────────────────────────────
export function loadCart(cartId) {
  const cart = cartId ? one('SELECT * FROM carts WHERE id = ?', cartId) : null;
  const rows = cart ? all(`SELECT ci.qty, v.id AS variant_id, v.sku, v.mpn, v.price, v.compare_at, v.stock, v.options, v.active,
      p.id AS product_id, p.slug, p.name, p.options AS p_options, p.images, p.image_key, p.preorder, p.release_note, p.active AS p_active, c.slug AS category
      FROM cart_items ci JOIN variants v ON v.id = ci.variant_id JOIN products p ON p.id = v.product_id JOIN categories c ON c.id = p.category_id
      WHERE ci.cart_id = ? ORDER BY ci.added_at`, cartId) : [];
  const items = rows.map((r) => {
    // pe /en, hydrate() traduce numele, nota de lansare și opțiunile (overlay-ul din catalog-i18n.js)
    const product = hydrate({ slug: r.slug, name: r.name, release_note: r.release_note, options: r.p_options, images: r.images, image_key: r.image_key, description: '[]', highlights: '[]', specs: '[]', in_box: '[]' });
    const opts = json(r.options, {});
    const limit = r.preorder ? MAX_PREORDER_QTY : Math.min(MAX_QTY, r.stock);
    const available = r.active && r.p_active && (r.preorder || r.stock > 0);
    return {
      variant_id: r.variant_id, product_id: r.product_id, sku: r.sku, mpn: r.mpn, slug: r.slug, name: product.name, category: r.category,
      options: opts, label: variantLabel(product, opts), image: imageFor(product, opts, '480'),
      price: r.price, compare_at: r.compare_at, qty: r.qty, max: Math.max(1, limit), line: r.price * r.qty,
      preorder: !!r.preorder, release_note: product.release_note, available, stock_issue: available && !r.preorder && r.qty > r.stock,
    };
  });
  return { id: cartId, promo_code: cart?.promo_code || null, items };
}

/**
 * Calculează totalurile coșului.
 * @param {{deliveryCode?: string, payment?: string}} opts
 */
export function priceCart(cart, { deliveryCode, payment, customer } = {}) {
  const store = getSetting('store') || {};
  const items = cart.items.filter((i) => i.available);
  const subtotal = items.reduce((s, i) => s + i.line, 0);
  const count = items.reduce((s, i) => s + i.qty, 0);
  const savings = items.reduce((s, i) => s + (i.compare_at ? (i.compare_at - i.price) * i.qty : 0), 0);

  let promo = null, promoError = null, discount = 0, freeShipping = false;
  if (cart.promo_code) {
    const p = findPromo(cart.promo_code);
    promoError = promoProblem(p, subtotal, customer);
    if (!promoError) {
      promo = { code: p.code, type: p.type, description: p.description };
      discount = promoDiscount(p, subtotal);
      freeShipping = p.type === 'shipping';
    }
  }
  const method = deliveryCode ? methodByCode(deliveryCode) : null;
  const shipping = method ? feeFor(method, subtotal - discount, { freeShipping }) : null;
  const codAllowed = !method || !!method.cod_allowed;
  const codFee = payment === 'cod' ? store.cod_fee ?? 999 : 0;
  const total = Math.max(0, subtotal - discount + (shipping || 0) + codFee);
  const freeThreshold = 50000;
  return {
    count, subtotal, discount, savings, promo, promoError,
    shipping, codFee, codAllowed: codAllowed && total <= (store.cod_max ?? 1500000), total, vat: vatIncluded(total, store.vat ?? 21),
    freeShippingLeft: Math.max(0, freeThreshold - (subtotal - discount)),
    freeShippingProgress: Math.min(1, (subtotal - discount) / freeThreshold),
    hasPreorder: items.some((i) => i.preorder),
    unavailable: cart.items.filter((i) => !i.available),
  };
}

export function cartCount(cartId) {
  if (!cartId) return 0;
  return one('SELECT COALESCE(SUM(qty), 0) n FROM cart_items WHERE cart_id = ?', cartId).n;
}
