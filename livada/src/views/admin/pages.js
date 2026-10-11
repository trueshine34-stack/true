import { html, cx, attr, raw } from '../../lib/html.js';
import { adminPage } from './layout.js';
import { areaChart, columnChart, hbars, statTile } from './charts.js';
import { icon } from '../icons.js';
import { csrfInput, carrierBadge } from '../components.js';
import { lei, leiShort, dateTime, dateShort, phone as fmtPhone, plural } from '../../lib/format.js';
import { STATUSES, PAYMENT_STATUSES, PAYMENT_METHODS, TRANSITIONS } from '../../services/orders.js';
import { deliveryText } from '../../services/mail-templates.js';
import { carrier } from '../../services/shipping.js';
import { config } from '../../config.js';
import { COUNTIES } from '../../db/defaults.js';

const badge = (label, tone) => html`<span class=${`spill spill--${tone}`}>${label}</span>`;
const st = (t, s) => badge(t.status(s, STATUSES[s]?.label), STATUSES[s]?.tone || 'gray');
const pst = (t, s) => badge(t.pay(s, PAYMENT_STATUSES[s]?.label), PAYMENT_STATUSES[s]?.tone || 'gray');
const lbl = (label, input) => html`<label class="adm-field"><span>${label}</span>${input}</label>`;

function pager(t, { total, limit, offset, base, query }) {
  if (total <= limit) return '';
  const q = new URLSearchParams(query);
  const link = (o) => { q.set('o', String(o)); return `${base}?${q}`; };
  return html`<nav class="adm-pager">
    <span>${offset + 1}–${Math.min(offset + limit, total)} / ${total}</span>
    ${offset > 0 ? html`<a class="adm-btn adm-btn--sm" href=${link(Math.max(0, offset - limit))}>← ${t('prev')}</a>` : ''}
    ${offset + limit < total ? html`<a class="adm-btn adm-btn--sm" href=${link(offset + limit)}>${t('next')} →</a>` : ''}
  </nav>`;
}

