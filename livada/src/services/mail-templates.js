// Șabloanele de email Livada — HTML pe tabele (compatibil Gmail / Outlook / Apple Mail) + versiune text.
import { config } from '../config.js';
import { getSetting } from '../db/index.js';
import { esc } from '../lib/html.js';
import { lei, dateTime, phone as fmtPhone } from '../lib/format.js';
import { CARRIERS } from '../db/defaults.js';

const C = {
  ink: '#0E1411', ink2: '#3A433E', muted: '#6B746F', line: '#E3E7E0', mist: '#F4F5F1', page: '#ECEEE8',
  leaf: '#1F7A4D', leafSoft: '#E4F1E8', paper: '#FFFFFF', warn: '#B4541A',
};
const SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Inter, Roboto, Helvetica, Arial, sans-serif";
const SERIF = "'Instrument Serif', Georgia, 'Times New Roman', serif";
const MONO = "'SF Mono', ui-monospace, Menlo, Consolas, 'Liberation Mono', monospace";

const abs = (p) => (/^https?:/.test(p || '') ? p : `${config.publicUrl}${p || ''}`);

function emailImage(p) {
  // imaginile WebP au o variantă JPG pentru clienții de email (Outlook nu afișează WebP)
  if (!p) return null;
  return abs(p.replace(/-(480|1000)\.webp$/, '-email.jpg'));
}

function button(href, label, { dark = true } = {}) {
  const bg = dark ? C.ink : C.paper;
  const fg = dark ? '#FFFFFF' : C.ink;
  return `<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr><td bgcolor="${bg}" style="border-radius:999px;${dark ? '' : `border:1px solid ${C.line};`}">
<a href="${esc(href)}" target="_blank" style="display:inline-block;padding:15px 30px;font-family:${SANS};font-size:15px;font-weight:600;color:${fg};text-decoration:none;border-radius:999px;letter-spacing:-0.01em">${esc(label)}&nbsp;&nbsp;→</a>
</td></tr></table>`;
}

function pill(label, { bg = C.leafSoft, fg = C.leaf } = {}) {
  return `<span style="display:inline-block;padding:6px 12px;border-radius:999px;background:${bg};color:${fg};font-family:${SANS};font-size:11px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase">${esc(label)}</span>`;
}

function codeTiles(code) {
  const cells = code.split('').map((d, i) => `${i === 3 ? `<td width="14" style="font-size:0">&nbsp;</td>` : ''}<td align="center" valign="middle" width="50" height="64" bgcolor="${C.mist}" style="width:50px;height:64px;border:1px solid ${C.line};border-radius:14px;font-family:${MONO};font-size:30px;font-weight:700;color:${C.ink}">${esc(d)}</td>${i < 5 && i !== 2 ? '<td width="8" style="font-size:0">&nbsp;</td>' : ''}`).join('');
  return `<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 auto"><tr>${cells}</tr></table>`;
}

function hairline(space = 28) {
  return `<tr><td style="padding:${space}px 0"><div style="height:1px;line-height:1px;font-size:0;background:${C.line}">&nbsp;</div></td></tr>`;
}

