import { html, cx, attr, jsonScript } from '../../lib/html.js';
import { config } from '../../config.js';
import { page } from '../layout.js';
import { field, selectField, checkbox, csrfInput, carrierBadge } from '../components.js';
import { icon } from '../icons.js';
import { lei } from '../../lib/format.js';
import { COUNTIES } from '../../db/defaults.js';
import { getSetting } from '../../db/index.js';
import { estimate, carrier as carrierInfo } from '../../services/shipping.js';
import { summaryRows, promoForm } from './cart.js';

const price = (m, subtotal) => (m.free_over !== null && subtotal >= m.free_over ? 0 : m.price);

function methodCard(m, subtotal, checked) {
  const p = price(m, subtotal);
  return html`<label class=${cx('mcard', m.type === 'express' && 'mcard--express')} data-method-card data-type=${m.type}>
    <input type="radio" name="delivery_method" value=${m.code}${attr('checked', checked)} data-method=${m.code} data-cod=${m.cod_allowed ? '1' : '0'}>
    <span class="mcard__box">
      ${carrierBadge(m.carrier, { size: 36 })}
      <span class="mcard__main"><b>${m.name}</b><small>${estimate(m)}</small></span>
      <span class="mcard__price">${p === 0 ? html`<span class="free">Gratuit</span>` : lei(p)}${m.free_over && p > 0 ? html`<small>gratuit de la ${Math.round(m.free_over / 100)} lei</small>` : ''}</span>
    </span>
  </label>`;
}

