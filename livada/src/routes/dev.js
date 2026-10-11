// Unelte de dezvoltare: „inboxul” local cu emailurile trimise (când SMTP nu e configurat).
import { html, raw } from '../lib/html.js';
import { HttpError } from '../lib/http.js';
import { listOutbox, readOutbox } from '../lib/mailer.js';
import { dateTime } from '../lib/format.js';
import { missingStrings, clearMissing } from '../lib/i18n.js';

export default function devRoutes(r) {
  r.get('/dev/mail', (ctx) => {
    const list = listOutbox(100);
    const sel = ctx.query.get('id') ? readOutbox(ctx.query.get('id')) : (list[0] && readOutbox(list[0].id));
    ctx.html(html`<!doctype html><html lang="ro"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Inbox de test — Livada</title><link rel="icon" href="/img/brand/favicon.svg">
<style>body{margin:0;font:14px/1.45 -apple-system,Inter,system-ui,sans-serif;display:grid;grid-template-columns:340px 1fr;height:100vh;color:#0E1411}
aside{border-right:1px solid #E3E7E0;overflow:auto;background:#F4F5F1}header{padding:18px 18px 10px}h1{font-size:16px;margin:0}p.n{color:#6B746F;margin:4px 0 0;font-size:12px}
a.m{display:block;padding:12px 18px;border-top:1px solid #E3E7E0;color:inherit;text-decoration:none}a.m.on,a.m:hover{background:#fff}a.m b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
a.m small{color:#6B746F}iframe{width:100%;height:100%;border:0;background:#ECEEE8}@media(max-width:800px){body{grid-template-columns:1fr;grid-template-rows:40vh 1fr}}</style></head>
<body><aside><header><h1>Inbox de test</h1><p class="n">Emailurile trimise de aplicație (SMTP neconfigurat). Doar în development.</p></header>
${list.length ? list.map((m) => html`<a class=${`m ${sel?.id === m.id ? 'on' : ''}`} href=${`/dev/mail?id=${m.id}`} data-mail-id=${m.id}><b>${m.subject}</b><small>${m.to} · ${dateTime(m.date)}</small></a>`) : html`<p style="padding:18px">Niciun email încă.</p>`}
</aside><main>${sel ? html`<iframe title="Email" sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox" src=${`/dev/mail/${sel.id}/raw`}></iframe>` : ''}</main></body></html>`);
  });

  r.get('/dev/mail/:id/raw', (ctx) => {
    const m = readOutbox(ctx.params.id);
    if (!m) throw new HttpError(404, 'Email inexistent.');
    // emailul este HTML generat de aplicație; îl servim cu CSP strict (fără scripturi)
    ctx.send('text/html; charset=utf-8', m.html.replace('<head>', '<head><base target="_blank">'), 200, {
      'Content-Security-Policy': "default-src 'none'; img-src 'self' data: https:; style-src 'unsafe-inline'; font-src 'self'", 'Cache-Control': 'no-store',
    });
  });

  /** textele cerute în engleză care lipsesc din src/i18n/en/*.json (se adună în timp ce navighezi pe /en) */
  r.get('/dev/i18n', (ctx) => {
    if (ctx.query.get('clear')) clearMissing();
    const rows = missingStrings();
    if (ctx.query.get('format') === 'json') return ctx.json({ missing: rows.map(([s, n]) => ({ s, n })) });
    ctx.html(html`<!doctype html><html lang="ro"><head><meta charset="utf-8"><title>Traduceri lipsă — Livada</title>
<style>body{font:14px/1.5 -apple-system,Inter,system-ui,sans-serif;margin:24px;color:#0E1411}td{padding:4px 10px;border-bottom:1px solid #E3E7E0;vertical-align:top}td.n{color:#6B746F;text-align:right}</style></head>
<body><h1>Traduceri lipsă (${rows.length})</h1><p><a href="/dev/i18n?clear=1">Golește lista</a> · <a href="/dev/i18n?format=json">JSON</a></p>
<table>${rows.map(([s, n]) => html`<tr><td class="n">${n}</td><td>${s}</td></tr>`)}</table></body></html>`);
  });

  r.get('/dev/mail/latest.json', (ctx) => {
    const to = ctx.query.get('to');
    const list = listOutbox(50).filter((m) => !to || m.to === to);
    ctx.json(list[0] ? { ...list[0], html: undefined } : null);
  });
}

export { raw };