function layout({ preheader, topRight = '', body, footerNote }) {
  const co = getSetting('company') || {};
  const year = new Date().getFullYear();
  return `<!doctype html>
<html lang="ro" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><meta name="supported-color-schemes" content="light dark">
<title>${esc(preheader)}</title>
<style>
  @media (max-width:620px){ .card{padding:28px 22px !important} .h1{font-size:28px !important} .px{padding-left:16px !important;padding-right:16px !important} .stack{display:block !important;width:100% !important} }
  @media (prefers-color-scheme: dark){
    .page{background:#0B0F0D !important} .card{background:#141A17 !important} .t-ink{color:#F2F4EF !important} .t-mute{color:#A3ABA6 !important}
    .tile{background:#1C2420 !important;border-color:#2A332E !important;color:#F2F4EF !important} .box{background:#1C2420 !important}
  }
  a{color:${C.leaf}}
</style>
</head>
<body style="margin:0;padding:0;background:${C.page}" class="page">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent">${esc(preheader)}&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="${C.page}" class="page">
<tr><td align="center" style="padding:36px 12px 48px" class="px">
  <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px">
    <tr><td style="padding:0 8px 22px">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
        <td valign="middle"><a href="${esc(config.publicUrl)}" target="_blank" style="text-decoration:none"><img src="cid:livada-logo" width="121" height="30" alt="Livada" style="display:block;border:0;width:121px;height:30px"></a></td>
        <td valign="middle" align="right" class="t-mute" style="font-family:${SANS};font-size:12px;color:${C.muted}">${topRight}</td>
      </tr></table>
    </td></tr>
    <tr><td bgcolor="${C.paper}" class="card" style="background:${C.paper};border-radius:28px;padding:44px 44px 40px">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">${body}</table>
    </td></tr>
    <tr><td style="padding:28px 12px 0" align="center">
      <p style="margin:0 0 6px;font-family:${SERIF};font-style:italic;font-size:19px;color:${C.ink2}" class="t-mute">Tehnologia Apple, culeasă cu grijă.</p>
      <p style="margin:0 0 14px;font-family:${SANS};font-size:12px;line-height:20px;color:${C.muted}" class="t-mute">
        <a href="${esc(config.publicUrl)}/ajutor" style="color:${C.ink2};text-decoration:none">Ajutor</a> &nbsp;·&nbsp;
        <a href="${esc(config.publicUrl)}/livrare" style="color:${C.ink2};text-decoration:none">Livrare</a> &nbsp;·&nbsp;
        <a href="${esc(config.publicUrl)}/retur" style="color:${C.ink2};text-decoration:none">Retur 30 de zile</a> &nbsp;·&nbsp;
        <a href="tel:${esc((co.phone || '').replace(/\s/g, ''))}" style="color:${C.ink2};text-decoration:none">${esc(co.phone || '')}</a>
      </p>
      <p style="margin:0;font-family:${SANS};font-size:11px;line-height:18px;color:#8A928D" class="t-mute">
        ${footerNote ? `${esc(footerNote)}<br>` : ''}
        © ${year} ${esc(co.legal_name || 'Livada')} · CUI ${esc(co.cui || '')} · ${esc(co.reg_com || '')}<br>${esc(co.address || '')}
      </p>
    </td></tr>
  </table>
</td></tr>
</table>
</body></html>`;
}

const h1 = (text, accent) => `<tr><td style="padding:18px 0 14px"><h1 class="h1 t-ink" style="margin:0;font-family:${SANS};font-size:32px;line-height:1.12;letter-spacing:-0.025em;font-weight:700;color:${C.ink}">${text}${accent ? ` <span style="font-family:${SERIF};font-style:italic;font-weight:400;letter-spacing:-0.01em">${accent}</span>` : ''}</h1></td></tr>`;
const p = (text, { size = 16, color = C.ink2, pad = '0 0 14px' } = {}) => `<tr><td class="t-mute" style="padding:${pad};font-family:${SANS};font-size:${size}px;line-height:1.6;color:${color}">${text}</td></tr>`;

function securityBox(meta) {
  if (!meta) return '';
  const rows = [meta.when && `<b>Când:</b> ${esc(meta.when)}`, meta.device && `<b>Dispozitiv:</b> ${esc(meta.device)}`, meta.ip && `<b>IP:</b> ${esc(meta.ip)}`].filter(Boolean).join('<br>');
  return `<tr><td style="padding:26px 0 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td class="box" bgcolor="${C.mist}" style="background:${C.mist};border-radius:18px;padding:18px 20px;font-family:${SANS};font-size:13px;line-height:1.65;color:${C.ink2}">
<span style="font-size:11px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;color:${C.muted}">Detaliile cererii</span><br>${rows}<br>
<span style="color:${C.muted}">Nu ai fost tu? Ignoră acest mesaj — fără confirmare, nimic nu se activează.</span></td></tr></table></td></tr>`;
}

