import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { csrfInput, carrierBadge, pill } from '../components.js';
import { icon } from '../icons.js';
import { lei, dateTime, phone as fmtPhone } from '../../lib/format.js';
import { getSetting } from '../../db/index.js';
import { STATUSES, PAYMENT_METHODS, PAYMENT_STATUSES } from '../../services/orders.js';
import { carrier } from '../../services/shipping.js';
import { deliveryText } from '../../services/mail-templates.js';

const STEPS = [['new', 'Plasată'], ['confirmed', 'Confirmată'], ['shipped', 'Expediată'], ['delivered', 'Livrată']];
const STEP_INDEX = { new: 0, confirmed: 1, processing: 1, ready: 2, shipped: 2, delivered: 3 };

export function timeline(order, events = []) {
  if (['cancelled', 'returned'].includes(order.status)) {
    return html`<div class="otl otl--stopped">${pill(STATUSES[order.status].label, STATUSES[order.status].tone)}</div>`;
  }
  const at = STEP_INDEX[order.status] ?? 0;
  const when = (s) => events.find((e) => e.status === s || (s === 'confirmed' && e.status === 'processing') || (s === 'shipped' && e.status === 'ready'))?.created_at;
  return html`<ol class="otl">${STEPS.map(([s, label], i) => html`<li class=${cx(i <= at && 'is-done', i === at && 'is-current')}>
    <span class="otl__dot">${i < at ? icon('check', { size: 12 }) : ''}</span>
    <span class="otl__label">${s === 'shipped' && order.status === 'ready' ? 'Gata de ridicare' : label}</span>
    ${when(s) ? html`<span class="otl__time">${dateTime(when(s))}</span>` : ''}
  </li>`)}</ol>`;
}

export function orderDetails(order, items) {
  const co = getSetting('company') || {};
  const d = order.delivery;
  const c = carrier(d.carrier);
  const track = order.awb && c.track ? c.track.replace('{awb}', encodeURIComponent(order.awb)) : null;
  return html`<div class="odetail">
    <ul class="clines clines--compact">${items.map((it) => html`<li class="cline">
      <a class="cline__img" href=${`/produs/${it.slug}`}>${it.image ? html`<img src=${it.image} alt="" width="80" height="80" loading="lazy">` : ''}</a>
      <div class="cline__info"><a class="cline__name" href=${`/produs/${it.slug}`}>${it.name}</a><span class="cline__var">${it.variant_label}</span><span class="cline__var">${it.qty} × ${lei(it.unit_price)}</span></div>
      <div class="cline__right"><span class="cline__price"><b>${lei(it.line_total)}</b></span></div></li>`)}</ul>
    <dl class="sum">
      <div><dt>Subtotal</dt><dd>${lei(order.subtotal)}</dd></div>
      ${order.discount ? html`<div class="sum__save"><dt>Reducere ${order.promo_code || ''}</dt><dd>−${lei(order.discount)}</dd></div>` : ''}
      <div><dt>Livrare</dt><dd>${order.shipping_fee ? lei(order.shipping_fee) : html`<span class="free">Gratuită</span>`}</dd></div>
      ${order.cod_fee ? html`<div><dt>Taxă ramburs</dt><dd>${lei(order.cod_fee)}</dd></div>` : ''}
      <div class="sum__total"><dt>Total</dt><dd>${lei(order.total)}</dd></div>
      <div class="sum__vat"><dt>din care TVA 21%</dt><dd>${lei(order.vat)}</dd></div>
    </dl>
    <div class="info-cards">
      <div class="info-card"><h3>${carrierBadge(d.carrier, { size: 26 })} Livrare</h3>
        <p><b>${order.delivery_title}</b></p><p>${deliveryText(order)}</p>
        ${d.type === 'locker' && d.locker?.hours ? html`<p class="muted">Program: ${d.locker.hours}</p>` : ''}
        ${order.awb ? html`<p>AWB: <b class="mono">${order.awb}</b>${track ? html` · <a href=${track} target="_blank" rel="noopener">Urmărește ${icon('external', { size: 14 })}</a>` : ''}</p>` : ''}
      </div>
      <div class="info-card"><h3>${icon('card', { size: 20 })} Plată</h3>
        <p><b>${PAYMENT_METHODS[order.payment_method]}</b>${order.installments ? ` · ${order.installments} rate` : ''}</p>
        <p>${pill(PAYMENT_STATUSES[order.payment_status].label, PAYMENT_STATUSES[order.payment_status].tone)}</p>
        ${order.payment_method === 'transfer' && order.payment_status === 'pending' ? html`<p class="muted">Plătește <b>${lei(order.total)}</b> în <span class="mono">${co.iban}</span> (${co.bank}), beneficiar ${co.legal_name}, explicația <b>${order.number}</b>.</p>` : ''}
      </div>
      <div class="info-card"><h3>${icon('user', { size: 20 })} Facturare</h3>
        <p><b>${order.first_name} ${order.last_name}</b></p>
        ${order.company ? html`<p>${order.company.name} · ${order.company.cui}${order.company.reg_com ? ` · ${order.company.reg_com}` : ''}</p>` : ''}
        <p>${order.billing.street}, ${order.billing.city}, jud. ${order.billing.county}</p>
        <p class="muted">${order.email} · ${fmtPhone(order.phone)}</p>
      </div>
    </div>
  </div>`;
}

