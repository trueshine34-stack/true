import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { ROOT, config } from '../config.js';

const PUBLIC = path.join(ROOT, 'public');

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.avif': 'image/avif',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8', '.xml': 'application/xml',
  '.webmanifest': 'application/manifest+json', '.gif': 'image/gif',
};
const COMPRESS = new Set(['.css', '.js', '.mjs', '.json', '.svg', '.txt', '.xml', '.webmanifest', '.html']);

const cache = new Map();

function load(file) {
  const stat = fs.statSync(file);
  const hit = cache.get(file);
  if (hit && hit.mtime === stat.mtimeMs) return hit;
  const buf = fs.readFileSync(file);
  const ext = path.extname(file).toLowerCase();
  const entry = {
    mtime: stat.mtimeMs, buf, ext, type: TYPES[ext] || 'application/octet-stream',
    etag: `"${crypto.createHash('sha1').update(buf).digest('base64url').slice(0, 16)}"`,
  };
  if (COMPRESS.has(ext) && buf.length > 512) {
    entry.br = zlib.brotliCompressSync(buf, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11 } });
    entry.gz = zlib.gzipSync(buf, { level: 9 });
  }
  cache.set(file, entry);
  return entry;
}

/** URL cu versiune (hash de conținut) pentru cache „immutable” */
const versions = new Map();
export function asset(urlPath) {
  if (!config.production) {
    try { return `${urlPath}?v=${load(path.join(PUBLIC, urlPath)).etag.slice(1, 9)}`; } catch { return urlPath; }
  }
  if (!versions.has(urlPath)) {
    try { versions.set(urlPath, `${urlPath}?v=${load(path.join(PUBLIC, urlPath)).etag.slice(1, 9)}`); } catch { versions.set(urlPath, urlPath); }
  }
  return versions.get(urlPath);
}

/**
 * Import map pentru modulele JS: paginile încarcă /js/app.js?v=…, iar scripturile de pagină importă „./app.js”.
 * Fără hartă, browserul le-ar trata ca module diferite și app.js ar rula de două ori (handlere duble).
 * Hash-ul intră în CSP (script-src), deoarece harta este un script inline.
 */
const SHARED_MODULES = ['/js/app.js'];
let importMapMemo = null;
export function importMap() {
  const imports = Object.fromEntries(SHARED_MODULES.map((m) => [m, asset(m)]));
  const json = JSON.stringify({ imports });
  if (importMapMemo?.json !== json) importMapMemo = { json, hash: `'sha256-${crypto.createHash('sha256').update(json).digest('base64')}'` };
  return importMapMemo;
}

function resolveSafe(base, urlPath) {
  let p;
  try { p = decodeURIComponent(urlPath); } catch { return null; }
  if (p.includes('\0')) return null;
  const file = path.normalize(path.join(base, p));
  if (!file.startsWith(base + path.sep)) return null;
  return file;
}

/**
 * Servește fișiere din public/ (și /uploads/ din DATA_DIR). Returnează true dacă a răspuns.
 */
export function serveStatic(req, res, urlPath, query, securityHeaders) {
  let base = PUBLIC;
  let rel = urlPath;
  if (urlPath.startsWith('/uploads/')) { base = config.uploadDir; rel = urlPath.slice('/uploads'.length); }
  else if (!/^\/(css|js|img|fonts|vendor)\//.test(urlPath) && !/^\/(favicon\.(ico|svg)|robots\.txt|site\.webmanifest|apple-touch-icon\.png)$/.test(urlPath)) return false;
  if (req.method !== 'GET' && req.method !== 'HEAD') return false;
  if (urlPath === '/favicon.ico' || urlPath === '/favicon.svg') rel = '/img/brand/favicon.svg';
  if (urlPath === '/apple-touch-icon.png') rel = '/img/brand/apple-touch-icon.png';
  const file = resolveSafe(base, rel);
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) return false;
  const e = load(file);
  const versioned = query.has('v');
  const headers = {
    'Content-Type': e.type,
    ETag: e.etag,
    'Cache-Control': versioned ? 'public, max-age=31536000, immutable'
      : urlPath.startsWith('/img/products/') || urlPath.startsWith('/fonts/') ? 'public, max-age=2592000'
        : 'public, max-age=3600',
    'X-Content-Type-Options': 'nosniff',
  };
  if (e.ext === '.svg') Object.assign(headers, { 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'" });
  if (req.headers['if-none-match'] === e.etag) {
    res.writeHead(304, headers);
    res.end();
    return true;
  }
  let body = e.buf;
  const ae = req.headers['accept-encoding'] || '';
  if (e.br && /\bbr\b/.test(ae)) { body = e.br; headers['Content-Encoding'] = 'br'; headers.Vary = 'Accept-Encoding'; }
  else if (e.gz && /\bgzip\b/.test(ae)) { body = e.gz; headers['Content-Encoding'] = 'gzip'; headers.Vary = 'Accept-Encoding'; }
  headers['Content-Length'] = body.length;
  res.writeHead(200, headers);
  res.end(req.method === 'HEAD' ? undefined : body);
  return true;
}
