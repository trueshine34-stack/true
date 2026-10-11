// Trimitere email fără dependențe: client SMTP (STARTTLS / TLS implicit, AUTH PLAIN/LOGIN) + „outbox” local.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import tls from 'node:tls';
import crypto from 'node:crypto';
import os from 'node:os';
import { config, ROOT } from '../config.js';
import { run } from '../db/index.js';

const b64 = (s) => Buffer.from(s).toString('base64');
const wrap76 = (s) => s.replace(/.{1,76}/g, '$&\r\n');
const encodeHeader = (s) => (/^[\x20-\x7e]*$/.test(s) ? s : `=?UTF-8?B?${b64(s)}?=`);

function encodeAddress(a) {
  const m = /^\s*(.*?)\s*<([^>]+)>\s*$/.exec(a);
  if (!m) return a.trim();
  return m[1] ? `${encodeHeader(m[1].replace(/^"|"$/g, ''))} <${m[2]}>` : `<${m[2]}>`;
}
const bareAddress = (a) => (/<([^>]+)>/.exec(a)?.[1] || a).trim();

/** Construiește mesajul MIME: multipart/related( multipart/alternative(text, html), imagini inline ) */
export function buildMime({ from, to, subject, text, html, inline = [], headers = {} }) {
  const bAlt = `alt_${crypto.randomBytes(8).toString('hex')}`;
  const bRel = `rel_${crypto.randomBytes(8).toString('hex')}`;
  const domain = bareAddress(from).split('@')[1] || 'livada.ro';
  const lines = [
    `From: ${encodeAddress(from)}`,
    `To: ${encodeAddress(to)}`,
    `Subject: ${encodeHeader(subject)}`,
    `Date: ${new Date().toUTCString().replace('GMT', '+0000')}`,
    `Message-ID: <${crypto.randomUUID()}@${domain}>`,
    'MIME-Version: 1.0',
    ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
  ];
  const alt = [
    `--${bAlt}`, 'Content-Type: text/plain; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', wrap76(b64(text || '')),
    `--${bAlt}`, 'Content-Type: text/html; charset=UTF-8', 'Content-Transfer-Encoding: base64', '', wrap76(b64(html || '')),
    `--${bAlt}--`, '',
  ].join('\r\n');
  if (!inline.length) {
    lines.push(`Content-Type: multipart/alternative; boundary="${bAlt}"`, '', alt);
    return lines.join('\r\n');
  }
  lines.push(`Content-Type: multipart/related; boundary="${bRel}"; type="multipart/alternative"`, '',
    `--${bRel}`, `Content-Type: multipart/alternative; boundary="${bAlt}"`, '', alt);
  for (const f of inline) {
    lines.push(`--${bRel}`, `Content-Type: ${f.type}; name="${f.filename}"`, 'Content-Transfer-Encoding: base64',
      `Content-ID: <${f.cid}>`, `Content-Disposition: inline; filename="${f.filename}"`, '', wrap76(f.data.toString('base64')));
  }
  lines.push(`--${bRel}--`, '');
  return lines.join('\r\n');
}

