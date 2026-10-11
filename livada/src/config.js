import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const envFile = path.join(ROOT, '.env');
if (fs.existsSync(envFile) && !process.env.LIVADA_SKIP_ENV_FILE) process.loadEnvFile(envFile);

const env = process.env;
const bool = (v, d = false) => (v === undefined || v === '' ? d : /^(1|true|yes|on)$/i.test(v));

const production = env.NODE_ENV === 'production';
const dataDir = path.resolve(ROOT, env.DATA_DIR || 'var');
fs.mkdirSync(dataDir, { recursive: true });

function appSecret() {
  if (env.APP_SECRET) return env.APP_SECRET;
  if (production) throw new Error('APP_SECRET este obligatoriu în producție (openssl rand -hex 32).');
  // în development păstrăm un secret stabil între reporniri
  const file = path.join(dataDir, '.secret');
  if (!fs.existsSync(file)) fs.writeFileSync(file, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
  return fs.readFileSync(file, 'utf8').trim();
}

const port = Number(env.PORT || 8080);

export const config = {
  production,
  port,
  host: env.HOST || '127.0.0.1',
  publicUrl: (env.PUBLIC_URL || `http://localhost:${port}`).replace(/\/$/, ''),
  secret: appSecret(),
  dataDir,
  dbFile: env.DB_FILE || path.join(dataDir, 'livada.db'),
  outboxDir: path.join(dataDir, 'outbox'),
  uploadDir: path.join(dataDir, 'uploads'),
  admin: { email: env.ADMIN_EMAIL || 'admin@livada.ro', password: env.ADMIN_PASSWORD || '' },
  smtp: {
    host: env.SMTP_HOST || '',
    port: Number(env.SMTP_PORT || 587),
    secure: bool(env.SMTP_SECURE, false),
    user: env.SMTP_USER || '',
    pass: env.SMTP_PASS || '',
    from: env.MAIL_FROM || 'Livada <comenzi@livada.ro>',
  },
  shopNotifyEmail: env.SHOP_NOTIFY_EMAIL || 'comenzi@livada.ro',
  map: {
    tiles: env.MAP_TILES_URL || 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
    attribution: env.MAP_TILES_ATTRIBUTION || '© OpenStreetMap contributors © CARTO',
  },
  // dev-only: pagina /dev/mail cu emailurile trimise
  devTools: !production || bool(env.ENABLE_DEV_TOOLS),
  trustProxy: bool(env.TRUST_PROXY, production),
};

fs.mkdirSync(config.outboxDir, { recursive: true });
fs.mkdirSync(config.uploadDir, { recursive: true });
