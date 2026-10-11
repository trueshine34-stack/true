import { html, cx, attr } from '../../lib/html.js';
import { page } from '../layout.js';
import { productGrid, breadcrumbs, emptyState } from '../components.js';
import { icon } from '../icons.js';
import { plural } from '../../lib/format.js';

const SORT_LABELS = { recommended: 'Recomandate', new: 'Cele mai noi', 'price-asc': 'Preț crescător', 'price-desc': 'Preț descrescător', name: 'Nume A–Z' };
const PRICE_BANDS = [[0, 500], [500, 1500], [1500, 5000], [5000, 10000], [10000, null]];

function qs(base, params, patch) {
  const p = new URLSearchParams(params);
  for (const [k, v] of Object.entries(patch)) { if (v === null || v === undefined || v === '') p.delete(k); else p.set(k, v); }
  const s = p.toString();
  return s ? `${base}?${s}` : base;
}

/**
 * @param {{category?:object, title:string, tagline?:string, products:object[], groups?:string[], base:string, query:URLSearchParams, crumbs:object[], showFilters?:boolean}} o
 */
export function listingPage(ctx, o) {
  const q = o.query;
  const group = q.get('grup') || '';
  const sort = SORT_LABELS[q.get('sort')] ? q.get('sort') : 'recommended';
  const band = q.get('pret') || '';
  const inStock = q.get('stoc') === '1';
  const promo = q.get('oferte') === '1';
  const params = Object.fromEntries([...q.entries()].filter(([k]) => ['grup', 'sort', 'pret', 'stoc', 'oferte', 'q'].includes(k)));
  const activeFilters = [band && 'pret', inStock && 'stoc', promo && 'oferte'].filter(Boolean).length;

  const body = html`
  <section class="listing-head">
    <div class="wrap">
      ${breadcrumbs(o.crumbs)}
      <div class="listing-head__row">
        <div>
          <h1 class="h1">${o.title}</h1>
          ${o.tagline ? html`<p class="listing-head__tag">${o.tagline}</p>` : ''}
        </div>
        <p class="listing-head__count">${plural(o.products.length, 'produs', 'produse', 'de produse')}</p>
      </div>
      ${o.groups?.length ? html`<div class="tabs" role="tablist" aria-label="Subcategorii">
        <a class=${cx('tab', !group && 'is-active')} href=${qs(o.base, params, { grup: null })} role="tab" aria-selected=${String(!group)}>Toate</a>
        ${o.groups.map((g) => html`<a class=${cx('tab', group === g && 'is-active')} href=${qs(o.base, params, { grup: g })} role="tab" aria-selected=${String(group === g)}>${g}</a>`)}
      </div>` : ''}
    </div>
  </section>
  <section class="section section--listing">
    <div class="wrap">
      ${o.showFilters === false ? '' : html`<div class="toolbar">
        <details class="filters" ${attr('open', false)}>
          <summary class="btn btn--ghost btn--sm">${icon('filter', { size: 16 })} Filtre${activeFilters ? html` <span class="filters__count">${activeFilters}</span>` : ''}</summary>
          <form class="filters__panel" method="get" action=${o.base}>
            ${group ? html`<input type="hidden" name="grup" value=${group}>` : ''}
            ${q.get('q') ? html`<input type="hidden" name="q" value=${q.get('q')}>` : ''}
            <input type="hidden" name="sort" value=${sort}>
            <fieldset><legend>Preț</legend>
              <label class="radio-chip"><input type="radio" name="pret" value=""${attr('checked', !band)}><span>Orice preț</span></label>
              ${PRICE_BANDS.map(([a, b]) => {
    const v = `${a}-${b ?? ''}`;
    return html`<label class="radio-chip"><input type="radio" name="pret" value=${v}${attr('checked', band === v)}><span>${b ? `${a.toLocaleString('ro-RO')} – ${b.toLocaleString('ro-RO')} lei` : `peste ${a.toLocaleString('ro-RO')} lei`}</span></label>`;
  })}
            </fieldset>
            <fieldset><legend>Disponibilitate</legend>
              <label class="switch"><input type="checkbox" name="stoc" value="1"${attr('checked', inStock)}><span></span>Doar produse în stoc</label>
              <label class="switch"><input type="checkbox" name="oferte" value="1"${attr('checked', promo)}><span></span>Doar oferte</label>
            </fieldset>
            <div class="filters__actions"><a class="btn btn--ghost btn--sm" href=${qs(o.base, { ...(group ? { grup: group } : {}), ...(q.get('q') ? { q: q.get('q') } : {}) }, {})}>Resetează</a><button class="btn btn--dark btn--sm" type="submit">Aplică</button></div>
          </form>
        </details>
        <form class="sort" method="get" action=${o.base} data-autosubmit>
          ${Object.entries(params).filter(([k]) => k !== 'sort').map(([k, v]) => html`<input type="hidden" name=${k} value=${v}>`)}
          <label for="sort-select" class="visually-hidden">Sortează</label>
          <select id="sort-select" name="sort">${Object.entries(SORT_LABELS).map(([v, l]) => html`<option value=${v}${attr('selected', v === sort)}>${l}</option>`)}</select>
          ${icon('chevron-down', { size: 16 })}
        </form>
      </div>`}
      ${o.products.length ? productGrid(o.products, { eager: true }) : emptyState({ iconName: 'search', title: 'Niciun produs nu se potrivește', text: 'Încearcă alte filtre sau caută după numele modelului.', cta: 'Vezi toate produsele', href: o.base })}
    </div>
  </section>`;
  return page(ctx, { title: o.metaTitle || o.title, description: o.description, body, active: o.category?.slug, canonical: o.canonical });
}

export function parsePriceBand(v) {
  const m = /^(\d+)-(\d*)$/.exec(v || '');
  if (!m) return {};
  return { min: Number(m[1]) * 100 || undefined, max: m[2] ? Number(m[2]) * 100 : undefined };
}