export function checkoutPage(ctx, { cart, totals, methods, addresses, user, prefill = {} }) {
  const showroom = getSetting('showroom') || {};
  const store = getSetting('store') || {};
  const company = getSetting('company') || {};
  const couriers = methods.filter((m) => m.type === 'courier');
  const express = methods.find((m) => m.type === 'express');
  const lockers = methods.filter((m) => m.type === 'locker');
  const pickup = methods.find((m) => m.type === 'pickup');
  const sub = totals.subtotal - totals.discount;
  const def = addresses.find((a) => a.is_default) || addresses[0] || {};
  const v = (k, fallback = '') => prefill[k] ?? fallback;

  const body = html`
  <section class="section section--page checkout-wrap">
    <div class="wrap">
      <h1 class="visually-hidden">Finalizează comanda</h1>
      <ol class="steps" aria-label="Pași">
        <li class="is-done"><a href="/cos">${icon('check', { size: 14 })} Coș</a></li>
        <li class="is-active"><span>2</span> Livrare și plată</li>
        <li><span>3</span> Confirmare</li>
      </ol>
      <form class="checkout" action="/checkout" method="post" novalidate data-checkout>
        ${csrfInput(ctx)}
        <div class="checkout__main">

          <section class="cstep" aria-labelledby="h-contact">
            <header class="cstep__head"><span class="cstep__n">1</span><h2 id="h-contact">Date de contact</h2>
              ${user ? html`<span class="cstep__aside">${icon('user', { size: 16 })} ${user.email}</span>` : html`<a class="cstep__aside link" href="/cont/autentificare?next=/checkout">Ai cont? Autentifică-te</a>`}
            </header>
            <div class="grid2">
              ${field({ name: 'first_name', label: 'Prenume', value: v('first_name', user?.first_name), required: true, autocomplete: 'given-name' })}
              ${field({ name: 'last_name', label: 'Nume', value: v('last_name', user?.last_name), required: true, autocomplete: 'family-name' })}
              ${field({ name: 'email', label: 'Email', type: 'email', value: v('email', user?.email), required: true, autocomplete: 'email', hint: 'Aici trimitem confirmarea și factura.' })}
              ${field({ name: 'phone', label: 'Telefon', type: 'tel', value: v('phone', user?.phone), required: true, autocomplete: 'tel', inputmode: 'tel', hint: 'Pentru curier (ex: 0722 123 456).' })}
            </div>
          </section>

          <section class="cstep" aria-labelledby="h-ship">
            <header class="cstep__head"><span class="cstep__n">2</span><h2 id="h-ship">Livrare</h2></header>
            <div class="segmented" role="tablist" data-ship-tabs>
              <button type="button" role="tab" class="is-active" data-ship-tab="courier" aria-selected="true">${icon('truck', { size: 18 })}<span>Curier la adresă</span></button>
              <button type="button" role="tab" data-ship-tab="locker" aria-selected="false">${icon('locker', { size: 18 })}<span>Locker</span></button>
              <button type="button" role="tab" data-ship-tab="pickup" aria-selected="false">${icon('store', { size: 18 })}<span>Showroom</span></button>
            </div>

            <div class="shippane" data-ship-pane="courier">
              <div class="mcards">
                ${couriers.map((m, i) => methodCard(m, sub, i === 0))}
                ${express ? methodCard(express, sub, false) : ''}
              </div>
              ${addresses.length ? html`<div class="saved-addr"><p class="saved-addr__label">Adrese salvate</p><div class="chips">
                ${addresses.map((a) => html`<button type="button" class="chip" data-fill-address=${JSON.stringify(a)}>${icon('pin', { size: 14 })} ${a.street}, ${a.city}</button>`)}</div></div>` : ''}
              <div class="grid2">
                ${selectField({ name: 'ship_county', label: 'Județ', options: COUNTIES, value: v('ship_county', def.county), required: true })}
                ${field({ name: 'ship_city', label: 'Localitate', value: v('ship_city', def.city), required: true, autocomplete: 'address-level2' })}
                ${field({ name: 'ship_street', label: 'Strada, număr, bloc, scară, apartament', value: v('ship_street', def.street), required: true, autocomplete: 'street-address', cls: 'span2' })}
                ${field({ name: 'ship_postcode', label: 'Cod poștal', value: v('ship_postcode', def.postcode), inputmode: 'numeric', maxlength: 6, autocomplete: 'postal-code' })}
              </div>
              <p class="note" data-express-note hidden>${icon('bolt', { size: 16 })} Livrarea în aceeași zi este disponibilă pentru București și Ilfov, la comenzile plasate până la ora ${store.express_cutoff_hour ?? 14}:00.</p>
            </div>

            <div class="shippane" data-ship-pane="locker" hidden>
              <div class="locker-nets">${lockers.map((m) => {
    const p = price(m, sub);
    return html`<span class="locker-net">${carrierBadge(m.carrier, { size: 24 })}<span>${m.name}</span><b>${p ? lei(p) : 'Gratuit'}</b></span>`;
  })}</div>
              <div class="locker-pick" data-locker-picked hidden></div>
              <button class="btn btn--dark btn--lg locker-open" type="button" data-open-lockers>${icon('pin', { size: 20 })} Alege lockerul pe hartă</button>
              <input type="hidden" name="locker_id" value="" data-locker-id>
              <p class="field__msg" data-error-for="locker_id"></p>
              <div hidden>${lockers.map((m) => html`<input type="radio" name="delivery_method" value=${m.code} data-method=${m.code} data-cod=${m.cod_allowed ? '1' : '0'} data-locker-method=${m.carrier}>`)}</div>
            </div>

            <div class="shippane" data-ship-pane="pickup" hidden>
              ${pickup ? html`<label class="mcard mcard--wide">
                <input type="radio" name="delivery_method" value=${pickup.code} data-method=${pickup.code} data-cod="1">
                <span class="mcard__box">${carrierBadge('livada', { size: 36 })}
                  <span class="mcard__main"><b>${showroom.name}</b><small>${showroom.address}</small><small>${showroom.hours}</small></span>
                  <span class="mcard__price"><span class="free">Gratuit</span><small>${estimate(pickup)}</small></span></span>
              </label>` : ''}
            </div>
            <p class="field__msg" data-error-for="delivery_method"></p>
          </section>

          <section class="cstep" aria-labelledby="h-bill">
            <header class="cstep__head"><span class="cstep__n">3</span><h2 id="h-bill">Facturare</h2></header>
            <div class="segmented segmented--sm" role="radiogroup">
              <label><input type="radio" name="customer_type" value="pf" checked data-customer-type><span>Persoană fizică</span></label>
              <label><input type="radio" name="customer_type" value="pj" data-customer-type><span>Persoană juridică</span></label>
            </div>
            <div class="grid2" data-company hidden>
              ${field({ name: 'company_name', label: 'Denumire firmă', value: v('company_name'), required: true, cls: 'span2' })}
              ${field({ name: 'company_cui', label: 'CUI / CIF', value: v('company_cui'), required: true, hint: 'Ex: RO12345678' })}
              ${field({ name: 'company_reg', label: 'Nr. Reg. Comerțului', value: v('company_reg') })}
            </div>
            <div data-billing-same-wrap>${checkbox({ name: 'billing_same', label: 'Adresa de facturare este aceeași cu adresa de livrare', checked: true })}</div>
            <div class="grid2" data-billing hidden>
              ${selectField({ name: 'bill_county', label: 'Județ', options: COUNTIES, value: v('bill_county', def.county), required: true })}
              ${field({ name: 'bill_city', label: 'Localitate', value: v('bill_city', def.city), required: true, autocomplete: 'billing address-level2' })}
              ${field({ name: 'bill_street', label: 'Strada și numărul', value: v('bill_street', def.street), required: true, autocomplete: 'billing street-address', cls: 'span2' })}
              ${field({ name: 'bill_postcode', label: 'Cod poștal', value: v('bill_postcode', def.postcode), inputmode: 'numeric', maxlength: 6 })}
            </div>
          </section>

          <section class="cstep" aria-labelledby="h-pay">
            <header class="cstep__head"><span class="cstep__n">4</span><h2 id="h-pay">Plată</h2></header>
            <div class="paycards">
              <label class="pcard-pay"><input type="radio" name="payment_method" value="card" checked>
                <span class="pcard-pay__box">${icon('card', { size: 22 })}<span><b>Card online</b><small>Visa, Mastercard, Apple Pay, Google Pay · 3‑D Secure</small></span></span></label>
              <label class="pcard-pay" data-pay-cod><input type="radio" name="payment_method" value="cod">
                <span class="pcard-pay__box">${icon('cash', { size: 22 })}<span><b>Ramburs la livrare</b><small>Numerar sau card la curier / locker · +${lei(store.cod_fee ?? 999)}</small></span></span></label>
              <label class="pcard-pay"${totals.total < (store.installments_min ?? 50000) ? html` data-disabled="1"` : ''}><input type="radio" name="payment_method" value="installments"${attr('disabled', totals.total < (store.installments_min ?? 50000))}>
                <span class="pcard-pay__box">${icon('calendar', { size: 22 })}<span><b>Rate fără dobândă</b><small>3, 6 sau 12 rate 0% cu cardurile partenere · de la 500 lei</small></span></span></label>
              <label class="pcard-pay"><input type="radio" name="payment_method" value="transfer">
                <span class="pcard-pay__box">${icon('bank', { size: 22 })}<span><b>Transfer bancar (OP)</b><small>Pentru firme · ${company.bank} · expediem după confirmarea plății</small></span></span></label>
            </div>
            <div class="installments" data-installments hidden>
              <p>Număr de rate</p>
              <div class="segmented segmented--sm">${[3, 6, 12].map((n) => html`<label><input type="radio" name="installments" value=${n}${attr('checked', n === 6)}><span>${n} rate · <b data-rate=${n}>${lei(Math.ceil(totals.total / n))}</b></span></label>`)}</div>
            </div>
            <p class="field__msg" data-error-for="payment_method"></p>
          </section>

          <section class="cstep cstep--last">
            ${field({ name: 'notes', label: 'Mențiuni pentru curier', value: v('notes'), maxlength: 500 })}
            ${checkbox({ name: 'terms', label: html`Am citit și accept <a href="/termeni" target="_blank">Termenii și condițiile</a> și <a href="/confidentialitate" target="_blank">Politica de confidențialitate</a>.`, required: true })}
          </section>
        </div>

        <div class="checkout__side">
          <div class="summary summary--sticky" data-summary>
            <h2 class="h4">Comanda ta</h2>
            <ul class="mini-lines">${cart.items.map((it) => html`<li>
              <span class="mini-lines__img">${it.image ? html`<img src=${it.image} alt="" width="56" height="56">` : ''}<em>${it.qty}</em></span>
              <span class="mini-lines__name">${it.name}<small>${it.label}</small></span><b>${lei(it.line)}</b></li>`)}</ul>
            <div data-summary-promo>${promoForm(ctx, cart, totals)}</div>
            <p class="field__msg" data-error-for="promo"></p>
            <div data-summary-rows>${summaryRows(totals)}</div>
            <p class="summary__eta" data-summary-eta></p>
            <button class="btn btn--dark btn--lg btn--block" type="submit" data-place-order>
              <span data-place-label>Plasează comanda</span> · <b data-place-total>${lei(totals.total)}</b>
            </button>
            <p class="summary__legal">Prin plasarea comenzii, comanda devine obligație de plată. Ai dreptul de retragere în 30 de zile.</p>
          </div>
        </div>
      </form>
    </div>
  </section>

  <div class="locker-modal" hidden data-locker-modal role="dialog" aria-modal="true" aria-labelledby="locker-title">
    <div class="locker-modal__panel">
      <header class="locker-modal__head">
        <h2 id="locker-title">Alege lockerul</h2>
        <div class="locker-search">${icon('search', { size: 18 })}<input type="search" placeholder="Oraș, stradă sau nume locker" data-locker-search aria-label="Caută locker"></div>
        <button class="btn btn--ghost btn--sm" type="button" data-locker-locate>${icon('locate', { size: 16 })} Lângă mine</button>
        <button class="icon-btn" type="button" aria-label="Închide" data-close-lockers>${icon('close', { size: 22 })}</button>
      </header>
      <div class="locker-filters" data-locker-filters>
        ${lockers.map((m) => {
    const c = carrierInfo(m.carrier);
    return html`<label class="lf" style=${`--cb:${c.color};--ci:${c.ink}`}><input type="checkbox" value=${m.carrier} checked><span><i></i>${m.name}<small data-lf-count=${m.carrier}></small></span></label>`;
  })}
      </div>
      <div class="locker-modal__body">
        <div class="locker-map" data-locker-map data-tone=${config.map.tone}></div>
        <div class="locker-list" data-locker-list><p class="muted">Se încarcă lockerele…</p></div>
      </div>
    </div>
  </div>
  ${jsonScript('checkout-data', {
    map: { tiles: config.map.tiles, attribution: config.map.attribution },
    lockerPrices: Object.fromEntries(lockers.map((m) => [m.carrier, { code: m.code, name: m.name, price: price(m, sub), cod: !!m.cod_allowed, eta: estimate(m) }])),
    carriers: Object.fromEntries(lockers.map((m) => [m.carrier, carrierInfo(m.carrier)])),
    expressCounties: ['București', 'Ilfov'],
  })}`;

  return page(ctx, {
    title: 'Finalizează comanda', body, noindex: true, minimal: true, bodyClass: 'is-checkout',
    scripts: ['/js/checkout.js'],
  });
}
