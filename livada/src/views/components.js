import { html, cx, attr, raw } from '../lib/html.js';
import { lei } from '../lib/format.js';
import { icon } from './icons.js';
import { carrier } from '../services/shipping.js';

export function productCard(p, { size = 'md', eager = false, heading = 3 } = {}) {
  const sale = p.compare_from && p.compare_from > p.price_from;
  const pct = sale ? Math.round((1 - p.price_from / p.compare_from) * 100) : 0;
  return html`<article class=${cx('pcard', `pcard--${size}`)} data-product-card>
    <a class="pcard__link" href=${`/produs/${p.slug}`}>
      <div class="pcard__media">
        ${p.badge ? html`<span class=${cx('badge', p.badge === 'Precomandă' ? 'badge--dark' : 'badge--leaf')}>${p.badge}</span>` : sale ? html`<span class="badge badge--sale">−${pct}%</span>` : ''}
        ${p.image ? html`<img src=${p.image} srcset=${`${p.image} 480w, ${p.image_lg} 1000w`} sizes="(max-width: 700px) 50vw, 25vw" alt=${p.name} width="480" height="480" ${raw(eager ? 'fetchpriority="high"' : 'loading="lazy"')} decoding="async" data-card-img>` : ''}
      </div>
      <div class="pcard__body">
        ${p.colors?.length > 1 ? html`<div class="swatches swatches--sm" aria-label=${`${p.colors.length} culori`}>
          ${p.colors.slice(0, 6).map((c) => html`<span class="swatch" style=${`--c:${c.hex}`} title=${c.label} data-swatch-img=${c.image || ''}></span>`)}
          ${p.colors.length > 6 ? html`<span class="swatches__more">+${p.colors.length - 6}</span>` : ''}
        </div>` : ''}
        ${heading === 2 ? html`<h2 class="pcard__name">${p.name}</h2>` : html`<h3 class="pcard__name">${p.name}</h3>`}
        ${size !== 'sm' ? html`<p class="pcard__tag">${p.tagline}</p>` : ''}
        <p class="pcard__price">
          ${p.variant_count > 1 && p.price_to > p.price_from ? html`<span class="pcard__from">de la</span>` : ''}
          ${sale ? html`<s>${lei(p.compare_from)}</s>` : ''}<b class=${cx(sale && 'is-sale')}>${lei(p.price_from)}</b>
        </p>
        ${!p.in_stock ? html`<p class="pcard__stock is-out">Stoc epuizat</p>` : p.preorder ? html`<p class="pcard__stock is-pre">Precomandă</p>` : ''}
      </div>
    </a>
  </article>`;
}

export const productGrid = (items, opts = {}) => html`<div class=${cx('pgrid', opts.cls)}>${items.map((p, i) => productCard(p, { ...opts, eager: opts.eager && i < 4 }))}</div>`;

export function breadcrumbs(items) {
  return html`<nav class="crumbs" aria-label="Breadcrumb"><ol>
    <li><a href="/">Acasă</a></li>
    ${items.map((it, i) => html`<li>${icon('chevron-right', { size: 14 })}${it.href && i < items.length - 1 ? html`<a href=${it.href}>${it.label}</a>` : html`<span aria-current="page">${it.label}</span>`}</li>`)}
  </ol></nav>`;
}

/** insigna curierului: semnul original pe o plăcuță albă (arată la fel în tema deschisă și în cea întunecată) */
export function carrierBadge(code, { size = 32 } = {}) {
  const c = carrier(code);
  return html`<span class="carrier" style=${`--cs:${size}px`} title=${c.name}><img src=${c.mark} alt="" loading="lazy" decoding="async"></span>`;
}

/** mai multe semne suprapuse (ex.: „FAN, Sameday, Cargus, DPD, GLS”) */
export function carrierStack(codes, { size = 28 } = {}) {
  return html`<span class="cstack" title=${codes.map((c) => carrier(c).name).join(', ')}>${codes.map((c) => html`<span class="carrier" style=${`--cs:${size}px`}><img src=${carrier(c).mark} alt="" loading="lazy" decoding="async"></span>`)}</span>`;
}

