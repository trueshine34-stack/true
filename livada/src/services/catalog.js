import { one, all, json } from '../db/index.js';
import { fold } from '../lib/format.js';

export function hydrate(p) {
  if (!p) return null;
  return {
    ...p,
    description: json(p.description, []),
    highlights: json(p.highlights, []),
    specs: json(p.specs, []),
    in_box: json(p.in_box, []),
    options: json(p.options, []),
    images: json(p.images, {}),
    featured: !!p.featured,
    preorder: !!p.preorder,
    active: !!p.active,
  };
}

/** prima imagine pentru o variantă (sau pentru produs) */
export function imageFor(product, variantOptions = {}, size = '480', index = 0) {
  const imgs = product.images || {};
  const key = product.image_key && imgs[variantOptions[product.image_key]] ? variantOptions[product.image_key] : Object.keys(imgs)[0];
  const list = imgs[key] || [];
  return (list[index] || list[0])?.[size] || null;
}

export function variantLabel(product, opts) {
  return product.options.map((o) => o.values.find((v) => v.value === opts[o.key])?.label).filter(Boolean).join(' · ');
}

export function categories() {
  return all(`SELECT c.*, (SELECT COUNT(*) FROM products p WHERE p.category_id = c.id AND p.active = 1) AS count
              FROM categories c ORDER BY c.sort`).map((c) => ({ ...c, groups: json(c.groups, []) }));
}

export const categoryBySlug = (slug) => {
  const c = one('SELECT * FROM categories WHERE slug = ?', slug);
  return c && { ...c, groups: json(c.groups, []) };
};

const CARD_SQL = `SELECT p.*, c.slug AS category_slug, c.name AS category_name,
  (SELECT MIN(price) FROM variants v WHERE v.product_id = p.id AND v.active = 1) AS price_from,
  (SELECT MAX(price) FROM variants v WHERE v.product_id = p.id AND v.active = 1) AS price_to,
  (SELECT compare_at FROM variants v WHERE v.product_id = p.id AND v.active = 1 ORDER BY price LIMIT 1) AS compare_from,
  (SELECT SUM(stock) FROM variants v WHERE v.product_id = p.id AND v.active = 1) AS stock_total,
  (SELECT COUNT(*) FROM variants v WHERE v.product_id = p.id AND v.active = 1) AS variant_count
  FROM products p JOIN categories c ON c.id = p.category_id`;

function card(row) {
  const p = hydrate(row);
  const colorOpt = p.options.find((o) => o.key === 'color');
  return {
    id: p.id, slug: p.slug, name: p.name, tagline: p.tagline, badge: p.badge, grp: p.grp, preorder: p.preorder,
    category: p.category_slug, category_name: p.category_name, featured: p.featured,
    price_from: p.price_from, price_to: p.price_to, compare_from: p.compare_from,
    in_stock: p.preorder || p.stock_total > 0, variant_count: p.variant_count,
    image: imageFor(p, {}, '480'), image_lg: imageFor(p, {}, '1000'),
    colors: colorOpt ? colorOpt.values.map((v) => ({ value: v.value, label: v.label, hex: v.hex, image: p.images[v.value]?.[0]?.['480'] })) : [],
    created_at: p.created_at,
  };
}

const SORTS = {
  recommended: 'p.featured DESC, p.sort',
  'price-asc': 'price_from ASC',
  'price-desc': 'price_from DESC',
  new: "(p.badge IN ('Nou','Precomandă')) DESC, p.created_at DESC, p.sort",
  name: 'p.name COLLATE NOCASE',
};

export function listProducts({ category, group, sort = 'recommended', min, max, inStock, featured, ids, promo, limit = 200 } = {}) {
  const where = ['p.active = 1'];
  const params = [];
  if (category) { where.push('c.slug = ?'); params.push(category); }
  if (group) { where.push('p.grp = ?'); params.push(group); }
  if (featured) where.push('p.featured = 1');
  if (ids?.length) { where.push(`p.id IN (${ids.map(() => '?').join(',')})`); params.push(...ids); }
  let rows = all(`${CARD_SQL} WHERE ${where.join(' AND ')} ORDER BY ${SORTS[sort] || SORTS.recommended} LIMIT ?`, ...params, limit).map(card);
  if (min) rows = rows.filter((r) => r.price_from >= min);
  if (max) rows = rows.filter((r) => r.price_from <= max);
  if (inStock) rows = rows.filter((r) => r.in_stock);
  if (promo) rows = rows.filter((r) => r.compare_from);
  if (ids?.length && sort === 'ids') rows.sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id));
  return rows;
}