// ─── panou ─────────────────────────────────────────────────────────────────
export function dashboardPage(ctx, t, d) {
  const labelsDelivery = { locker: 'Lockere', courier: 'Curier la adresă', pickup_showroom: 'Showroom', express_bucuresti: 'Aceeași zi (București)' };
  const body = html`
  <p class="adm-sub">${t('dash.sub')}</p>
  <section class="stats">
    ${statTile({ label: t('dash.revenue_today'), value: leiShort(Math.round(d.today.s / 100) * 100), delta: d.deltas.today, deltaLabel: 'vs. ieri' })}
    ${statTile({ label: t('dash.revenue_30'), value: leiShort(Math.round(d.last30.s / 100) * 100), delta: d.deltas.revenue, deltaLabel: t('dash.vs_prev'), spark: d.series.map((s) => s.revenue) })}
    ${statTile({ label: t('dash.orders_30'), value: d.last30.n.toLocaleString('ro-RO'), delta: d.deltas.orders, deltaLabel: t('dash.vs_prev'), spark: d.series.map((s) => s.orders) })}
    ${statTile({ label: t('dash.aov'), value: leiShort(Math.round(d.aov / 100) * 100), delta: d.deltas.aov, deltaLabel: t('dash.vs_prev') })}
    ${statTile({ label: t('dash.new_customers'), value: d.customers30.toLocaleString('ro-RO'), delta: d.deltas.customers, deltaLabel: t('dash.vs_prev') })}
    <a class="stat stat--action" href="/admin/comenzi?status=new"><p class="stat__label">${t('dash.to_process')}</p><p class="stat__value">${d.toProcess}</p><p class="stat__delta"><span>${t('dash.see_all')} →</span></p></a>
  </section>
  <section class="adm-grid adm-grid--2-1">
    <div class="card">
      <div class="card__head"><h2>${t('dash.revenue_chart')}</h2></div>
      ${areaChart(d.series.map((s) => ({ day: s.day, value: s.revenue, label: lei(s.revenue) })), { id: 'revenue', title: t('dash.revenue_chart') })}
      <h3 class="card__sub">${t('dash.orders_chart')}</h3>
      ${columnChart(d.series.map((s) => ({ day: s.day, value: s.orders, label: plural(s.orders, 'comandă', 'comenzi', 'de comenzi') })), { id: 'orders', title: t('dash.orders_chart') })}
      <details class="adm-table-toggle"><summary>${t('dash.table')}</summary>
        <table class="adm-table adm-table--compact"><thead><tr><th>${t('col.day')}</th><th class="num">${t('col.orders')}</th><th class="num">${t('col.revenue')}</th></tr></thead>
        <tbody>${[...d.series].reverse().map((s) => html`<tr><td>${dateShort(s.day)}</td><td class="num">${s.orders}</td><td class="num">${lei(s.revenue)}</td></tr>`)}</tbody></table>
      </details>
    </div>
    <div class="adm-stack">
      <div class="card"><div class="card__head"><h2>${t('dash.status')}</h2></div>
        ${hbars(d.byStatus.map((s) => ({ label: st(t, s.status), value: s.n })))}</div>
      <div class="card"><div class="card__head"><h2>${t('dash.delivery')}</h2></div>
        ${hbars(d.byDelivery.map((s) => ({ label: labelsDelivery[s.k] || s.k, value: s.n })))}</div>
      <div class="card"><div class="card__head"><h2>${t('dash.payment')}</h2></div>
        ${hbars(d.byPayment.map((s) => ({ label: t.paym(s.k, PAYMENT_METHODS[s.k]), value: s.n })))}</div>
    </div>
  </section>
  <section class="adm-grid adm-grid--2-1">
    <div class="card card--flush">
      <div class="card__head"><h2>${t('dash.recent')}</h2><a class="adm-link" href="/admin/comenzi">${t('dash.see_all')} →</a></div>
      ${ordersTable(t, d.recent)}
    </div>
    <div class="adm-stack">
      <div class="card"><div class="card__head"><h2>${t('dash.top')}</h2></div>
        <ul class="toplist">${d.top.map((p) => html`<li><span class="toplist__img">${p.image ? html`<img src=${p.image} alt="" width="40" height="40">` : ''}</span><span class="toplist__name">${p.name}<small>${p.qty} buc.</small></span><b>${leiShort(Math.round(p.revenue / 100) * 100)}</b></li>`)}</ul></div>
      <div class="card"><div class="card__head"><h2>${t('dash.low_stock')}</h2></div>
        ${d.lowStock.length ? html`<ul class="toplist">${d.lowStock.map((v) => html`<li><span class="toplist__name"><a href=${`/admin/produse/${v.product_id}#variante`}>${v.name}</a><small>${v.sku}</small></span>${badge(v.stock <= 0 ? t('products.out') : `${v.stock} buc.`, v.stock <= 0 ? 'red' : 'amber')}</li>`)}</ul>` : html`<p class="muted">${t('none')}</p>`}</div>
    </div>
  </section>`;
  return adminPage(ctx, { title: t('dash.title', { name: ctx.user.first_name }), body, active: 'dashboard', t });
}

export function ordersTable(t, rows) {
  if (!rows.length) return html`<p class="adm-empty">${t('none')}</p>`;
  return html`<div class="adm-table-wrap"><table class="adm-table">
    <thead><tr><th>${t('col.order')}</th><th>${t('col.date')}</th><th>${t('col.customer')}</th><th>${t('col.delivery')}</th><th>${t('col.payment')}</th><th>${t('col.status')}</th><th class="num">${t('col.total')}</th></tr></thead>
    <tbody>${rows.map((o) => {
    const d = typeof o.delivery === 'string' ? JSON.parse(o.delivery) : o.delivery;
    return html`<tr data-href=${`/admin/comenzi/${o.id}`}>
      <td><a class="adm-strong" href=${`/admin/comenzi/${o.id}`}>${o.number}</a></td>
      <td class="muted nowrap">${dateTime(o.created_at)}</td>
      <td>${o.first_name} ${o.last_name}<small class="block muted">${o.email}</small></td>
      <td><span class="adm-carrier">${carrierBadge(d.carrier, { size: 22 })}${d.type === 'locker' ? 'Locker' : d.type === 'pickup' ? 'Showroom' : d.type === 'express' ? 'Express' : carrier(d.carrier).name}</span></td>
      <td>${pst(t, o.payment_status)}<small class="block muted">${t.paym(o.payment_method, PAYMENT_METHODS[o.payment_method])}</small></td>
      <td>${st(t, o.status)}</td>
      <td class="num adm-strong">${lei(o.total)}</td>
    </tr>`;
  })}</tbody></table></div>`;
}

export function ordersPage(ctx, t, { rows, total, filters, limit, offset }) {
  const sel = (name, options, value) => html`<select name=${name}><option value="">${t('all')}</option>${options.map(([v, l]) => html`<option value=${v}${attr('selected', v === value)}>${l}</option>`)}</select>`;
  const body = html`
  <form class="adm-filters" method="get">
    <input name="q" value=${filters.q || ''} placeholder=${t('search.orders')}>
    ${sel('status', Object.entries(STATUSES).map(([k, v]) => [k, t.status(k, v.label)]), filters.status)}
    ${sel('payment', Object.entries(PAYMENT_METHODS).map(([k, v]) => [k, t.paym(k, v)]), filters.payment)}
    ${sel('delivery', [['courier', 'Curier'], ['locker', 'Locker'], ['express', 'Express'], ['pickup', 'Showroom']], filters.delivery)}
    <button class="adm-btn adm-btn--dark">${t('filter')}</button>
    ${Object.values(filters).some(Boolean) ? html`<a class="adm-btn" href="/admin/comenzi">${t('reset')}</a>` : ''}
  </form>
  <p class="adm-count">${t('results', { n: total })}</p>
  <div class="card card--flush">${ordersTable(t, rows)}</div>
  ${pager(t, { total, limit, offset, base: '/admin/comenzi', query: filters })}`;
  const q = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
  return adminPage(ctx, { title: t('orders.title'), body, active: 'orders', t,
    actions: html`<a class="adm-btn" href=${`/admin/comenzi.csv?${q}`}>${icon('download', { size: 16 })} ${t('export_csv')}</a>` });
}

export function orderPage(ctx, t, { order: o, items, events, customerOrders }) {
  const d = o.delivery;
  const c = carrier(d.carrier);
  const next = TRANSITIONS[o.status] || [];
  const body = html`
  <div class="adm-order-head">
    ${st(t, o.status)} ${pst(t, o.payment_status)}
    <span class="muted">${dateTime(o.created_at)} · IP ${o.ip || '—'}</span>
    <a class="adm-btn adm-btn--sm" href=${`/admin/comenzi/${o.id}/factura`} target="_blank">${icon('print', { size: 16 })} ${t('order.invoice')}</a>
    <a class="adm-btn adm-btn--sm" href=${`/comanda/${o.public_token}`} target="_blank">${icon('external', { size: 16 })} Client</a>
  </div>
  <div class="adm-grid adm-grid--2-1">
    <div class="adm-stack">
      <div class="card card--flush"><div class="card__head"><h2>${t('order.items')}</h2></div>
        <table class="adm-table"><thead><tr><th>${t('col.product')}</th><th>${t('col.sku')}</th><th class="num">${t('col.qty')}</th><th class="num">${t('col.price')}</th><th class="num">${t('col.total')}</th></tr></thead>
        <tbody>${items.map((i) => html`<tr><td><div class="adm-prod">${i.image ? html`<img src=${i.image} alt="" width="44" height="44">` : ''}<span><a class="adm-strong" href=${`/produs/${i.slug}`} target="_blank">${i.name}</a><small class="block muted">${i.variant_label}</small></span></div></td>
          <td class="mono small">${i.sku}${i.mpn ? html`<small class="block muted">${i.mpn}</small>` : ''}</td><td class="num">${i.qty}</td><td class="num">${lei(i.unit_price)}</td><td class="num adm-strong">${lei(i.line_total)}</td></tr>`)}</tbody>
        <tfoot>
          <tr><td colspan="4">Subtotal</td><td class="num">${lei(o.subtotal)}</td></tr>
          ${o.discount ? html`<tr><td colspan="4">Reducere ${o.promo_code || ''}</td><td class="num">−${lei(o.discount)}</td></tr>` : ''}
          <tr><td colspan="4">${t('col.delivery')}</td><td class="num">${o.shipping_fee ? lei(o.shipping_fee) : '0,00 lei'}</td></tr>
          ${o.cod_fee ? html`<tr><td colspan="4">Ramburs</td><td class="num">${lei(o.cod_fee)}</td></tr>` : ''}
          <tr class="is-total"><td colspan="4">${t('col.total')} <small class="muted">(TVA ${lei(o.vat)})</small></td><td class="num">${lei(o.total)}</td></tr>
        </tfoot></table>
      </div>
      <div class="card"><div class="card__head"><h2>${t('order.timeline')}</h2></div>
        <ol class="adm-timeline">${[...events].reverse().map((e) => html`<li><span class="adm-timeline__dot"></span><div>
          <b>${e.status.startsWith('payment:') ? e.note : t.status(e.status, STATUSES[e.status]?.label)}</b>${e.note && !e.status.startsWith('payment:') ? html` — ${e.note}` : ''}
          <small class="block muted">${dateTime(e.created_at)} · ${e.actor}${e.notified ? ' · ✉' : ''}</small></div></li>`)}</ol>
      </div>
    </div>
    <div class="adm-stack">
      ${next.length ? html`<div class="card card--accent"><div class="card__head"><h2>${t('order.change_status')}</h2></div>
        <form class="adm-form" method="post" action=${`/admin/comenzi/${o.id}/status`}>${csrfInput(ctx)}
          <div class="adm-seg">${next.map((s, i) => html`<label><input type="radio" name="status" value=${s}${attr('checked', i === 0)}><span>${t.status(s, STATUSES[s].label)}</span></label>`)}</div>
          ${o.delivery.type !== 'pickup' && !o.awb ? lbl(t('order.awb'), html`<input name="awb" placeholder="ex. 2345678901">`) : ''}
          ${lbl(t('order.note'), html`<input name="note" maxlength="300">`)}
          <label class="adm-check"><input type="checkbox" name="notify" value="1" checked> ${t('order.notify')}</label>
          <button class="adm-btn adm-btn--dark adm-btn--block">${t('order.apply')}</button>
        </form></div>` : ''}
      ${o.payment_status !== 'paid' && !['cancelled', 'returned'].includes(o.status) ? html`<form method="post" action=${`/admin/comenzi/${o.id}/plata`}>${csrfInput(ctx)}<input type="hidden" name="status" value="paid"><button class="adm-btn adm-btn--block">${icon('check', { size: 16 })} ${t('order.mark_paid')}</button></form>` : ''}
      <div class="card"><div class="card__head"><h2>${t('order.customer')}</h2>${o.user_id ? html`<a class="adm-link" href=${`/admin/clienti/${o.user_id}`}>${t.orders(customerOrders)} →</a>` : html`<span class="muted small">${t('order.guest')}</span>`}</div>
        <p class="adm-strong">${o.first_name} ${o.last_name}</p><p><a href=${`mailto:${o.email}`}>${o.email}</a></p><p><a href=${`tel:${o.phone}`}>${fmtPhone(o.phone)}</a></p>
        ${o.company ? html`<p class="muted">${t('order.company')}: ${o.company.name} · ${o.company.cui}${o.company.reg_com ? ` · ${o.company.reg_com}` : ''}</p>` : ''}
        ${o.notes ? html`<p class="adm-note"><b>${t('order.customer_notes')}:</b> ${o.notes}</p>` : ''}
      </div>
      <div class="card"><div class="card__head"><h2>${t('order.delivery')}</h2>${carrierBadge(d.carrier, { size: 28 })}</div>
        <p class="adm-strong">${o.delivery_title}</p><p>${deliveryText(o)}</p>
        ${d.type === 'locker' && d.locker?.lat ? html`<p><a class="adm-link" target="_blank" href=${`https://www.openstreetmap.org/?mlat=${d.locker.lat}&mlon=${d.locker.lng}#map=17/${d.locker.lat}/${d.locker.lng}`}>${t('order.open_map')} →</a></p>` : ''}
        ${o.awb ? html`<p>AWB <b class="mono">${o.awb}</b>${c.track ? html` · <a class="adm-link" target="_blank" href=${c.track.replace('{awb}', encodeURIComponent(o.awb))}>${t('order.track')} →</a>` : ''}</p>` : ''}
      </div>
      <div class="card"><div class="card__head"><h2>${t('order.billing')}</h2></div>
        <p>${o.billing.first_name || o.first_name} ${o.billing.last_name || o.last_name}</p><p>${o.billing.street}, ${o.billing.city}, jud. ${o.billing.county} ${o.billing.postcode || ''}</p>
        <p class="muted">${t.paym(o.payment_method, PAYMENT_METHODS[o.payment_method])}${o.installments ? ` · ${o.installments} rate` : ''}</p></div>
      <div class="card"><div class="card__head"><h2>${t('order.admin_notes')}</h2></div>
        <form method="post" action=${`/admin/comenzi/${o.id}/note`} class="adm-form">${csrfInput(ctx)}<textarea name="admin_notes" rows="3">${o.admin_notes}</textarea><button class="adm-btn adm-btn--sm">${t('save')}</button></form></div>
    </div>
  </div>`;
  return adminPage(ctx, { title: `${t('col.order')} ${o.number}`, body, active: 'orders', t,
    actions: html`<a class="adm-btn" href="/admin/comenzi">← ${t('back')}</a>` });
}

/** proformă tipăribilă */
export function invoicePage(ctx, { order: o, items, company }) {
  return html`<!doctype html><html lang="ro"><head><meta charset="utf-8"><title>Proformă ${o.number}</title>
<link rel="stylesheet" href="/css/admin.css"></head><body class="invoice">
<div class="inv">
  <header class="inv__head"><div><img src="/img/brand/logo.svg" alt="Livada" width="129" height="32"><p>${company.legal_name}<br>CUI ${company.cui} · ${company.reg_com}<br>${company.address}<br>${company.iban} · ${company.bank}</p></div>
  <div class="inv__meta"><h1>Factură proformă</h1><p>Nr. <b>${o.number}</b><br>Data ${dateShort(o.created_at)}</p><button type="button" class="adm-btn no-print" data-print>Tipărește</button></div></header>
  <section class="inv__to"><h2>Cumpărător</h2><p><b>${o.company?.name || `${o.first_name} ${o.last_name}`}</b>${o.company ? html`<br>CUI ${o.company.cui}${o.company.reg_com ? ` · ${o.company.reg_com}` : ''}` : ''}<br>${o.billing.street}, ${o.billing.city}, jud. ${o.billing.county}<br>${o.email} · ${fmtPhone(o.phone)}</p></section>
  <table class="inv__table"><thead><tr><th>#</th><th>Produs</th><th>Cod</th><th class="num">Cant.</th><th class="num">Preț unitar fără TVA</th><th class="num">Valoare fără TVA</th><th class="num">TVA 21%</th></tr></thead>
  <tbody>${items.map((i, n) => { const net = Math.round(i.line_total / 1.21); return html`<tr><td>${n + 1}</td><td>${i.name}<small>${i.variant_label}</small></td><td class="mono">${i.mpn || i.sku}</td><td class="num">${i.qty}</td><td class="num">${lei(Math.round(i.unit_price / 1.21))}</td><td class="num">${lei(net)}</td><td class="num">${lei(i.line_total - net)}</td></tr>`; })}
  ${o.shipping_fee ? html`<tr><td></td><td>Transport</td><td></td><td class="num">1</td><td class="num">${lei(Math.round(o.shipping_fee / 1.21))}</td><td class="num">${lei(Math.round(o.shipping_fee / 1.21))}</td><td class="num">${lei(o.shipping_fee - Math.round(o.shipping_fee / 1.21))}</td></tr>` : ''}
  ${o.cod_fee ? html`<tr><td></td><td>Taxă ramburs</td><td></td><td class="num">1</td><td class="num">${lei(Math.round(o.cod_fee / 1.21))}</td><td class="num">${lei(Math.round(o.cod_fee / 1.21))}</td><td class="num">${lei(o.cod_fee - Math.round(o.cod_fee / 1.21))}</td></tr>` : ''}
  ${o.discount ? html`<tr><td></td><td>Reducere ${o.promo_code || ''}</td><td></td><td class="num">1</td><td class="num">−${lei(Math.round(o.discount / 1.21))}</td><td class="num">−${lei(Math.round(o.discount / 1.21))}</td><td class="num">−${lei(o.discount - Math.round(o.discount / 1.21))}</td></tr>` : ''}
  </tbody></table>
  <div class="inv__totals"><p>Total fără TVA <b>${lei(o.total - o.vat)}</b></p><p>TVA 21% <b>${lei(o.vat)}</b></p><p class="is-total">Total de plată <b>${lei(o.total)}</b></p></div>
  <p class="inv__note">Document generat automat pentru comanda ${o.number}. Factura fiscală se emite la expediere și se transmite prin RO e-Factura.</p>
</div><script type="module" src="/js/admin.js"></script></body></html>`;
}

// ─── produse ───────────────────────────────────────────────────────────────
export function productsPage(ctx, t, { rows, categories, filters }) {
  const body = html`
  <form class="adm-filters" method="get">
    <input name="q" value=${filters.q || ''} placeholder="iPhone, MagSafe, SKU…">
    <select name="cat"><option value="">${t('all')}</option>${categories.map((c) => html`<option value=${c.slug}${attr('selected', c.slug === filters.cat)}>${c.name}</option>`)}</select>
    <select name="stoc"><option value="">${t('col.stock')}: ${t('all')}</option><option value="low"${attr('selected', filters.stoc === 'low')}>≤ 3</option><option value="out"${attr('selected', filters.stoc === 'out')}>${t('products.out')}</option></select>
    <button class="adm-btn adm-btn--dark">${t('filter')}</button>
  </form>
  <p class="adm-count">${t('results', { n: rows.length })}</p>
  <div class="card card--flush"><div class="adm-table-wrap"><table class="adm-table">
    <thead><tr><th>${t('col.product')}</th><th>${t('col.category')}</th><th class="num">${t('col.variants')}</th><th class="num">${t('col.price')}</th><th class="num">${t('col.stock')}</th><th>${t('col.status')}</th></tr></thead>
    <tbody>${rows.map((p) => html`<tr data-href=${`/admin/produse/${p.id}`}>
      <td><div class="adm-prod">${p.image ? html`<img src=${p.image} alt="" width="44" height="44" loading="lazy">` : ''}<span><a class="adm-strong" href=${`/admin/produse/${p.id}`}>${p.name}</a><small class="block muted">${p.grp}</small></span></div></td>
      <td>${p.category_name}</td><td class="num">${p.variant_count}</td>
      <td class="num">${p.price_from === p.price_to ? lei(p.price_from) : html`${lei(p.price_from)}<small class="block muted">– ${lei(p.price_to)}</small>`}</td>
      <td class="num">${p.preorder ? badge(t('products.preorder'), 'violet') : p.stock_total <= 0 ? badge(t('products.out'), 'red') : p.stock_total <= 3 ? badge(`${p.stock_total}`, 'amber') : p.stock_total}</td>
      <td>${p.active ? badge(t('yes'), 'green') : badge(t('no'), 'gray')}${p.featured ? html` ${badge('★', 'leaf')}` : ''}</td>
    </tr>`)}</tbody></table></div></div>`;
  return adminPage(ctx, { title: t('products.title'), body, active: 'products', t,
    actions: html`<a class="adm-btn adm-btn--dark" href="/admin/produse/nou">${icon('plus', { size: 16 })} ${t('products.new')}</a>` });
}

export function productEditPage(ctx, t, { p, categories, isNew = false }) {
  const lines = (a) => (a || []).join('\n');
  const optLabel = (v) => p.options.map((o) => o.values.find((x) => x.value === v.options[o.key])?.label).filter(Boolean).join(' · ') || '—';
  const imageKeys = p.image_key ? (p.options.find((o) => o.key === p.image_key)?.values || []).map((v) => [v.value, v.label]) : [['-', 'Principal']];
  const body = html`
  <form class="adm-grid adm-grid--2-1" method="post" action=${isNew ? '/admin/produse' : `/admin/produse/${p.id}`}>
    ${csrfInput(ctx)}
    <div class="card"><div class="card__head"><h2>${t('products.basic')}</h2></div>
      <div class="adm-form adm-form--grid">
        ${lbl(t('products.name'), html`<input name="name" required value=${p.name || ''}>`)}
        ${lbl(t('products.slug'), html`<input name="slug" required pattern="[a-z0-9-]+" value=${p.slug || ''}>`)}
        ${lbl(t('col.category'), html`<select name="category_id">${categories.map((c) => html`<option value=${c.id}${attr('selected', c.id === p.category_id)}>${c.name}</option>`)}</select>`)}
        ${lbl(t('products.group'), html`<input name="grp" value=${p.grp || ''}>`)}
        <div class="span2">${lbl(t('products.tagline'), html`<input name="tagline" value=${p.tagline || ''} maxlength="160">`)}</div>
        <div class="span2">${lbl(t('products.description'), html`<textarea name="description" rows="5">${lines(p.description)}</textarea>`)}</div>
        <div class="span2">${lbl(t('products.highlights'), html`<textarea name="highlights" rows="4">${lines(p.highlights)}</textarea>`)}</div>
        ${lbl(t('products.badge'), html`<select name="badge">${['', 'Nou', 'Precomandă', 'Ofertă', 'Ediție limitată'].map((b) => html`<option value=${b}${attr('selected', (p.badge || '') === b)}>${b || '—'}</option>`)}</select>`)}
        ${lbl(t('products.release'), html`<input name="release_note" value=${p.release_note || ''}>`)}
        <div class="span2 adm-checks">
          <label class="adm-check"><input type="checkbox" name="active" value="1"${attr('checked', isNew || p.active)}> ${t('products.active')}</label>
          <label class="adm-check"><input type="checkbox" name="featured" value="1"${attr('checked', p.featured)}> ${t('products.featured')}</label>
          <label class="adm-check"><input type="checkbox" name="preorder" value="1"${attr('checked', p.preorder)}> ${t('products.preorder')}</label>
        </div>
      </div>
    </div>
    <div class="adm-stack">
      <div class="card"><div class="card__head"><h2>${t('save')}</h2></div>
        <button class="adm-btn adm-btn--dark adm-btn--block">${t('save')}</button>
        ${!isNew ? html`<a class="adm-btn adm-btn--block" href=${`/produs/${p.slug}`} target="_blank">${icon('external', { size: 16 })} ${t('products.view')}</a>` : ''}
      </div>
      ${!isNew ? html`<div class="card"><div class="card__head"><h2>${t('products.images')}</h2></div>
        <div class="adm-thumbs">${Object.entries(p.images).map(([k, list]) => list.map((img) => html`<figure><img src=${img['480']} alt="" width="80" height="80" loading="lazy"><figcaption>${k === '-' ? '' : k}</figcaption></figure>`))}</div>
      </div>` : ''}
    </div>
  </form>
  ${!isNew ? html`
  <div class="card card--flush" id="variante">
    <div class="card__head"><h2>${t('products.variants')}</h2><span class="muted small">${t('products.price_hint')}</span></div>
    <form method="post" action=${`/admin/produse/${p.id}/variante`}>${csrfInput(ctx)}
      <div class="adm-table-wrap"><table class="adm-table adm-table--edit">
        <thead><tr><th>${t('col.variants')}</th><th>${t('col.sku')} / ${t('col.mpn')}</th><th class="num">${t('col.price')}</th><th class="num">${t('col.old_price')}</th><th class="num">${t('col.stock')}</th><th>${t('col.active')}</th></tr></thead>
        <tbody>${p.allVariants.map((v) => html`<tr>
          <td>${optLabel(v)}</td><td class="mono small">${v.sku}<small class="block muted">${v.mpn || ''}</small></td>
          <td class="num"><input class="adm-num" name=${`price_${v.id}`} value=${(v.price / 100).toFixed(2)} inputmode="decimal" required></td>
          <td class="num"><input class="adm-num" name=${`compare_${v.id}`} value=${v.compare_at ? (v.compare_at / 100).toFixed(2) : ''} inputmode="decimal" placeholder="—"></td>
          <td class="num"><input class="adm-num adm-num--sm" name=${`stock_${v.id}`} value=${v.stock} inputmode="numeric"></td>
          <td><input type="checkbox" name=${`active_${v.id}`} value="1"${attr('checked', v.active)}></td>
        </tr>`)}</tbody></table></div>
      <div class="card__foot">
        <label class="adm-inline">${t('products.bulk_pct')} <input class="adm-num adm-num--sm" name="bulk_pct" placeholder="0" inputmode="decimal"> %</label>
        <button class="adm-btn adm-btn--dark">${t('save')}</button>
      </div>
    </form>
  </div>
  <div class="card">
    <div class="card__head"><h2>${t('products.upload')}</h2></div>
    <form class="adm-form adm-form--row" method="post" action=${`/admin/produse/${p.id}/imagini`} enctype="multipart/form-data">${csrfInput(ctx)}
      ${lbl(t('products.for_key'), html`<select name="key">${imageKeys.map(([v, l]) => html`<option value=${v}>${l}</option>`)}</select>`)}
      ${lbl('WebP / PNG / JPG (max. 8 MB)', html`<input type="file" name="image" accept="image/webp,image/png,image/jpeg" required>`)}
      <button class="adm-btn">${icon('upload', { size: 16 })} ${t('products.upload')}</button>
    </form>
  </div>` : ''}`;
  return adminPage(ctx, { title: isNew ? t('products.new') : p.name, body, active: 'products', t,
    actions: html`<a class="adm-btn" href="/admin/produse">← ${t('back')}</a>` });
}

// ─── clienți ───────────────────────────────────────────────────────────────
export function customersPage(ctx, t, { rows, total, q, limit, offset }) {
  const body = html`
  <form class="adm-filters" method="get"><input name="q" value=${q || ''} placeholder="Nume, email, telefon"><button class="adm-btn adm-btn--dark">${t('filter')}</button></form>
  <p class="adm-count">${t('results', { n: total })}</p>
  <div class="card card--flush"><div class="adm-table-wrap"><table class="adm-table">
    <thead><tr><th>${t('col.customer')}</th><th>${t('col.registered')}</th><th>${t('col.verified')}</th><th class="num">${t('col.orders')}</th><th class="num">${t('col.spent')}</th><th>${t('col.status')}</th></tr></thead>
    <tbody>${rows.map((u) => html`<tr data-href=${`/admin/clienti/${u.id}`}>
      <td><a class="adm-strong" href=${`/admin/clienti/${u.id}`}>${u.first_name} ${u.last_name}</a><small class="block muted">${u.email}</small></td>
      <td class="muted nowrap">${dateShort(u.created_at)}</td>
      <td>${u.email_verified_at ? badge(t('yes'), 'green') : badge(t('no'), 'amber')}</td>
      <td class="num">${u.orders}</td><td class="num">${lei(u.spent)}</td>
      <td>${u.status === 'blocked' ? badge('blocat', 'red') : u.role !== 'customer' ? badge(u.role, 'violet') : ''}</td>
    </tr>`)}</tbody></table></div></div>
  ${pager(t, { total, limit, offset, base: '/admin/clienti', query: q ? { q } : {} })}`;
  return adminPage(ctx, { title: t('customers.title'), body, active: 'customers', t });
}

export function customerPage(ctx, t, { u, orders, addresses, stats }) {
  const body = html`<div class="adm-grid adm-grid--2-1">
    <div class="card card--flush"><div class="card__head"><h2>${t('customer.orders')} (${stats.n})</h2><span class="adm-strong">${lei(stats.spent)}</span></div>${ordersTable(t, orders)}</div>
    <div class="adm-stack">
      <div class="card"><p class="adm-strong">${u.first_name} ${u.last_name}</p><p><a href=${`mailto:${u.email}`}>${u.email}</a></p><p>${fmtPhone(u.phone) || '—'}</p>
        <p class="muted small">${t('col.registered')}: ${dateTime(u.created_at)}<br>Ultima autentificare: ${u.last_login_at ? dateTime(u.last_login_at) : '—'}<br>${t('customer.marketing')}: ${u.marketing_opt_in ? t('yes') : t('no')}</p>
        <p>${u.email_verified_at ? badge(`${t('col.verified')} ${dateShort(u.email_verified_at)}`, 'green') : badge(`${t('col.verified')}: ${t('no')}`, 'amber')}</p>
        ${!u.email_verified_at ? html`<form method="post" action=${`/admin/clienti/${u.id}/confirmare`}>${csrfInput(ctx)}<button class="adm-btn adm-btn--block">${icon('mail', { size: 16 })} ${t('customer.resend')}</button></form>` : ''}
        ${u.role === 'customer' ? html`<form method="post" action=${`/admin/clienti/${u.id}/status`} data-confirm="?">${csrfInput(ctx)}<button class=${cx('adm-btn adm-btn--block', u.status === 'active' && 'adm-btn--danger')} name="status" value=${u.status === 'active' ? 'blocked' : 'active'}>${u.status === 'active' ? t('customer.block') : t('customer.unblock')}</button></form>` : ''}
      </div>
      <div class="card"><div class="card__head"><h2>${t('customer.addresses')}</h2></div>
        ${addresses.length ? addresses.map((a) => html`<p class="adm-addr">${a.street}, ${a.city}, jud. ${a.county}${a.is_default ? html` ${badge('implicită', 'leaf')}` : ''}</p>`) : html`<p class="muted">${t('none')}</p>`}</div>
    </div></div>`;
  return adminPage(ctx, { title: `${u.first_name} ${u.last_name}`, body, active: 'customers', t, actions: html`<a class="adm-btn" href="/admin/clienti">← ${t('back')}</a>` });
}

// ─── promoții ──────────────────────────────────────────────────────────────
export function promosPage(ctx, t, rows) {
  const now = new Date().toISOString().replace('T', ' ').slice(0, 19);
  const typeLabel = { percent: t('promos.percent'), fixed: t('promos.fixed'), shipping: t('promos.shipping') };
  const body = html`<div class="adm-grid adm-grid--2-1">
    <div class="card card--flush"><div class="adm-table-wrap"><table class="adm-table">
      <thead><tr><th>${t('col.code')}</th><th>${t('col.type')}</th><th class="num">${t('col.value')}</th><th class="num">${t('col.used')}</th><th>${t('col.valid')}</th><th>${t('col.active')}</th><th></th></tr></thead>
      <tbody>${rows.map((p) => html`<tr>
        <td><b class="mono">${p.code}</b><small class="block muted">${p.description}</small></td>
        <td>${typeLabel[p.type]}</td>
        <td class="num">${p.type === 'percent' ? `${p.value}%${p.max_discount ? ` (max ${leiShort(p.max_discount)})` : ''}` : p.type === 'fixed' ? leiShort(p.value) : '—'}${p.min_subtotal ? html`<small class="block muted">min. ${leiShort(p.min_subtotal)}</small>` : ''}</td>
        <td class="num">${p.used}${p.max_uses ? ` / ${p.max_uses}` : ''}</td>
        <td class="nowrap">${p.ends_at ? html`${dateShort(p.ends_at)}${p.ends_at < now ? html` ${badge(t('promos.expired'), 'gray')}` : ''}` : '∞'}</td>
        <td><form method="post" action=${`/admin/promotii/${p.id}/toggle`}>${csrfInput(ctx)}<button class="adm-switch" aria-pressed=${p.active ? 'true' : 'false'} aria-label="toggle"><span></span></button></form></td>
        <td><form method="post" action=${`/admin/promotii/${p.id}/sterge`} data-confirm=${`${t('promos.delete')} ${p.code}?`}>${csrfInput(ctx)}<button class="adm-icon" aria-label=${t('promos.delete')}>${icon('trash', { size: 16 })}</button></form></td>
      </tr>`)}</tbody></table></div></div>
    <div class="card"><div class="card__head"><h2>${t('promos.new')}</h2></div>
      <form class="adm-form" method="post" action="/admin/promotii">${csrfInput(ctx)}
        ${lbl(t('col.code'), html`<input name="code" required pattern="[A-Za-z0-9_-]{3,30}" placeholder="BLACKFRIDAY" style="text-transform:uppercase">`)}
        ${lbl(t('promos.desc'), html`<input name="description" maxlength="140">`)}
        ${lbl(t('col.type'), html`<select name="type"><option value="percent">${t('promos.percent')}</option><option value="fixed">${t('promos.fixed')}</option><option value="shipping">${t('promos.shipping')}</option></select>`)}
        ${lbl(t('promos.value'), html`<input name="value" inputmode="decimal" placeholder="10">`)}
        ${lbl(t('promos.min'), html`<input name="min_subtotal" inputmode="decimal" placeholder="0">`)}
        ${lbl(t('promos.max'), html`<input name="max_discount" inputmode="decimal" placeholder="—">`)}
        ${lbl(t('promos.uses'), html`<input name="max_uses" inputmode="numeric" placeholder="∞">`)}
        ${lbl(t('promos.until'), html`<input name="ends_at" type="date">`)}
        <button class="adm-btn adm-btn--dark adm-btn--block">${t('save')}</button>
      </form></div></div>`;
  return adminPage(ctx, { title: t('promos.title'), body, active: 'promos', t });
}

// ─── livrare ───────────────────────────────────────────────────────────────
export function deliveryPage(ctx, t, { methods, stats }) {
  const body = html`
  <div class="card card--flush"><div class="card__head"><h2>${t('delivery.methods')}</h2></div>
    <form method="post" action="/admin/livrare">${csrfInput(ctx)}
      <div class="adm-table-wrap"><table class="adm-table adm-table--edit">
        <thead><tr><th>${t('col.method')}</th><th>${t('col.eta')}</th><th class="num">${t('col.price')}</th><th class="num">${t('col.free_over')}</th><th>${t('col.cod')}</th><th>${t('col.active')}</th></tr></thead>
        <tbody>${methods.map((m) => html`<tr>
          <td><div class="adm-prod">${carrierBadge(m.carrier, { size: 30 })}<span><b>${m.name}</b><small class="block muted">${m.code}</small></span></div></td>
          <td><input name=${`eta_${m.code}`} value=${m.eta} class="adm-input-sm"></td>
          <td class="num"><input class="adm-num" name=${`price_${m.code}`} value=${(m.price / 100).toFixed(2)} inputmode="decimal"></td>
          <td class="num"><input class="adm-num" name=${`free_${m.code}`} value=${m.free_over ? (m.free_over / 100).toFixed(0) : ''} inputmode="decimal" placeholder="—"></td>
          <td><input type="checkbox" name=${`cod_${m.code}`} value="1"${attr('checked', m.cod_allowed)}></td>
          <td><input type="checkbox" name=${`active_${m.code}`} value="1"${attr('checked', m.active)}></td>
        </tr>`)}</tbody></table></div>
      <div class="card__foot"><button class="adm-btn adm-btn--dark">${t('save')}</button></div>
    </form></div>
  <div class="adm-grid adm-grid--2">
    <div class="card"><div class="card__head"><h2>${t('delivery.lockers')}</h2><span class="muted small">${stats.reduce((s, x) => s + x.n, 0)}</span></div>
      ${hbars(stats.map((s) => ({ label: html`<span class="adm-carrier">${carrierBadge(s.provider, { size: 20 })}${carrier(s.provider).name}</span>`, value: s.n })), { fmt: (v) => v.toLocaleString('ro-RO') })}
      <p class="muted small">Sursa demo: OpenStreetMap (ODbL). ${stats.reduce((s, x) => s + x.cities, 0)} localități.</p></div>
    <div class="card"><div class="card__head"><h2>${t('delivery.import')}</h2></div>
      <form class="adm-form" method="post" action="/admin/livrare/lockere" enctype="multipart/form-data">${csrfInput(ctx)}
        <p class="muted small">${t('delivery.import_hint')}</p>
        <input type="file" name="file" accept="application/json,.json" required>
        <button class="adm-btn">${icon('upload', { size: 16 })} Import</button></form></div>
  </div>`;
  return adminPage(ctx, { title: t('delivery.title'), body, active: 'delivery', t });
}

// ─── emailuri ──────────────────────────────────────────────────────────────
export function emailsPage(ctx, t, { log, templates }) {
  const body = html`
  <p class=${cx('adm-banner', config.smtp.host ? 'is-ok' : 'is-warn')}>${icon(config.smtp.host ? 'check' : 'info', { size: 18 })} ${config.smtp.host ? t('emails.smtp_on', { host: config.smtp.host }) : t('emails.smtp_off')}</p>
  <div class="card"><div class="card__head"><h2>${t('emails.templates')}</h2>
    <form method="post" action="/admin/emailuri/test">${csrfInput(ctx)}<button class="adm-btn adm-btn--sm">${icon('mail', { size: 16 })} ${t('emails.test')}</button></form></div>
    <div class="adm-tabs" data-tabs>${templates.map(([k, l], i) => html`<button type="button" class=${cx(i === 0 && 'is-active')} data-tab=${`/admin/emailuri/previzualizare/${k}`}>${l}</button>`)}</div>
    <iframe class="adm-mailframe" title="Previzualizare" src=${`/admin/emailuri/previzualizare/${templates[0][0]}`} data-tab-frame sandbox="allow-same-origin allow-popups"></iframe>
  </div>
  <div class="card card--flush"><div class="card__head"><h2>${t('emails.log')}</h2>${config.devTools ? html`<a class="adm-link" href="/dev/mail" target="_blank">Inbox →</a>` : ''}</div>
    <div class="adm-table-wrap"><table class="adm-table"><thead><tr><th>${t('col.date')}</th><th>${t('col.email')}</th><th>Subiect</th><th>${t('col.type')}</th><th>${t('col.status')}</th></tr></thead>
    <tbody>${log.map((m) => html`<tr><td class="nowrap muted">${dateTime(m.created_at)}</td><td>${m.to_addr}</td><td>${m.subject}</td><td class="mono small">${m.template}</td>
      <td>${badge(m.status, m.status === 'sent' ? 'green' : m.status === 'failed' ? 'red' : 'blue')}${m.error ? html`<small class="block muted">${m.error}</small>` : ''}</td></tr>`)}</tbody></table></div></div>`;
  return adminPage(ctx, { title: t('emails.title'), body, active: 'emails', t });
}

export function messagesPage(ctx, t, rows) {
  const body = rows.length ? html`<div class="adm-stack">${rows.map((m) => html`<div class=${cx('card', m.handled && 'is-muted')}>
    <div class="card__head"><h2>${m.topic || '—'}</h2><span class="muted small">${dateTime(m.created_at)}</span></div>
    <p><b>${m.name}</b> · <a href=${`mailto:${m.email}`}>${m.email}</a>${m.phone ? ` · ${m.phone}` : ''}</p>
    <p class="adm-msg">${m.message}</p>
    ${m.handled ? badge(t('messages.handled'), 'green') : html`<form method="post" action=${`/admin/mesaje/${m.id}`}>${csrfInput(ctx)}<button class="adm-btn adm-btn--sm">${icon('check', { size: 16 })} ${t('messages.mark')}</button></form>`}
  </div>`)}</div>` : html`<p class="adm-empty">${t('none')}</p>`;
  return adminPage(ctx, { title: t('messages.title'), body, active: 'messages', t, narrow: true });
}

// ─── setări ────────────────────────────────────────────────────────────────
export function settingsPage(ctx, t, { company, showroom, store }) {
  const inp = (group, k, label, v, extra = '') => lbl(label, html`<input name=${`${group}.${k}`} value=${v ?? ''} ${raw(extra)}>`);
  const body = html`
  <p class="adm-banner is-warn">${icon('info', { size: 18 })} ${t('settings.placeholder')}</p>
  <form method="post" action="/admin/setari" class="adm-stack">${csrfInput(ctx)}
    <div class="card"><div class="card__head"><h2>${t('settings.company')}</h2></div><div class="adm-form adm-form--grid">
      ${inp('company', 'legal_name', 'Denumire legală', company.legal_name)}${inp('company', 'brand', 'Brand', company.brand)}
      ${inp('company', 'cui', 'CUI', company.cui)}${inp('company', 'reg_com', 'Nr. Reg. Com.', company.reg_com)}
      ${inp('company', 'euid', 'EUID', company.euid)}${inp('company', 'capital', 'Capital social', company.capital)}
      <div class="span2">${inp('company', 'address', 'Sediu social', company.address)}</div>
      ${inp('company', 'phone', 'Telefon', company.phone)}${inp('company', 'phone_hours', 'Program telefonic', company.phone_hours)}
      ${inp('company', 'email', 'Email contact', company.email, 'type="email"')}${inp('company', 'orders_email', 'Email comenzi', company.orders_email, 'type="email"')}
      ${inp('company', 'iban', 'IBAN', company.iban)}${inp('company', 'bank', 'Banca', company.bank)}
      ${inp('company', 'instagram', 'Instagram', company.instagram)}${inp('company', 'facebook', 'Facebook', company.facebook)}
    </div></div>
    <div class="card"><div class="card__head"><h2>${t('settings.showroom')}</h2></div><div class="adm-form adm-form--grid">
      ${inp('showroom', 'name', 'Nume', showroom.name)}${inp('showroom', 'hours', 'Program', showroom.hours)}
      <div class="span2">${inp('showroom', 'address', 'Adresă', showroom.address)}</div>
      ${inp('showroom', 'lat', 'Latitudine', showroom.lat)}${inp('showroom', 'lng', 'Longitudine', showroom.lng)}
    </div></div>
    <div class="card"><div class="card__head"><h2>${t('settings.store')}</h2></div><div class="adm-form adm-form--grid">
      ${inp('store', 'cod_fee', 'Taxă ramburs (lei)', (store.cod_fee / 100).toFixed(2))}${inp('store', 'cod_max', 'Ramburs maxim (lei)', Math.round(store.cod_max / 100))}
      ${inp('store', 'cutoff_hour', 'Ora limită expediere în aceeași zi', store.cutoff_hour, 'type="number" min="8" max="23"')}${inp('store', 'express_cutoff_hour', 'Ora limită livrare în aceeași zi', store.express_cutoff_hour, 'type="number" min="8" max="23"')}
      ${inp('store', 'order_prefix', 'Prefix număr comandă', store.order_prefix, 'maxlength="4"')}${inp('store', 'installments_min', 'Rate de la (lei)', Math.round(store.installments_min / 100))}
      <div class="span2">${lbl('Mesaje bara de anunțuri (unul pe linie)', html`<textarea name="store.announcement" rows="4">${(store.announcement || []).join('\n')}</textarea>`)}</div>
    </div></div>
    <div><button class="adm-btn adm-btn--dark">${t('save')}</button></div>
  </form>`;
  return adminPage(ctx, { title: t('settings.title'), body, active: 'settings', t, narrow: true });
}

export function auditPage(ctx, t, rows) {
  const body = html`<div class="card card--flush"><div class="adm-table-wrap"><table class="adm-table">
    <thead><tr><th>${t('col.date')}</th><th>${t('col.who')}</th><th>${t('col.action')}</th><th>${t('col.details')}</th></tr></thead>
    <tbody>${rows.map((r) => html`<tr><td class="nowrap muted">${dateTime(r.created_at)}</td><td>${r.email || '—'}</td><td><b>${r.action}</b><small class="block muted">${r.entity || ''} ${r.entity_id || ''}</small></td><td class="mono small adm-json">${r.data || ''}</td></tr>`)}</tbody>
  </table></div></div>`;
  return adminPage(ctx, { title: t('audit.title'), body, active: 'audit', t });
}

export { COUNTIES };
