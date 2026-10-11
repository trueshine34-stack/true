import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { config, ROOT } from '../config.js';

let db;

export function openDb(file = config.dbFile) {
  if (db) return db;
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000; PRAGMA synchronous = NORMAL;');
  db.exec(fs.readFileSync(path.join(ROOT, 'src/db/schema.sql'), 'utf8'));
  return db;
}

export function closeDb() {
  if (db) { db.close(); db = undefined; }
}

const cache = new Map();
function stmt(sql) {
  let s = cache.get(sql);
  if (!s) {
    s = openDb().prepare(sql);
    cache.set(sql, s);
  }
  return s;
}

export const one = (sql, ...p) => stmt(sql).get(...p);
export const all = (sql, ...p) => stmt(sql).all(...p);
export const run = (sql, ...p) => stmt(sql).run(...p);
export const exec = (sql) => openDb().exec(sql);

let depth = 0;
/** tranzacție (cu suport pentru apeluri imbricate prin SAVEPOINT) */
export function tx(fn) {
  const d = openDb();
  const name = `sp${depth}`;
  d.exec(depth === 0 ? 'BEGIN IMMEDIATE' : `SAVEPOINT ${name}`);
  depth++;
  try {
    const r = fn();
    depth--;
    d.exec(depth === 0 ? 'COMMIT' : `RELEASE ${name}`);
    return r;
  } catch (e) {
    depth--;
    d.exec(depth === 0 ? 'ROLLBACK' : `ROLLBACK TO ${name}; RELEASE ${name}`);
    throw e;
  }
}

export const json = (s, fallback = null) => {
  if (s === null || s === undefined || s === '') return fallback;
  try { return JSON.parse(s); } catch { return fallback; }
};

// ─── setări cheie/valoare ───────────────────────────────────────────────────
const settingsCache = new Map();
export function getSetting(key, fallback = null) {
  if (settingsCache.has(key)) return settingsCache.get(key);
  const row = one('SELECT value FROM settings WHERE key = ?', key);
  const v = row ? json(row.value, fallback) : fallback;
  settingsCache.set(key, v);
  return v;
}
export function setSetting(key, value) {
  run('INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', key, JSON.stringify(value));
  settingsCache.set(key, value);
}
export const clearSettingsCache = () => settingsCache.clear();

export function audit(userId, action, entity, entityId, data, ip) {
  run('INSERT INTO audit_log(user_id, action, entity, entity_id, data, ip) VALUES (?,?,?,?,?,?)',
    userId ?? null, action, entity ?? null, entityId == null ? null : String(entityId), data ? JSON.stringify(data) : null, ip ?? null);
}