/** sigla completă a curierului (carduri de livrare, pagina de livrare) */
export function carrierLogo(code, { w = 76, h = 44, cls = '' } = {}) {
  const c = carrier(code);
  return html`<span class=${cx('carrier-logo', cls)} style=${`--cw:${w}px;--ch:${h}px`}><img src=${c.logo} alt=${c.name} loading="lazy" decoding="async"></span>`;
}

/** câmp de formular cu etichetă flotantă și eroare */
export function field({ name, label, type = 'text', value = '', error, required = false, autocomplete, hint, inputmode, pattern, maxlength, placeholder = ' ', cls, attrs = '' }) {
  const id = `f-${name}`;
  return html`<div class=${cx('field', error && 'has-error', cls)}>
    <input id=${id} name=${name} type=${type} value=${value ?? ''} placeholder=${placeholder}${attr('required', required)}${attr('autocomplete', !!autocomplete, autocomplete)}${attr('inputmode', !!inputmode, inputmode)}${attr('pattern', !!pattern, pattern)}${attr('maxlength', !!maxlength, maxlength)} ${raw(attrs)}${attr('aria-invalid', !!error, 'true')}${attr('aria-describedby', !!(error || hint), `${id}-msg`)}>
    <label for=${id}>${label}${required ? '' : html` <span class="opt">(opțional)</span>`}</label>
    ${type === 'password' ? html`<button class="field__eye" type="button" aria-label="Arată parola" data-toggle-password>${icon('eye', { size: 18 })}</button>` : ''}
    <p class="field__msg" id=${`${id}-msg`} data-error-for=${name}>${error || hint || ''}</p>
  </div>`;
}

export function selectField({ name, label, options, value = '', error, required = false, placeholder = 'Alege…' }) {
  const id = `f-${name}`;
  return html`<div class=${cx('field field--select', error && 'has-error', value && 'has-value')}>
    <select id=${id} name=${name}${attr('required', required)}${attr('aria-invalid', !!error, 'true')}>
      <option value="" disabled${attr('selected', !value)}>${placeholder}</option>
      ${options.map((o) => {
    const v = typeof o === 'string' ? o : o.value;
    const l = typeof o === 'string' ? o : o.label;
    return html`<option value=${v}${attr('selected', v === value)}>${l}</option>`;
  })}
    </select>
    <label for=${id}>${label}</label>
    ${icon('chevron-down', { size: 16, cls: 'field__chev' })}
    <p class="field__msg" data-error-for=${name}>${error || ''}</p>
  </div>`;
}

export function checkbox({ name, label, checked = false, error, required = false, value = 'on' }) {
  return html`<label class=${cx('check', error && 'has-error')}>
    <input type="checkbox" name=${name} value=${value}${attr('checked', checked)}${attr('required', required)}>
    <span class="check__box">${icon('check', { size: 14 })}</span><span class="check__label">${label}</span>
  </label>${error ? html`<p class="field__msg is-error" data-error-for=${name}>${error}</p>` : html`<p class="field__msg" data-error-for=${name}></p>`}`;
}

export const csrfInput = (ctx) => html`<input type="hidden" name="_csrf" value=${ctx.csrf}>`;

export function emptyState({ iconName = 'bag', title, text, cta, href }) {
  return html`<div class="empty">
    <div class="empty__icon">${icon(iconName, { size: 28 })}</div>
    <h2>${title}</h2>${text ? html`<p>${text}</p>` : ''}
    ${cta ? html`<a class="btn btn--dark" href=${href}>${cta}</a>` : ''}
  </div>`;
}

export const TONES = { blue: 'blue', leaf: 'leaf', amber: 'amber', violet: 'violet', green: 'green', red: 'red', gray: 'gray' };
export const pill = (label, tone = 'gray') => html`<span class=${`spill spill--${tone}`}>${label}</span>`;