// ─── 1. confirmare email ────────────────────────────────────────────────────
export function verifyEmail({ user, code, link, meta }) {
  const name = esc(user.first_name || 'acolo');
  const pretty = `${code.slice(0, 3)} ${code.slice(3)}`;
  const body = `
<tr><td>${pill('Confirmare email')}</td></tr>
${h1(`Bun venit în Livada,`, `${name}.`)}
${p('Mai e un singur pas. Introdu codul de mai jos pe pagina deschisă în browser sau apasă butonul ca să-ți confirmi adresa de email.')}
<tr><td style="padding:16px 0 10px" align="center">${codeTiles(code).replaceAll(`bgcolor="${C.mist}" style="width:50px`, `bgcolor="${C.mist}" class="tile" style="width:50px`)}</td></tr>
<tr><td align="center" class="t-mute" style="padding:4px 0 26px;font-family:${SANS};font-size:13px;color:${C.muted}">Codul expiră în <b style="color:${C.ink}">30 de minute</b> și poate fi folosit o singură dată.</td></tr>
<tr><td align="center" style="padding:0 0 4px">${button(link, 'Confirmă adresa de email')}</td></tr>
<tr><td align="center" class="t-mute" style="padding:14px 0 0;font-family:${SANS};font-size:12px;line-height:1.6;color:${C.muted}">Butonul nu funcționează? Copiază linkul în browser:<br><a href="${esc(link)}" style="color:${C.leaf};word-break:break-all">${esc(link)}</a></td></tr>
${securityBox(meta)}`;
  return {
    subject: `${pretty} este codul tău de confirmare Livada`,
    html: layout({ preheader: `Codul tău: ${pretty}. Expiră în 30 de minute.`, topRight: 'Cont nou', body,
      footerNote: 'Ai primit acest email pentru că cineva a creat un cont Livada cu această adresă.' }),
    text: `Bun venit în Livada, ${user.first_name || ''}!\n\nCodul tău de confirmare: ${pretty}\nExpiră în 30 de minute.\n\nSau confirmă direct: ${link}\n\nNu ai creat tu contul? Ignoră acest mesaj.\n— Livada`,
  };
}

// ─── 2. bun venit (după confirmare) ─────────────────────────────────────────
export function welcome({ user }) {
  const perks = [
    ['Livrare gratuită', 'de la 500 lei, la adresă sau în easybox / FANbox.'],
    ['Retur 30 de zile', 'gratuit, fără întrebări, pentru produsele nedesigilate.'],
    ['Rate 0%', 'până la 12 luni cu cardurile partenere.'],
  ].map(([t, d]) => `<tr><td style="padding:0 0 12px"><table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
<td valign="top" style="padding:3px 12px 0 0"><div style="width:10px;height:10px;border-radius:3px 10px 3px 10px;background:${C.leaf}"></div></td>
<td class="t-mute" style="font-family:${SANS};font-size:15px;line-height:1.5;color:${C.ink2}"><b class="t-ink" style="color:${C.ink}">${t}</b> ${d}</td></tr></table></td></tr>`).join('');
  const body = `
<tr><td>${pill('Cont confirmat')}</td></tr>
${h1('Totul e pregătit,', `${esc(user.first_name || '')}.`)}
${p('Contul tău Livada este activ. De acum îți poți urmări comenzile, salva adresele de livrare și primi facturile direct aici.')}
<tr><td style="padding:10px 0 8px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td bgcolor="${C.ink}" style="background:${C.ink};border-radius:20px;padding:24px 26px">
  <p style="margin:0 0 6px;font-family:${SANS};font-size:12px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;color:#8EE0AE">Cadou de bun venit</p>
  <p style="margin:0 0 14px;font-family:${SANS};font-size:22px;line-height:1.3;font-weight:700;color:#FFFFFF;letter-spacing:-0.02em">50 lei reducere la prima comandă de peste 500 lei</p>
  <span style="display:inline-block;padding:10px 16px;border:1px dashed #5E6B64;border-radius:12px;font-family:${MONO};font-size:18px;font-weight:700;letter-spacing:0.14em;color:#FFFFFF">BUNVENIT50</span>
</td></tr></table></td></tr>
${hairline(22)}
${perks}
<tr><td style="padding:14px 0 0">${button(`${config.publicUrl}/`, 'Descoperă noutățile')}</td></tr>`;
  return {
    subject: `Contul tău Livada e activ — ai 50 lei cadou`,
    html: layout({ preheader: 'Codul BUNVENIT50 îți aduce 50 lei reducere la prima comandă.', topRight: 'Bun venit', body }),
    text: `Contul tău Livada este activ.\n\nCadou: 50 lei reducere la prima comandă de peste 500 lei cu codul BUNVENIT50.\n\n${config.publicUrl}`,
  };
}

