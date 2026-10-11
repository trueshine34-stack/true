import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { emptyState, csrfInput, productCard } from '../components.js';
import { icon } from '../icons.js';
import { lei, plural } from '../../lib/format.js';

function qtyControl(ctx, it) {
  return html`<form class="qty" action="/cos/actualizeaza" method="post" data-cart-qty>
    ${csrfInput(ctx)}<input type="hidden" name="variant_id" value=${it.variant_id}>
    <button type="submit" name="qty" value=${it.qty - 1} aria-label="Scade cantitatea" class="qty__btn">${icon(it.qty <= 1 ? 'trash' : 'minus', { size: 16 })}</button>
    <span class="qty__val" aria-live="polite">${it.qty}</span>
    <button type="submit" name="qty" value=${it.qty + 1} aria-label="Crește cantitatea" class="qty__btn"${it.qty >= it.max ? html` disabled` : ''}>${icon('plus', { size: 16 })}</button>
  </form>`;
}

function line(ctx, it, { compact = false } = {}) {
  return html`<li class=${cx('cline', !it.available && 'is-unavailable')}>
    <a class="cline__img" href=${`/produs/${it.slug}`} tabindex="-1" aria-hidden="true">${it.image ? html`<img src=${it.image} alt="" width="96" height="96" loading="lazy">` : ''}</a>
    <div class="cline__info">
      <a class="cline__name" href=${`/produs/${it.slug}`}>${it.name}</a>
      ${it.label ? html`<span class="cline__var">${it.label}</span>` : ''}
      ${it.preorder ? html`<span class="cline__note cline__note--pre">${icon('calendar', { size: 14 })} ${it.release_note || 'Precomandă'}</span>` : ''}
      ${!it.available ? html`<span class="cline__note cline__note--err">Nu mai este disponibil</span>` : it.stock_issue ? html`<span class="cline__note cline__note--err">Stoc insuficient — redu cantitatea</span>` : ''}
      ${compact ? '' : html`<form action="/cos/sterge" method="post" data-cart-remove>${csrfInput(ctx)}<input type="hidden" name="variant_id" value=${it.variant_id}><button class="link-btn" type="submit">Elimină</button></form>`}
    </div>
    <div class="cline__right">
      <span class="cline__price">${it.compare_at ? html`<s>${lei(it.compare_at * it.qty)}</s>` : ''}<b>${lei(it.line)}</b></span>
      ${qtyControl(ctx, it)}
    </div>
  </li>`;
}

export function freeShippingBar(t) {
  if (!t.count) return '';
  const done = t.freeShippingLeft <= 0;
  return html`<div class=${cx('freebar', done && 'is-done')}>
    <p>${done ? html`${icon('check', { size: 16 })} Ai livrare gratuită la adresă și în lockere.` : html`Mai adaugă <b>${lei(t.freeShippingLeft)}</b> pentru livrare gratuită la adresă.`}</p>
    <span class="freebar__track"><span style=${`--p:${Math.round(t.freeShippingProgress * 100)}%`}></span></span>
  </div>`;
}

export function promoForm(ctx, cart, t) {
  if (t.promo) {
    return html`<div class="promo is-applied">${icon('tag', { size: 16 })}<span><b>${t.promo.code}</b> · ${t.promo.description}</span>
      <form action="/cos/promo/sterge" method="post" data-cart-promo>${csrfInput(ctx)}<button class="link-btn" type="submit">Elimină</button></form></div>`;
  }
  return html`<details class="promo"${cart.promo_code ? html` open` : ''}>
    <summary>${icon('tag', { size: 16 })} Ai un cod promoțional?</summary>
    <form class="promo__form" action="/cos/promo" method="post" data-cart-promo>
      ${csrfInput(ctx)}
      <input name="code" placeholder="Cod" autocomplete="off" value=${cart.promo_code || ''} aria-label="Cod promoțional" required>
      <button class="btn btn--ghost btn--sm" type="submit">Aplică</button>
    </form>
    ${t.promoError ? html`<p class="field__msg is-error">${t.promoError}</p>` : ''}
  </details>`;
}

