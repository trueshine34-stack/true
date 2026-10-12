import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { emptyState, csrfInput, productCard } from '../components.js';
import { icon } from '../icons.js';
import { lei, plural } from '../../lib/format.js';
import { t, tHtml } from '../../lib/i18n.js';

function qtyControl(ctx, it) {
  return html`<form class="qty" action="/cos/actualizeaza" method="post" data-cart-qty>
    ${csrfInput(ctx)}<input type="hidden" name="variant_id" value=${it.variant_id}>
    <button type="submit" name="qty" value=${it.qty - 1} aria-label=${t('Scade cantitatea')} class="qty__btn">${icon(it.qty <= 1 ? 'trash' : 'minus', { size: 16 })}</button>
    <span class="qty__val" aria-live="polite">${it.qty}</span>
    <button type="submit" name="qty" value=${it.qty + 1} aria-label=${t('Crește cantitatea')} class="qty__btn"${it.qty >= it.max ? html` disabled` : ''}>${icon('plus', { size: 16 })}</button>
  </form>`;
}

function line(ctx, it, { compact = false } = {}) {
  return html`<li class=${cx('cline', !it.available && 'is-unavailable')}>
    <a class="cline__img" href=${`/produs/${it.slug}`} tabindex="-1" aria-hidden="true">${it.image ? html`<img src=${it.image} alt="" width="96" height="96" loading="lazy">` : ''}</a>
    <div class="cline__info">
      <a class="cline__name" href=${`/produs/${it.slug}`}>${it.name}</a>
      ${it.label ? html`<span class="cline__var">${it.label}</span>` : ''}
      ${it.preorder ? html`<span class="cline__note cline__note--pre">${icon('calendar', { size: 14 })} ${it.release_note || t('Precomandă')}</span>` : ''}
      ${!it.available ? html`<span class="cline__note cline__note--err">${t('Nu mai este disponibil')}</span>` : it.stock_issue ? html`<span class="cline__note cline__note--err">${t('Stoc insuficient — redu cantitatea')}</span>` : ''}
      ${compact ? '' : html`<form action="/cos/sterge" method="post" data-cart-remove>${csrfInput(ctx)}<input type="hidden" name="variant_id" value=${it.variant_id}><button class="link-btn" type="submit">${t('Elimină')}</button></form>`}
    </div>
    <div class="cline__right">
      <span class="cline__price">${it.compare_at ? html`<s>${lei(it.compare_at * it.qty)}</s>` : ''}<b>${lei(it.line)}</b></span>
      ${qtyControl(ctx, it)}
    </div>
  </li>`;
}

export function freeShippingBar(tot) {
  if (!tot.count) return '';
  const done = tot.freeShippingLeft <= 0;
  return html`<div class=${cx('freebar', done && 'is-done')}>
    <p>${done ? html`${icon('check', { size: 16 })} ${t('Ai livrare gratuită la adresă și în lockere.')}` : tHtml('Mai adaugă <b>{amount}</b> pentru livrare gratuită la adresă.', { amount: lei(tot.freeShippingLeft) })}</p>
    <span class="freebar__track"><span style=${`--p:${Math.round(tot.freeShippingProgress * 100)}%`}></span></span>
  </div>`;
}

export function promoForm(ctx, cart, tot) {
  if (tot.promo) {
    return html`<div class="promo is-applied">${icon('tag', { size: 16 })}<span><b>${tot.promo.code}</b> · ${t(tot.promo.description)}</span>
      <form action="/cos/promo/sterge" method="post" data-cart-promo>${csrfInput(ctx)}<button class="link-btn" type="submit">${t('Elimină')}</button></form></div>`;
  }
  return html`<details class="promo"${cart.promo_code ? html` open` : ''}>
    <summary>${icon('tag', { size: 16 })} ${t('Ai un cod promoțional?')}</summary>
    <form class="promo__form" action="/cos/promo" method="post" data-cart-promo>
      ${csrfInput(ctx)}
      <input name="code" placeholder=${t('Cod')} autocomplete="off" value=${cart.promo_code || ''} aria-label=${t('Cod promoțional')} required>
      <button class="btn btn--ghost btn--sm" type="submit">${t('Aplică')}</button>
    </form>
    ${tot.promoError ? html`<p class="field__msg is-error">${tot.promoError}</p>` : ''}
  </details>`;
}