/** Client SMTP minimal */
export function smtpSend({ host, port, secure, user, pass, from, to, message, timeout = 20000, rejectUnauthorized = true }) {
  return new Promise((resolve, reject) => {
    let socket = secure ? tls.connect({ host, port, servername: host, rejectUnauthorized }) : net.connect({ host, port });
    let buf = '';
    let waiting = null;
    const transcript = [];
    const fail = (e) => { try { socket.destroy(); } catch { /* */ } reject(e); };
    socket.setTimeout(timeout, () => fail(new Error('SMTP timeout')));
    socket.on('error', fail);

    const onData = (d) => {
      buf += d.toString('utf8');
      // un răspuns complet: ultima linie „NNN text” (fără „-” după cod)
      const m = /(?:^|\r\n)(\d{3}) [^\r\n]*\r\n$/.exec(buf);
      if (m && waiting) {
        const resp = buf;
        buf = '';
        const w = waiting;
        waiting = null;
        w({ code: Number(m[1]), text: resp });
      }
    };
    socket.on('data', onData);

    const read = () => new Promise((r) => { waiting = r; if (buf) onData(''); });
    const cmd = async (line, expect) => {
      transcript.push(`> ${line.startsWith('AUTH') ? 'AUTH ***' : line}`);
      socket.write(`${line}\r\n`);
      const res = await read();
      transcript.push(`< ${res.text.trim()}`);
      if (expect && !expect.includes(res.code)) throw new Error(`SMTP ${line.split(' ')[0]} → ${res.text.trim()}`);
      return res;
    };

    (async () => {
      const greet = await read();
      if (greet.code !== 220) throw new Error(`SMTP greeting: ${greet.text}`);
      const name = os.hostname() || 'livada.local';
      let ehlo = await cmd(`EHLO ${name}`, [250]);
      if (!secure && /STARTTLS/i.test(ehlo.text)) {
        await cmd('STARTTLS', [220]);
        socket.removeListener('data', onData);
        socket = tls.connect({ socket, servername: host, rejectUnauthorized });
        socket.on('data', onData);
        socket.on('error', fail);
        await new Promise((r, j) => { socket.once('secureConnect', r); socket.once('error', j); });
        ehlo = await cmd(`EHLO ${name}`, [250]);
      }
      if (user) {
        if (/AUTH[^\r\n]*PLAIN/i.test(ehlo.text)) await cmd(`AUTH PLAIN ${b64(`\0${user}\0${pass}`)}`, [235]);
        else {
          await cmd('AUTH LOGIN', [334]);
          await cmd(b64(user), [334]);
          await cmd(b64(pass), [235]);
        }
      }
      await cmd(`MAIL FROM:<${bareAddress(from)}>`, [250]);
      await cmd(`RCPT TO:<${bareAddress(to)}>`, [250, 251]);
      await cmd('DATA', [354]);
      const body = message.replace(/\r?\n/g, '\r\n').replace(/^\./gm, '..');
      const res = await cmd(`${body}\r\n.`, [250]);
      try { await cmd('QUIT'); } catch { /* unele servere închid direct */ }
      socket.end();
      resolve({ ok: true, response: res.text.trim(), transcript });
    })().catch(fail);
  });
}

const LOGO_FILE = path.join(ROOT, 'public/img/brand/email-logo.png');

/**
 * Trimite un email. Fără SMTP configurat, mesajul ajunge doar în outbox (var/outbox) — vizibil la /dev/mail
 * și în Admin → Emailuri.
 */
export async function sendMail({ to, subject, html, text, template = 'generic' }) {
  const id = `${new Date().toISOString().replace(/[:.]/g, '-')}_${crypto.randomBytes(3).toString('hex')}`;
  const file = path.join(config.outboxDir, `${id}.html`);
  // în outbox, logo-ul vine de pe site; în emailul real e atașat inline (cid:logo)
  fs.writeFileSync(file, html.replaceAll('cid:livada-logo', '/img/brand/email-logo.png'));
  fs.writeFileSync(path.join(config.outboxDir, `${id}.json`), JSON.stringify({ id, to, subject, template, text, date: new Date().toISOString() }));
  let status = 'outbox';
  let error = null;
  if (config.smtp.host) {
    try {
      const inline = fs.existsSync(LOGO_FILE) ? [{ cid: 'livada-logo', filename: 'livada.png', type: 'image/png', data: fs.readFileSync(LOGO_FILE) }] : [];
      const message = buildMime({ from: config.smtp.from, to, subject, text, html, inline,
        headers: { 'X-Mailer': 'Livada', 'Auto-Submitted': 'auto-generated' } });
      await smtpSend({ ...config.smtp, from: config.smtp.from, to, message });
      status = 'sent';
    } catch (e) {
      status = 'failed';
      error = e.message;
      console.error('[mail] eroare SMTP:', e.message);
    }
  }
  run('INSERT INTO email_log(to_addr, subject, template, status, error, file) VALUES (?,?,?,?,?,?)', to, subject, template, status, error, `${id}.html`);
  return { id, status, error };
}

export function listOutbox(limit = 50) {
  if (!fs.existsSync(config.outboxDir)) return [];
  return fs.readdirSync(config.outboxDir).filter((f) => f.endsWith('.json')).sort().reverse().slice(0, limit)
    .map((f) => JSON.parse(fs.readFileSync(path.join(config.outboxDir, f), 'utf8')));
}

export function readOutbox(id) {
  if (!/^[\w-]+$/.test(id)) return null;
  const meta = path.join(config.outboxDir, `${id}.json`);
  if (!fs.existsSync(meta)) return null;
  return { ...JSON.parse(fs.readFileSync(meta, 'utf8')), html: fs.readFileSync(path.join(config.outboxDir, `${id}.html`), 'utf8') };
}
