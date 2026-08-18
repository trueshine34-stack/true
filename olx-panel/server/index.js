/**
 * Panel OLX - serwer HTTP bez zaleznosci zewnetrznych (Node >= 20).
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { config, hasOlxCredentials, ROOT } from './lib/config.js';
import { routes, notFound } from './routes/api.js';

const PUBLIC_DIR = path.join(ROOT, 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.webmanifest': 'application/manifest+json',
};

function sendStatic(req, res, url) {
  const rel = url.pathname === '/' ? '/index.html' : url.pathname;
  const target = path.join(PUBLIC_DIR, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
  if (!target.startsWith(PUBLIC_DIR) || !fs.existsSync(target) || fs.statSync(target).isDirectory()) {
    return notFound(res);
  }
  res.writeHead(200, {
    'content-type': MIME[path.extname(target)] || 'application/octet-stream',
    'cache-control': 'no-cache',
  });
  fs.createReadStream(target).pipe(res);
  return undefined;
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 20 * 1024 * 1024) throw new Error('Payload za duzy (limit 20 MB)');
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  const text = Buffer.concat(chunks).toString('utf8');
  try { return JSON.parse(text); } catch { return { raw: text }; }
}

function authorized(req, url) {
  if (!config.panelToken) return true;
  const header = req.headers['x-panel-token'];
  const cookie = /(?:^|;\s*)panel_token=([^;]+)/.exec(req.headers.cookie || '')?.[1];
  return [header, url.searchParams.get('token'), cookie && decodeURIComponent(cookie)]
    .includes(config.panelToken);
}

/** Dopasowuje sciezke do wzorca typu "/api/listings/:id/preview". */
function match(pattern, pathname) {
  const p = pattern.split('/');
  const s = pathname.split('/');
  if (p.length !== s.length) return null;
  const params = {};
  for (let i = 0; i < p.length; i += 1) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = decodeURIComponent(s[i]);
    else if (p[i] !== s[i]) return null;
  }
  return params;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'access-control-allow-headers': 'content-type,x-panel-token' });
    return res.end();
  }

  if (!url.pathname.startsWith('/api/') && !url.pathname.startsWith('/oauth/')) {
    if (config.panelToken && url.searchParams.get('token') === config.panelToken) {
      res.setHeader('set-cookie', `panel_token=${encodeURIComponent(config.panelToken)}; Path=/; HttpOnly; SameSite=Lax`);
    }
    if (!authorized(req, url)) {
      res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8' });
      return res.end('401 - dodaj ?token=PANEL_TOKEN do adresu');
    }
    return sendStatic(req, res, url);
  }

  if (!authorized(req, url)) {
    res.writeHead(401, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error: 'Brak lub nieprawidlowy token panelu' }));
  }

  for (const route of routes) {
    if (route.method !== req.method) continue;
    const params = match(route.path, url.pathname);
    if (!params) continue;
    try {
      const body = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await readBody(req) : {};
      const result = await route.handler({ params, query: url.searchParams, body, req, res, url });
      if (res.writableEnded) return undefined;
      // Koperta __response tylko wtedy, gdy handler jawnie chcial ustawic kod HTTP -
      // inaczej pole "status" w danych (np. ogloszenia) zostaloby wzięte za kod odpowiedzi.
      const envelope = result?.__response === true ? result : { status: 200, body: result ?? { ok: true } };
      res.writeHead(envelope.status, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(envelope.body));
    } catch (err) {
      res.writeHead(err.status || 500, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: err.message, validation: err.validation || null }));
    }
  }

  return notFound(res);
});

server.listen(config.port, config.host, () => {
  const suffix = config.panelToken ? `?token=${config.panelToken}` : '';
  console.log(`\n  Panel OLX → http://${config.host}:${config.port}/${suffix}`);
  console.log(`  Dane: ${config.dataDir}`);
  console.log(`  OLX API: ${hasOlxCredentials() ? 'skonfigurowane' : 'BRAK kluczy (tryb dry-run)'}\n`);
});
