// Pornește aplicația pe un port aleator, cu o bază de date temporară, pentru teste.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';

export async function startApp({ demo = false } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'livada-test-'));
  process.env.LIVADA_SKIP_ENV_FILE = '1';
  process.env.DATA_DIR = dir;
  process.env.NODE_ENV = 'test';
  process.env.ADMIN_EMAIL = 'admin@livada.ro';
  process.env.ADMIN_PASSWORD = 'Admin-Test-2026';
  process.env.SMTP_HOST = '';
  const { config } = await import('../src/config.js');
  const { openDb } = await import('../src/db/index.js');
  const { seedAll } = await import('../src/db/seed.js');
  const { createApp } = await import('../src/app.js');
  openDb();
  await seedAll({ demo, log: false });
  const server = http.createServer(createApp());
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  config.publicUrl = base;
  return {
    base, dir, config,
    async stop() {
      await new Promise((r) => server.close(r));
      const { closeDb } = await import('../src/db/index.js');
      closeDb();
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

/** client HTTP cu cookie jar și token CSRF */
export class Client {
  constructor(base) { this.base = base; this.jar = new Map(); this.csrf = null; }

  cookieHeader() { return [...this.jar].map(([k, v]) => `${k}=${v}`).join('; '); }

  take(res) {
    for (const c of res.headers.getSetCookie?.() || []) {
      const [kv, ...attrs] = c.split(';');
      const i = kv.indexOf('=');
      const name = kv.slice(0, i).trim();
      const value = kv.slice(i + 1);
      if (attrs.some((a) => /max-age=0/i.test(a))) this.jar.delete(name);
      else this.jar.set(name, value);
    }
  }

  async get(p, { headers = {}, json = false } = {}) {
    const res = await fetch(this.base + p, { redirect: 'manual', headers: { cookie: this.cookieHeader(), ...(json ? { accept: 'application/json' } : {}), ...headers } });
    this.take(res);
    const body = await res.text();
    const m = body.match(/<meta name="csrf-token" content="([^"]+)"/);
    if (m) this.csrf = m[1];
    return { status: res.status, headers: res.headers, body, json: json ? safeJson(body) : undefined };
  }

  async post(p, data = {}, { json = true, csrf = true, headers = {} } = {}) {
    if (csrf && !this.csrf) await this.get('/');
    const body = new URLSearchParams({ ...(csrf ? { _csrf: this.csrf } : {}), ...data });
    const res = await fetch(this.base + p, {
      method: 'POST', redirect: 'manual', body,
      headers: { cookie: this.cookieHeader(), 'content-type': 'application/x-www-form-urlencoded', ...(json ? { accept: 'application/json' } : {}), ...headers },
    });
    this.take(res);
    const text = await res.text();
    return { status: res.status, headers: res.headers, body: text, json: safeJson(text), location: res.headers.get('location') };
  }
}

const safeJson = (t) => { try { return JSON.parse(t); } catch { return undefined; } };

/** ultimul email din outbox pentru o adresă */
export function lastMail(dir, to) {
  const outbox = path.join(dir, 'outbox');
  const metas = fs.readdirSync(outbox).filter((f) => f.endsWith('.json')).sort().reverse()
    .map((f) => JSON.parse(fs.readFileSync(path.join(outbox, f), 'utf8')));
  const m = metas.find((x) => x.to === to);
  return m && { ...m, html: fs.readFileSync(path.join(outbox, `${m.id}.html`), 'utf8') };
}

export function mails(dir, to) {
  const outbox = path.join(dir, 'outbox');
  return fs.readdirSync(outbox).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(fs.readFileSync(path.join(outbox, f), 'utf8'))).filter((m) => !to || m.to === to);
}
