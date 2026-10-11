import zlib from 'node:zlib';
import { config } from '../config.js';

export class HttpError extends Error {
  constructor(status, message, details) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

export function parseCookies(header = '') {
  const out = {};
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    if (!k || k in out) continue;
    try { out[k] = decodeURIComponent(part.slice(i + 1).trim()); } catch { out[k] = part.slice(i + 1).trim(); }
  }
  return out;
}

export function serializeCookie(name, value, opts = {}) {
  let s = `${name}=${encodeURIComponent(value)}`;
  s += `; Path=${opts.path || '/'}`;
  if (opts.maxAge !== undefined) s += `; Max-Age=${Math.floor(opts.maxAge)}`;
  if (opts.httpOnly !== false) s += '; HttpOnly';
  s += `; SameSite=${opts.sameSite || 'Lax'}`;
  if (opts.secure ?? config.production) s += '; Secure';
  return s;
}

const MAX_BODY = 1024 * 1024; // 1 MB pentru JSON/form
const MAX_UPLOAD = 12 * 1024 * 1024;

export function readBody(req, limit = MAX_BODY) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new HttpError(413, 'Cererea este prea mare.'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function formToObject(params) {
  const out = {};
  for (const [k, v] of params) {
    if (k.endsWith('[]')) (out[k.slice(0, -2)] ||= []).push(v);
    else out[k] = v;
  }
  return out;
}

/** multipart/form-data minimal (pentru upload-ul de imagini din admin) */
function parseMultipart(buf, boundary) {
  const fields = {};
  const files = [];
  const sep = Buffer.from(`--${boundary}`);
  let pos = buf.indexOf(sep);
  while (pos !== -1) {
    pos += sep.length;
    if (buf[pos] === 45 && buf[pos + 1] === 45) break; // --
    pos += 2; // CRLF
    const headEnd = buf.indexOf('\r\n\r\n', pos);
    if (headEnd < 0) break;
    const head = buf.slice(pos, headEnd).toString('utf8');
    const next = buf.indexOf(sep, headEnd + 4);
    if (next < 0) break;
    const body = buf.slice(headEnd + 4, next - 2);
    const name = /name="([^"]*)"/i.exec(head)?.[1];
    const filename = /filename="([^"]*)"/i.exec(head)?.[1];
    const type = /content-type:\s*([^\r\n]+)/i.exec(head)?.[1]?.trim();
    if (name !== undefined) {
      if (filename !== undefined) {
        if (filename) files.push({ field: name, filename, type, data: body });
      } else if (name.endsWith('[]')) (fields[name.slice(0, -2)] ||= []).push(body.toString('utf8'));
      else fields[name] = body.toString('utf8');
    }
    pos = next;
  }
  return { fields, files };
}

export async function parseBody(req) {
  const type = (req.headers['content-type'] || '').toLowerCase();
  if (type.startsWith('multipart/form-data')) {
    const boundary = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(req.headers['content-type']);
    if (!boundary) throw new HttpError(400, 'Formular invalid.');
    const buf = await readBody(req, MAX_UPLOAD);
    const { fields, files } = parseMultipart(buf, boundary[1] || boundary[2]);
    return { body: fields, files };
  }
  const buf = await readBody(req);
  if (!buf.length) return { body: {}, files: [] };
  if (type.includes('application/json')) {
    try { return { body: JSON.parse(buf.toString('utf8')), files: [] }; } catch { throw new HttpError(400, 'JSON invalid.'); }
  }
  if (type.includes('application/x-www-form-urlencoded')) {
    return { body: formToObject(new URLSearchParams(buf.toString('utf8'))), files: [] };
  }
  return { body: {}, files: [], raw: buf };
}

const COMPRESSIBLE = /^(text\/|application\/(json|javascript|xml|ld\+json|manifest\+json)|image\/svg)/;

/** Trimite răspunsul, comprimat (br/gzip) dacă merită și clientul acceptă. */
export function sendBuffer(req, res, status, type, body, headers = {}) {
  let buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
  const h = { 'Content-Type': type, ...headers };
  const ae = req.headers['accept-encoding'] || '';
  if (buf.length > 1024 && COMPRESSIBLE.test(type) && !h['Content-Encoding']) {
    h.Vary = 'Accept-Encoding';
    if (/\bbr\b/.test(ae)) {
      buf = zlib.brotliCompressSync(buf, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 4 } });
      h['Content-Encoding'] = 'br';
    } else if (/\bgzip\b/.test(ae)) {
      buf = zlib.gzipSync(buf, { level: 6 });
      h['Content-Encoding'] = 'gzip';
    }
  }
  h['Content-Length'] = buf.length;
  res.writeHead(status, h);
  res.end(req.method === 'HEAD' ? undefined : buf);
}

export function clientIp(req) {
  if (config.trustProxy) {
    const xf = req.headers['x-forwarded-for'];
    if (xf) return String(xf).split(',')[0].trim();
  }
  return req.socket.remoteAddress || '';
}