// ─── 3. resetare parolă ─────────────────────────────────────────────────────
export function passwordReset({ user, link, meta }) {
  const body = `
<tr><td>${pill('Securitate cont', { bg: '#FBEFE6', fg: C.warn })}</td></tr>
${h1('Resetează-ți parola')}
${p(`Bună, ${esc(user.first_name || '')}! Am primit o cerere de resetare a parolei pentru contul tău Livada. Linkul este valabil <b>60 de minute</b>.`)}
<tr><td style="padding:12px 0 6px">${button(link, 'Alege o parolă nouă')}</td></tr>
<tr><td class="t-mute" style="padding:14px 0 0;font-family:${SANS};font-size:12px;line-height:1.6;color:${C.muted}">Sau copiază linkul: <a href="${esc(link)}" style="color:${C.leaf};word-break:break-all">${esc(link)}</a></td></tr>
${securityBox(meta)}`;
  return {
    subject: 'Resetarea parolei Livada',
    html: layout({ preheader: 'Linkul de resetare este valabil 60 de minute.', topRight: 'Securitate', body }),
    text: `Resetează-ți parola Livada (valabil 60 de minute):\n${link}\n\nNu ai cerut tu? Ignoră acest mesaj.`,
  };
}

// ─── comenzi ────────────────────────────────────────────────────────────────
const PAYMENT_LABEL = { card: 'Card online', cod: 'Ramburs (numerar sau card la livrare)', transfer: 'Transfer bancar (OP)', installments: 'Rate fără dobândă cu cardul' };

function tracker(status) {
  const steps = [['new', 'Plasată'], ['confirmed', 'Confirmată'], ['shipped', 'Expediată'], ['delivered', 'Livrată']];
  const order = { new: 0, confirmed: 1, processing: 1, ready: 2, shipped: 2, delivered: 3 };
  const at = order[status] ?? 0;
  const cells = steps.map(([, label], i) => {
    const done = i <= at;
    return `<td align="center" valign="top" width="25%" style="font-family:${SANS};font-size:12px;color:${done ? C.ink : C.muted}" class="${done ? 't-ink' : 't-mute'}">
<div style="width:14px;height:14px;margin:0 auto 8px;border-radius:4px 14px 4px 14px;background:${done ? C.leaf : C.line}"></div>${label}</td>`;
  }).join('');
  return `<tr><td style="padding:12px 0 6px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>${cells}</tr></table></td></tr>`;
}

function itemsTable(items) {
  return items.map((it) => `<tr><td style="padding:0 0 14px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
<td width="72" valign="top"><div class="box" style="width:64px;height:64px;border-radius:14px;background:${C.mist};overflow:hidden">${it.image ? `<img src="${esc(emailImage(it.image))}" width="64" height="64" alt="" style="display:block;width:64px;height:64px;border:0">` : ''}</div></td>
<td valign="top" style="font-family:${SANS};font-size:14px;line-height:1.45;color:${C.ink}" class="t-ink"><b>${esc(it.name)}</b><br><span class="t-mute" style="color:${C.muted};font-size:13px">${esc(it.variant_label || '')}${it.variant_label ? ' · ' : ''}${it.qty} × ${lei(it.unit_price)}</span></td>
<td valign="top" align="right" style="font-family:${SANS};font-size:14px;font-weight:600;color:${C.ink};white-space:nowrap" class="t-ink">${lei(it.line_total)}</td>
</tr></table></td></tr>`).join('');
}

function totals(o) {
  const row = (k, v, strong) => `<tr><td class="${strong ? 't-ink' : 't-mute'}" style="padding:4px 0;font-family:${SANS};font-size:${strong ? 17 : 14}px;${strong ? 'font-weight:700;' : ''}color:${strong ? C.ink : C.ink2}">${k}</td><td align="right" class="t-ink" style="padding:4px 0;font-family:${SANS};font-size:${strong ? 17 : 14}px;${strong ? 'font-weight:700;' : ''}color:${C.ink};white-space:nowrap">${v}</td></tr>`;
  return `<tr><td><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
${row('Subtotal', lei(o.subtotal))}
${o.discount ? row(`Reducere${o.promo_code ? ` (${esc(o.promo_code)})` : ''}`, `−${lei(o.discount)}`) : ''}
${row('Livrare', o.shipping_fee ? lei(o.shipping_fee) : 'Gratuită')}
${o.cod_fee ? row('Taxă ramburs', lei(o.cod_fee)) : ''}
${row('Total', lei(o.total), true)}
<tr><td colspan="2" class="t-mute" style="padding:2px 0 0;font-family:${SANS};font-size:12px;color:${C.muted}">din care TVA 21%: ${lei(o.vat)}</td></tr>
</table></td></tr>`;
}

