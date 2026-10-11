-- Livada — schema bazei de date (SQLite). Toate sumele sunt în bani (1 leu = 100 bani), TVA inclus.

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  first_name TEXT NOT NULL DEFAULT '',
  last_name TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL DEFAULT 'customer' CHECK (role IN ('customer', 'manager', 'admin')),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked')),
  email_verified_at TEXT,
  marketing_opt_in INTEGER NOT NULL DEFAULT 0,
  admin_lang TEXT NOT NULL DEFAULT 'ro',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_login_at TEXT
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,                 -- sha256(token)
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  expires_at TEXT NOT NULL,
  ip TEXT, user_agent TEXT
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS email_verifications (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  attempts INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT NOT NULL,
  used_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS ev_user ON email_verifications(user_id);

CREATE TABLE IF NOT EXISTS password_resets (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  used_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS addresses (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label TEXT NOT NULL DEFAULT '',
  first_name TEXT NOT NULL, last_name TEXT NOT NULL, phone TEXT NOT NULL,
  county TEXT NOT NULL, city TEXT NOT NULL, street TEXT NOT NULL, postcode TEXT NOT NULL DEFAULT '',
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  title TEXT NOT NULL,
  tagline TEXT NOT NULL DEFAULT '',
  groups TEXT NOT NULL DEFAULT '[]',
  sort INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS products (
  id INTEGER PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  category_id INTEGER NOT NULL REFERENCES categories(id),
  grp TEXT NOT NULL DEFAULT '',
  name TEXT NOT NULL,
  tagline TEXT NOT NULL DEFAULT '',
  badge TEXT,
  featured INTEGER NOT NULL DEFAULT 0,
  release_note TEXT,
  description TEXT NOT NULL DEFAULT '[]',
  highlights TEXT NOT NULL DEFAULT '[]',
  specs TEXT NOT NULL DEFAULT '[]',
  in_box TEXT NOT NULL DEFAULT '[]',
  options TEXT NOT NULL DEFAULT '[]',
  images TEXT NOT NULL DEFAULT '{}',
  image_key TEXT,
  preorder INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS products_cat ON products(category_id, active, sort);

CREATE TABLE IF NOT EXISTS variants (
  id INTEGER PRIMARY KEY,
  product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  sku TEXT NOT NULL UNIQUE,
  mpn TEXT,
  options TEXT NOT NULL DEFAULT '{}',
  price INTEGER NOT NULL CHECK (price >= 0),
  compare_at INTEGER,
  stock INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS variants_product ON variants(product_id, active);

CREATE VIRTUAL TABLE IF NOT EXISTS product_search USING fts5(
  name, tagline, grp, category, body, product_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE IF NOT EXISTS carts (
  id TEXT PRIMARY KEY,                 -- sha256(token din cookie)
  user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
  promo_code TEXT,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS cart_items (
  cart_id TEXT NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
  variant_id INTEGER NOT NULL REFERENCES variants(id) ON DELETE CASCADE,
  qty INTEGER NOT NULL CHECK (qty > 0),
  added_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (cart_id, variant_id)
);

CREATE TABLE IF NOT EXISTS delivery_methods (
  code TEXT PRIMARY KEY,
  type TEXT NOT NULL CHECK (type IN ('courier', 'locker', 'pickup', 'express')),
  carrier TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  eta TEXT NOT NULL DEFAULT '',
  price INTEGER NOT NULL DEFAULT 0,
  free_over INTEGER,                   -- livrare gratuită peste această sumă (bani)
  cod_allowed INTEGER NOT NULL DEFAULT 1,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS lockers (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  name TEXT NOT NULL,
  address TEXT NOT NULL,
  city TEXT NOT NULL,
  county TEXT NOT NULL DEFAULT '',
  postcode TEXT NOT NULL DEFAULT '',
  lat REAL NOT NULL, lng REAL NOT NULL,
  hours TEXT NOT NULL DEFAULT 'Non-stop',
  active INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS lockers_geo ON lockers(lat, lng);
CREATE INDEX IF NOT EXISTS lockers_provider ON lockers(provider, active);

CREATE TABLE IF NOT EXISTS promo_codes (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE COLLATE NOCASE,
  description TEXT NOT NULL DEFAULT '',
  type TEXT NOT NULL CHECK (type IN ('percent', 'fixed', 'shipping')),
  value INTEGER NOT NULL DEFAULT 0,    -- procent (percent) sau bani (fixed)
  min_subtotal INTEGER NOT NULL DEFAULT 0,
  max_discount INTEGER,                -- plafon pentru reducerile procentuale
  starts_at TEXT, ends_at TEXT,
  max_uses INTEGER, used INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY,
  number TEXT NOT NULL UNIQUE,
  public_token TEXT NOT NULL UNIQUE,   -- pentru link-ul de confirmare / plată fără cont
  user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
  email TEXT NOT NULL, phone TEXT NOT NULL,
  first_name TEXT NOT NULL, last_name TEXT NOT NULL,
  customer_type TEXT NOT NULL DEFAULT 'pf' CHECK (customer_type IN ('pf', 'pj')),
  company TEXT,                        -- JSON {name, cui, reg_com}
  billing TEXT NOT NULL,               -- JSON adresă facturare
  delivery_method TEXT NOT NULL,
  delivery TEXT NOT NULL,              -- JSON {type, carrier, address | locker | store}
  payment_method TEXT NOT NULL CHECK (payment_method IN ('card', 'cod', 'transfer', 'installments')),
  payment_status TEXT NOT NULL DEFAULT 'pending' CHECK (payment_status IN ('pending', 'paid', 'cod', 'refunded', 'failed')),
  status TEXT NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'confirmed', 'processing', 'shipped', 'ready', 'delivered', 'cancelled', 'returned')),
  subtotal INTEGER NOT NULL, discount INTEGER NOT NULL DEFAULT 0,
  shipping_fee INTEGER NOT NULL DEFAULT 0, cod_fee INTEGER NOT NULL DEFAULT 0,
  total INTEGER NOT NULL, vat INTEGER NOT NULL DEFAULT 0,
  promo_code TEXT,
  notes TEXT NOT NULL DEFAULT '',
  admin_notes TEXT NOT NULL DEFAULT '',
  awb TEXT, installments INTEGER,
  ip TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS orders_user ON orders(user_id, created_at);
CREATE INDEX IF NOT EXISTS orders_status ON orders(status, created_at);
CREATE INDEX IF NOT EXISTS orders_created ON orders(created_at);

CREATE TABLE IF NOT EXISTS order_items (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id INTEGER, variant_id INTEGER,
  sku TEXT NOT NULL, mpn TEXT,
  name TEXT NOT NULL, variant_label TEXT NOT NULL DEFAULT '',
  image TEXT, slug TEXT,
  unit_price INTEGER NOT NULL, qty INTEGER NOT NULL, line_total INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS order_items_order ON order_items(order_id);

CREATE TABLE IF NOT EXISTS order_events (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  status TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  actor TEXT NOT NULL DEFAULT 'system',
  notified INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS favorites (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (user_id, product_id)
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS email_log (
  id INTEGER PRIMARY KEY,
  to_addr TEXT NOT NULL, subject TEXT NOT NULL, template TEXT NOT NULL,
  status TEXT NOT NULL, error TEXT, file TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS newsletter (
  email TEXT PRIMARY KEY COLLATE NOCASE,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS contact_messages (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL, email TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '',
  topic TEXT NOT NULL DEFAULT '', message TEXT NOT NULL,
  handled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY,
  user_id INTEGER, action TEXT NOT NULL, entity TEXT, entity_id TEXT,
  data TEXT, ip TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