export function summaryRows(t, { shippingLabel = 'Livrare', showShipping = true } = {}) {
  return html`<dl class="sum">
    <div><dt>Subtotal (${plural(t.count, 'produs', 'produse', 'de produse')})</dt><dd>${lei(t.subtotal)}</dd></div>
    ${t.savings ? html`<div class="sum__save"><dt>Economisești</dt><dd>${lei(t.savings)}</dd></div>` : ''}
    ${t.discount ? html`<div class="sum__save"><dt>Reducere ${t.promo?.code || ''}</dt><dd>−${lei(t.discount)}</dd></div>` : ''}
    ${showShipping ? html`<div><dt>${shippingLabel}</dt><dd data-sum-shipping>${t.shipping === null ? html`<span class="muted">la pasul următor</span>` : t.shipping === 0 ? html`<span class="free">Gratuită</span>` : lei(t.shipping)}</dd></div>` : ''}
    ${t.codFee ? html`<div><dt>Taxă ramburs</dt><dd>${lei(t.codFee)}</dd></div>` : ''}
    <div class="sum__total"><dt>Total</dt><dd>${lei(t.total)}</dd></div>
    <div class="sum__vat"><dt>din care TVA 21%</dt><dd>${lei(t.vat)}</dd></div>
  </dl>`;
}

export function cartSection(ctx, cart, t, suggestions = []) {
  if (!cart.items.length) {
    return html`<div data-cart-page>${emptyState({ iconName: 'bag', title: 'Coșul tău este gol', text: 'Descoperă noutățile Apple și accesoriile care le completează.', cta: 'Începe cumpărăturile', href: '/' })}
      ${suggestions.length ? html`<div class="section"><h2 class="h3">Ți-ar putea plăcea</h2><div class="pgrid">${suggestions.map((p) => productCard(p, { size: 'sm' }))}</div></div>` : ''}</div>`;
  }
  return html`<div class="cartpage" data-cart-page>
    <div class="cartpage__main">
      ${freeShippingBar(t)}
      <ul class="clines">${cart.items.map((it) => line(ctx, it))}</ul>
    </div>
    <div class="cartpage__side">
      <div class="summary">
        <h2 class="h4">Sumar</h2>
        ${promoForm(ctx, cart, t)}
        ${summaryRows(t)}
        <a class=${cx('btn btn--dark btn--lg btn--block', (t.unavailable.length || cart.items.some((i) => i.stock_issue)) && 'is-disabled')} href="/checkout">Finalizează comanda ${icon('arrow-right', { size: 18 })}</a>
        <ul class="summary__trust">
          <li>${icon('shield', { size: 16 })} Plată securizată 3‑D Secure</li>
          <li>${icon('return', { size: 16 })} Retur gratuit 30 de zile</li>
          <li>${icon('calendar', { size: 16 })} Rate 0% de la 500 lei</li>
        </ul>
      </div>
    </div>
  </div>`;
}

export function cartPage(ctx, cart, t, suggestions) {
  const body = html`<section class="section section--page"><div class="wrap">
    <h1 class="h1">Coșul tău</h1>
    ${cartSection(ctx, cart, t, suggestions)}
  </div></section>`;
  return page(ctx, { title: 'Coș de cumpărături', body, noindex: true, scripts: [] });
}

export function cartDrawer(ctx, cart, t) {
  if (!cart.items.length) {
    return { body: html`<div class="drawer__empty">${icon('bag', { size: 32 })}<p>Coșul tău este gol.</p><a class="btn btn--dark" href="/">Descoperă produsele</a></div>`, foot: '' };
  }
  return {
    body: html`${freeShippingBar(t)}<ul class="clines clines--compact">${cart.items.map((it) => line(ctx, it, { compact: true }))}</ul>`,
    foot: html`<div class="drawer__total"><span>Subtotal</span><b>${lei(t.subtotal - t.discount)}</b></div>
      <p class="drawer__note">Livrarea se calculează la finalizarea comenzii.</p>
      <a class="btn btn--dark btn--lg btn--block" href="/checkout">Finalizează comanda</a>
      <a class="btn btn--ghost btn--block" href="/cos">Vezi coșul</a>`,
  };
}