export function deliveryText(o) {
  const d = o.delivery;
  if (d.type === 'locker' && d.locker) return `${CARRIERS[d.carrier]?.name || d.carrier} · ${d.locker.name}, ${d.locker.address}, ${d.locker.city}`;
  if (d.type === 'pickup') return `${d.store?.name || 'Showroom Livada'} · ${d.store?.address || ''}`;
  const a = d.address || {};
  return `${o.delivery_label || ''}${o.delivery_label ? ' · ' : ''}${a.street}, ${a.city}, jud. ${a.county}${a.postcode ? `, ${a.postcode}` : ''}`;
}

function infoBlocks(o) {
  const co = getSetting('company') || {};
  let payExtra = '';
  if (o.payment_method === 'transfer') {
    payExtra = `<br><span style="font-size:13px">Plătește <b>${lei(o.total)}</b> în contul <b>${esc(co.iban)}</b> (${esc(co.bank)}), beneficiar ${esc(co.legal_name)}, cu explicația <b>${esc(o.number)}</b>. Expediem după confirmarea plății.</span>`;
  } else if (o.payment_method === 'cod') {
    payExtra = `<br><span style="font-size:13px">Achiți <b>${lei(o.total)}</b> la livrare, numerar sau cu cardul.</span>`;
  }
  const block = (title, html) => `<td class="stack box" valign="top" width="50%" bgcolor="${C.mist}" style="background:${C.mist};border-radius:18px;padding:18px 20px;font-family:${SANS};font-size:14px;line-height:1.55;color:${C.ink2}">
<span style="font-size:11px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;color:${C.muted}">${title}</span><br>${html}</td>`;
  return `<tr><td style="padding:6px 0 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
${block('Livrare', `<b class="t-ink" style="color:${C.ink}">${esc(o.delivery_title || '')}</b><br>${esc(deliveryText(o))}${o.delivery_eta ? `<br><span style="color:${C.leaf};font-weight:600">${esc(o.delivery_eta)}</span>` : ''}`)}
<td width="12" class="stack" style="font-size:0">&nbsp;</td>
${block('Plată', `<b class="t-ink" style="color:${C.ink}">${esc(PAYMENT_LABEL[o.payment_method] || o.payment_method)}</b>${payExtra}`)}
</tr></table></td></tr>`;
}

export function orderConfirmation({ order, items, link }) {
  const body = `
<tr><td>${pill('Comandă plasată')}</td></tr>
${h1(`Mulțumim, ${esc(order.first_name)}!`, 'Comanda e în livadă.')}
${p(`Am înregistrat comanda <b class="t-ink" style="color:${C.ink}">${esc(order.number)}</b> din ${esc(dateTime(order.created_at))}. Îți scriem din nou când pleacă spre tine.`)}
${tracker(order.status)}
${hairline(22)}
${itemsTable(items)}
${hairline(10)}
${totals(order)}
<tr><td style="padding:22px 0 0"></td></tr>
${infoBlocks(order)}
<tr><td style="padding:26px 0 0">${button(link, 'Vezi comanda')}</td></tr>`;
  return {
    subject: `Comanda ${order.number} a fost înregistrată`,
    html: layout({ preheader: `Total ${lei(order.total)} · ${items.length} ${items.length === 1 ? 'produs' : 'produse'}`, topRight: esc(order.number), body,
      footerNote: 'Ai 30 de zile să returnezi gratuit produsele. Factura fiscală se emite la expediere.' }),
    text: `Mulțumim! Comanda ${order.number} a fost înregistrată.\n\n${items.map((i) => `- ${i.name} ${i.variant_label ? `(${i.variant_label}) ` : ''}× ${i.qty}: ${lei(i.line_total)}`).join('\n')}\n\nTotal: ${lei(order.total)}\nLivrare: ${deliveryText(order)}\nPlată: ${PAYMENT_LABEL[order.payment_method]}\n\nDetalii: ${link}`,
  };
}