export function orderPage(ctx, order, items, events, { fresh = false } = {}) {
  const needsPay = ['card', 'installments'].includes(order.payment_method) && order.payment_status === 'pending' && order.status !== 'cancelled';
  const body = html`<section class="section section--page"><div class="wrap wrap--narrow">
    <div class=${cx('ohero', fresh && 'ohero--fresh')}>
      <div class="ohero__icon">${icon(order.status === 'cancelled' ? 'info' : 'check', { size: 30 })}</div>
      <p class="eyebrow">Comanda ${order.number}</p>
      <h1 class="h1">${fresh ? html`Mulțumim, ${order.first_name}! <em>Comanda e în livadă.</em>` : html`Comanda ta, <em>pe scurt.</em>`}</h1>
      <p class="lead lead--sm">${fresh ? html`Ți-am trimis confirmarea pe <b>${order.email}</b>. Îți scriem din nou când pleacă spre tine.` : html`Plasată pe ${dateTime(order.created_at)} · ${STATUSES[order.status].label}`}</p>
      ${needsPay ? html`<a class="btn btn--dark btn--lg" href=${`/plata/${order.public_token}`}>${icon('card', { size: 18 })} Finalizează plata · ${lei(order.total)}</a>` : ''}
    </div>
    ${timeline(order, events)}
    ${orderDetails(order, items)}
    <div class="ohelp">
      <p>Ai o întrebare despre comandă? <a href="/contact">Scrie-ne</a> sau sună la <a href=${`tel:${(getSetting('company')?.phone || '').replace(/\s/g, '')}`}>${getSetting('company')?.phone}</a>.</p>
      ${!ctx.user ? html`<p class="muted">Creează un cont cu adresa ${order.email} ca să vezi toate comenzile într-un singur loc. <a href="/cont/inregistrare">Creează cont</a></p>` : ''}
    </div>
  </div></section>`;
  return page(ctx, { title: `Comanda ${order.number}`, body, noindex: true });
}

/** pagină de plată simulată — mediu de test; în producție aici se face redirect către procesatorul de plăți */
export function paymentPage(ctx, order) {
  const body = html`<section class="section section--page"><div class="wrap wrap--xs">
    <div class="paybox">
      <div class="paybox__head"><span class="paybox__test">Mediu de test</span><p class="eyebrow">Plată securizată · 3‑D Secure</p>
        <h1 class="h2">${lei(order.total)}</h1><p class="muted">Comanda ${order.number}${order.installments ? ` · ${order.installments} rate fără dobândă` : ''}</p></div>
      <form action=${`/plata/${order.public_token}`} method="post" class="paybox__form" data-pay-form>
        ${csrfInput(ctx)}
        <div class="field"><input id="cc" inputmode="numeric" autocomplete="cc-number" placeholder=" " value="4111 1111 1111 1111" data-cc><label for="cc">Număr card</label></div>
        <div class="grid2">
          <div class="field"><input id="exp" inputmode="numeric" autocomplete="cc-exp" placeholder=" " value="12/29"><label for="exp">Expiră (LL/AA)</label></div>
          <div class="field"><input id="cvc" inputmode="numeric" autocomplete="cc-csc" placeholder=" " value="123" maxlength="4"><label for="cvc">CVC</label></div>
        </div>
        <p class="paybox__note">${icon('info', { size: 16 })} Aceasta este o simulare: datele cardului nu sunt trimise nicăieri. În producție, plata se face pe pagina procesatorului (Netopia, EuPlătesc sau Stripe).</p>
        <button class="btn btn--dark btn--lg btn--block" type="submit" name="result" value="success">Plătește ${lei(order.total)}</button>
        <button class="btn btn--ghost btn--block" type="submit" name="result" value="fail">Simulează plată respinsă</button>
      </form>
    </div>
  </div></section>`;
  return page(ctx, { title: 'Plată', body, noindex: true, minimal: true });
}