export function summaryRows(tot, { shippingLabel = t('Livrare'), showShipping = true } = {}) {
  return html`<dl class="sum">
    <div><dt>${t('Subtotal')} (${plural(tot.count, 'produs', 'produse', 'de produse')})</dt><dd>${lei(tot.subtotal)}</dd></div>
    ${tot.savings ? html`<div class="sum__save"><dt>${t('Economisești')}</dt><dd>${lei(tot.savings)}</dd></div>` : ''}
    ${tot.discount ? html`<div class="sum__save"><dt>${t('Reducere')} ${tot.promo?.code || ''}</dt><dd>−${lei(tot.discount)}</dd></div>` : ''}
    ${showShipping ? html`<div><dt>${shippingLabel}</dt><dd data-sum-shipping>${tot.shipping === null ? html`<span class="muted">${t('la pasul următor')}</span>` : tot.shipping === 0 ? html`<span class="free">${t('Gratuită')}</span>` : lei(tot.shipping)}</dd></div>` : ''}
    ${tot.codFee ? html`<div><dt>${t('Taxă ramburs')}</dt><dd>${lei(tot.codFee)}</dd></div>` : ''}
    <div class="sum__total"><dt>${t('Total')}</dt><dd>${lei(tot.total)}</dd></div>
    <div class="sum__vat"><dt>${t('din care TVA 21%')}</dt><dd>${lei(tot.vat)}</dd></div>
  </dl>`;
}

export function cartSection(ctx, cart, tot, suggestions = []) {
  if (!cart.items.length) {
    return html`<div data-cart-page>${emptyState({ iconName: 'bag', title: t('Coșul tău este gol'), text: t('Descoperă noutățile Apple și accesoriile care le completează.'), cta: t('Începe cumpărăturile'), href: '/' })}
      ${suggestions.length ? html`<div class="section"><h2 class="h3">${t('Ți-ar putea plăcea')}</h2><div class="pgrid">${suggestions.map((p) => productCard(p, { size: 'sm' }))}</div></div>` : ''}</div>`;
  }
  return html`<div class="cartpage" data-cart-page>
    <div class="cartpage__main">
      ${freeShippingBar(tot)}
      <ul class="clines">${cart.items.map((it) => line(ctx, it))}</ul>
    </div>
    <div class="cartpage__side">
      <div class="summary">
        <h2 class="h4">${t('Sumar')}</h2>
        ${promoForm(ctx, cart, tot)}
        ${summaryRows(tot)}
        <a class=${cx('btn btn--dark btn--lg btn--block', (tot.unavailable.length || cart.items.some((i) => i.stock_issue)) && 'is-disabled')} href="/checkout">${t('Finalizează comanda')} ${icon('arrow-right', { size: 18 })}</a>
        <ul class="summary__trust">
          <li>${icon('shield', { size: 16 })} ${t('Plată securizată 3‑D Secure')}</li>
          <li>${icon('return', { size: 16 })} ${t('Retur gratuit 30 de zile')}</li>
          <li>${icon('calendar', { size: 16 })} ${t('Rate 0% de la 500 lei')}</li>
        </ul>
      </div>
    </div>
  </div>`;
}

export function cartPage(ctx, cart, tot, suggestions) {
  const body = html`<section class="section section--page"><div class="wrap">
    <h1 class="h1">${t('Coșul tău')}</h1>
    ${cartSection(ctx, cart, tot, suggestions)}
  </div></section>`;
  return page(ctx, { title: t('Coș de cumpărături'), body, noindex: true, scripts: [] });
}

export function cartDrawer(ctx, cart, tot) {
  if (!cart.items.length) {
    return { body: html`<div class="drawer__empty">${icon('bag', { size: 32 })}<p>${t('Coșul tău este gol.')}</p><a class="btn btn--dark" href="/">${t('Descoperă produsele')}</a></div>`, foot: '' };
  }
  return {
    body: html`${freeShippingBar(tot)}<ul class="clines clines--compact">${cart.items.map((it) => line(ctx, it, { compact: true }))}</ul>`,
    foot: html`<div class="drawer__total"><span>${t('Subtotal')}</span><b>${lei(tot.subtotal - tot.discount)}</b></div>
      <p class="drawer__note">${t('Livrarea se calculează la finalizarea comenzii.')}</p>
      <a class="btn btn--dark btn--lg btn--block" href="/checkout">${t('Finalizează comanda')}</a>
      <a class="btn btn--ghost btn--block" href="/cos">${t('Vezi coșul')}</a>`,
  };
}