const STATUS_COPY = {
  confirmed: ['Comandă confirmată', 'Am confirmat comanda', 'Pregătim produsele pentru expediere.'],
  processing: ['În pregătire', 'Îți împachetăm comanda', 'Verificăm fiecare produs și emitem factura.'],
  shipped: ['Expediată', 'Comanda e pe drum', 'Curierul a preluat coletul. Îl poți urmări cu numărul AWB de mai jos.'],
  ready: ['Gata de ridicare', 'Te așteptăm', 'Comanda ta te așteaptă. Adu numărul comenzii și un act de identitate.'],
  delivered: ['Livrată', 'Bucură-te de ele', 'Comanda a fost livrată. Dacă ai nevoie de ajutor la configurare, suntem aici.'],
  cancelled: ['Anulată', 'Comanda a fost anulată', 'Dacă ai plătit deja, suma se returnează pe card în 3–5 zile lucrătoare.'],
  returned: ['Retur procesat', 'Am primit returul', 'Suma va fi rambursată în maximum 14 zile, conform legii.'],
};

export function orderStatus({ order, items, link, note }) {
  const [tag, title, text] = STATUS_COPY[order.status] || ['Actualizare', 'Comanda ta a fost actualizată', ''];
  const carrier = CARRIERS[order.delivery?.carrier];
  const track = order.awb && carrier?.track ? carrier.track.replace('{awb}', encodeURIComponent(order.awb)) : null;
  const body = `
<tr><td>${pill(tag, order.status === 'cancelled' ? { bg: '#FBEFE6', fg: C.warn } : undefined)}</td></tr>
${h1(title, order.status === 'shipped' ? `${esc(order.first_name)}.` : '')}
${p(`${text}${note ? `<br><br><i>${esc(note)}</i>` : ''}`)}
${['cancelled', 'returned'].includes(order.status) ? '' : tracker(order.status)}
${order.awb ? `<tr><td style="padding:16px 0 4px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td class="box" bgcolor="${C.mist}" style="background:${C.mist};border-radius:18px;padding:18px 20px;font-family:${SANS}">
<span style="font-size:11px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;color:${C.muted}">AWB ${esc(carrier?.name || '')}</span><br>
<span class="t-ink" style="font-family:${MONO};font-size:22px;font-weight:700;letter-spacing:0.06em;color:${C.ink}">${esc(order.awb)}</span></td></tr></table></td></tr>` : ''}
${hairline(20)}
${itemsTable(items)}
<tr><td style="padding:10px 0 0">${button(track || link, track ? 'Urmărește coletul' : 'Vezi comanda')}</td></tr>`;
  return {
    subject: `${tag}: comanda ${order.number}`,
    html: layout({ preheader: `${title} — ${order.number}`, topRight: esc(order.number), body }),
    text: `${title} — comanda ${order.number}\n${text}${order.awb ? `\nAWB: ${order.awb}${track ? ` (${track})` : ''}` : ''}\n\n${link}`,
  };
}

export function adminNewOrder({ order, items, link }) {
  const body = `
<tr><td>${pill('Comandă nouă', { bg: '#E8EEF9', fg: '#1F4BB4' })}</td></tr>
${h1(`${esc(order.number)} · ${lei(order.total)}`)}
${p(`${esc(order.first_name)} ${esc(order.last_name)} · ${esc(order.email)} · ${esc(fmtPhone(order.phone))}`)}
${itemsTable(items)}
${infoBlocks(order)}
<tr><td style="padding:22px 0 0">${button(link, 'Deschide în admin')}</td></tr>`;
  return {
    subject: `[Livada] Comandă nouă ${order.number} — ${lei(order.total)}`,
    html: layout({ preheader: `${order.first_name} ${order.last_name} · ${lei(order.total)}`, topRight: 'Admin', body }),
    text: `Comandă nouă ${order.number}: ${lei(order.total)}\n${link}`,
  };
}

export function contactReceived({ name }) {
  const body = `
<tr><td>${pill('Mesaj primit')}</td></tr>
${h1(`Mulțumim, ${esc(name)}.`)}
${p('Am primit mesajul tău și revenim cu un răspuns în cel mult o zi lucrătoare. Pentru comenzi urgente ne poți suna în programul afișat mai jos.')}`;
  return { subject: 'Am primit mesajul tău — Livada', html: layout({ preheader: 'Revenim în cel mult o zi lucrătoare.', body }), text: 'Am primit mesajul tău. Revenim în cel mult o zi lucrătoare. — Livada' };
}

export const TEMPLATES = { verifyEmail, welcome, passwordReset, orderConfirmation, orderStatus, adminNewOrder, contactReceived };