export function productBySlug(slug, { includeInactive = false } = {}) {
  const row = one(`SELECT p.*, c.slug AS category_slug, c.name AS category_name, c.title AS category_title
                   FROM products p JOIN categories c ON c.id = p.category_id WHERE p.slug = ? ${includeInactive ? '' : 'AND p.active = 1'}`, slug);
  if (!row) return null;
  const p = hydrate(row);
  p.variants = all('SELECT * FROM variants WHERE product_id = ? AND active = 1 ORDER BY sort, price', p.id)
    .map((v) => ({ ...v, options: json(v.options, {}) }));
  // păstrăm doar valorile de opțiuni care au variante active
  p.options = p.options.map((o) => ({ ...o, values: o.values.filter((val) => p.variants.some((v) => v.options[o.key] === val.value)) }))
    .filter((o) => o.values.length);
  return p;
}

export const productById = (id) => {
  const r = one('SELECT slug FROM products WHERE id = ?', id);
  return r ? productBySlug(r.slug, { includeInactive: true }) : null;
};

/** căutare full-text, fără diacritice, cu prefix („husa 18” → Husă … iPhone 18) */
export function search(q, limit = 24) {
  const terms = fold(q).replace(/["*^():]/g, ' ').split(/[^a-z0-9]+/).filter(Boolean).slice(0, 8);
  if (!terms.length) return [];
  const match = terms.map((t) => `"${t}"*`).join(' AND ');
  let ids = [];
  try {
    ids = all(`SELECT product_id FROM product_search WHERE product_search MATCH ? ORDER BY bm25(product_search, 10.0, 3.0, 2.0, 2.0, 1.0) LIMIT ?`, match, limit)
      .map((r) => r.product_id);
  } catch { ids = []; }
  if (!ids.length) return [];
  return listProducts({ ids, sort: 'ids' });
}

export function suggestions(q, limit = 6) {
  return search(q, limit).map((p) => ({ slug: p.slug, name: p.name, image: p.image, price_from: p.price_from, category: p.category_name }));
}

/** accesorii potrivite pentru un produs (huse pentru modelul respectiv, încărcătoare, curele...) */
export function relatedFor(product, limit = 8) {
  const picks = [];
  const add = (rows) => { for (const r of rows) if (r.id !== product.id && !picks.find((x) => x.id === r.id)) picks.push(r); };
  const acc = (q) => search(q, 12).filter((p) => p.category === 'accesorii');
  const n = product.name;
  if (/iPhone/.test(n)) {
    const model = n.replace(/^iPhone\s*/, '');
    add(acc(`iPhone ${model}`));
    add(acc('MagSafe'));
    add(search('AirPods Pro 3', 1));
    add(acc('20 W'));
  } else if (/MacBook|iMac|Mac mini|Mac Studio/.test(n)) {
    add(acc('Magic Mouse')); add(acc('Magic Keyboard Touch ID Română')); add(acc('Multiport')); add(acc('70 W')); add(acc('MagSafe 3'));
  } else if (/iPad/.test(n)) {
    const model = /Pro/.test(n) ? 'iPad Pro' : /Air/.test(n) ? 'iPad Air' : /mini/.test(n) ? 'iPad mini' : 'A16';
    add(acc(`Magic Keyboard ${model}`)); add(acc(`Smart Folio ${model}`)); add(acc('Apple Pencil Pro')); add(acc('Apple Pencil USB'));
  } else if (/Watch/.test(n)) {
    add(acc(/Ultra/.test(n) ? '49 mm' : '46 mm')); add(acc('Apple Watch încărcător'));
  } else if (/AirPods|Beats/.test(n)) {
    add(acc('20 W')); add(acc('MagSafe')); add(search('AirTag', 1));
  } else {
    add(search('AirTag', 1)); add(acc('MagSafe')); add(acc('20 W'));
  }
  if (picks.length < limit) add(listProducts({ category: 'accesorii', sort: 'recommended', limit: 12 }));
  return picks.slice(0, limit);
}

export function featuredProducts(limit = 8) {
  return listProducts({ featured: true, limit });
}
