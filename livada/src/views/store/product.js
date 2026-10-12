import { html, cx, jsonScript, attr } from '../../lib/html.js';
import { config } from '../../config.js';
import { page } from '../layout.js';
import { breadcrumbs, productCard, carrierBadge, carrierStack, csrfInput } from '../components.js';
import { icon } from '../icons.js';
import { lei } from '../../lib/format.js';
import { t, tHtml, locale, localizePath } from '../../lib/i18n.js';
import { imageFor, variantLabel } from '../../services/catalog.js';
import { estimate, methodByCode, lockerTotal } from '../../services/shipping.js';
import { getSetting } from '../../db/index.js';

function pickVariant(p, sku) {
  const bySku = p.variants.find((v) => v.sku === sku);
  if (bySku) return bySku;
  // implicit: varianta disponibilă în culoarea „de copertă” (prima imagine), apoi cea mai ieftină disponibilă
  const cover = p.image_key ? Object.keys(p.images)[0] : null;
  const ok = (v) => v.stock > 0 || p.preorder;
  return p.variants.find((v) => ok(v) && (!cover || v.options[p.image_key] === cover)) || p.variants.find(ok) || p.variants[0];
}

function stockLine(p, v) {
  if (p.preorder) return html`<p class="stock stock--pre">${icon('calendar', { size: 16 })}<span><b>${t('Precomandă.')}</b> ${p.release_note || t('Livrare la lansare')}</span></p>`;
  if (v.stock <= 0) return html`<p class="stock stock--out">${icon('info', { size: 16 })}<span>${tHtml('<b>Stoc epuizat</b> în această configurație. Alege altă variantă.')}</span></p>`;
  const eta = estimate(methodByCode('courier_fan'));
  return html`<p class=${cx('stock', v.stock <= 3 ? 'stock--low' : 'stock--in')}><span class="dot dot--live"></span><span><b>${v.stock <= 3 ? t('Doar {n} în stoc', { n: v.stock }) : t('În stoc')}.</b> ${t('Livrare estimată:')} ${eta}</span></p>`;
}

export function productPage(ctx, p, related) {
  const v = pickVariant(p, ctx.query.get('v'));
  const imgKey = p.image_key && p.images[v.options[p.image_key]] ? v.options[p.image_key] : Object.keys(p.images)[0];
  const gallery = p.images[imgKey] || [];
  const store = getSetting('store') || {};
  const showInstallments = v.price >= (store.installments_min ?? 50000);
  const easybox = methodByCode('locker_easybox');
  const showroom = getSetting('showroom') || {};
  const data = {
    id: p.id, slug: p.slug, name: p.name, image_key: p.image_key, preorder: p.preorder,
    options: p.options, images: p.images,
    variants: p.variants.map((x) => ({ id: x.id, sku: x.sku, mpn: x.mpn, options: x.options, price: x.price, compare_at: x.compare_at, stock: x.stock })),
    selected: v.id, installmentsMin: store.installments_min ?? 50000, eta: estimate(methodByCode('courier_fan')),
  };

  const optionBlock = (o) => {
    const current = o.values.find((x) => x.value === v.options[o.key]);
    if (o.key === 'color') {
      return html`<fieldset class="opt" data-option=${o.key}>
        <legend class="opt__legend">${o.label} <span class="opt__value" data-option-value>${current?.label}</span></legend>
        <div class="swatches swatches--lg">
          ${o.values.map((x) => html`<label class="swatch-pick" title=${x.label}>
            <input type="radio" name=${`opt-${o.key}`} value=${x.value}${attr('checked', x.value === current?.value)} data-label=${x.label}>
            <span class="swatch" style=${`--c:${x.hex}`}></span><span class="visually-hidden">${x.label}</span>
          </label>`)}
        </div>
      </fieldset>`;
    }
    // multe valori scurte (ex. mărimile 0–12 ale curelei Solo Loop) → plăcuțe compacte; altfel listă sau grilă
    const chips = o.values.length > 5 && o.values.every((x) => x.label.length <= 8 && !x.hint);
    const layout = chips ? 'opt__grid--chips' : o.values.length > 3 || o.values.some((x) => x.label.length > 18) ? 'opt__grid--list' : '';
    return html`<fieldset class="opt" data-option=${o.key}>
      <legend class="opt__legend">${o.label} <span class="opt__value" data-option-value>${current?.label}</span></legend>
      ${o.note ? html`<p class="opt__note">${o.note}</p>` : ''}
      <div class=${cx('opt__grid', layout)}>
        ${o.values.map((x) => html`<label class="opt-card">
          <input type="radio" name=${`opt-${o.key}`} value=${x.value}${attr('checked', x.value === current?.value)} data-label=${x.label}>
          <span class="opt-card__box"><span class="opt-card__label">${x.label}</span>${x.hint ? html`<span class="opt-card__hint">${x.hint}</span>` : ''}<span class="opt-card__delta" data-delta=${x.value}></span></span>
        </label>`)}
      </div>
    </fieldset>`;
  };

  const body = html`
  <div class="wrap">${breadcrumbs([{ label: p.category_name, href: `/${p.category_slug}` }, { label: p.name }])}</div>
  <section class="pdp" data-pdp>
    <div class="wrap pdp__grid">
      <div class="gallery" data-gallery>
        <div class="gallery__stage">
          ${p.badge ? html`<span class=${cx('badge', p.badge === 'Precomandă' ? 'badge--dark' : 'badge--leaf')}>${t(p.badge)}</span>` : ''}
          <div class="gallery__track" data-gallery-track tabindex="0" role="region" aria-label=${t('Galerie foto')}>
            ${gallery.map((img, i) => html`<figure class="gallery__slide"><img src=${img['1000']} alt=${`${p.name} ${variantLabel(p, v.options)}`} width="1000" height="1000" ${i === 0 ? html`fetchpriority="high"` : html`loading="lazy"`} decoding="async"></figure>`)}
          </div>
        </div>
        ${gallery.length > 1 ? html`<div class="gallery__thumbs" data-gallery-thumbs>${gallery.map((img, i) => html`<button type="button" class=${cx('gallery__thumb', i === 0 && 'is-active')} aria-label=${t('Imaginea {n}', { n: i + 1 })} data-index=${i}><img src=${img['480']} alt="" width="80" height="80" loading="lazy"></button>`)}</div>` : ''}
      </div>

      <div class="buybox">
        <p class="eyebrow"><a href=${`/${p.category_slug}`}>${p.category_name}</a>${p.grp && p.grp !== p.category_name ? ` · ${p.grp}` : ''}</p>
        <h1 class="buybox__title">${p.name}</h1>
        <p class="buybox__tag">${p.tagline}</p>
        <div class="buybox__price" data-price>
          ${v.compare_at ? html`<s class="price__old" data-compare>${lei(v.compare_at)}</s>` : html`<s class="price__old" data-compare hidden></s>`}
          <span class=${cx('price__big', v.compare_at && 'is-sale')} data-amount>${lei(v.price)}</span>
          <span class="price__vat">${t('TVA inclus')}</span>
        </div>
        <p class="buybox__rate" data-installments ${showInstallments ? '' : html`hidden`}>${tHtml('sau <b data-monthly>{price}</b>/lună în 12 rate fără dobândă', { price: lei(Math.ceil(v.price / 12)) })}</p>

        <form class="buybox__form" action="/cos/adauga" method="post" data-add-to-cart>
          ${csrfInput(ctx)}
          <input type="hidden" name="variant_id" value=${v.id} data-variant-input>
          ${p.options.map(optionBlock)}
          <div data-stock>${stockLine(p, v)}</div>
          <div class="buybox__actions">
            <button class="btn btn--dark btn--lg btn--block" type="submit" data-add-btn ${v.stock <= 0 && !p.preorder ? html`disabled` : ''}>
              ${icon('bag', { size: 20 })}<span data-add-label>${p.preorder ? t('Precomandă') : t('Adaugă în coș')}</span>
            </button>
            <button class="btn btn--ghost btn--lg btn--icon" type="button" aria-label=${t('Adaugă la favorite')} aria-pressed="false" data-favorite=${p.id}>${icon('heart', { size: 20 })}</button>
          </div>
          <p class="buybox__sku">${t('Cod:')} <span data-sku>${v.sku}</span>${v.mpn ? html` · Apple <span data-mpn>${v.mpn}</span>` : html`<span data-mpn></span>`}</p>
        </form>

        <ul class="perks">
          <li>${carrierStack(['fan', 'sameday', 'cargus', 'dpd', 'gls'], { size: 28 })}<div><b>${t('Livrare la adresă')}</b><span>${estimate(methodByCode('courier_fan'))} · ${t('gratuit de la 500 lei')}</span></div></li>
          <li>${carrierStack(['easybox', 'fanbox', 'dpd', 'gls'], { size: 28 })}<div><b>${t('Lockere și puncte de ridicare')}</b><span>${t('{n} în toată țara', { n: lockerTotal().toLocaleString(locale()) })} · ${easybox ? estimate(easybox) : t('în 24 de ore')} · ${t('gratuit de la 300 lei')}</span></div></li>
          <li>${carrierBadge('livada', { size: 28 })}<div><b>${t('Ridicare din showroom')}</b><span>${showroom.address?.split(',').slice(0, 2).join(',')} · ${t('gratuit')}</span></div></li>
          <li><span class="perks__icon">${icon('return', { size: 18 })}</span><div><b>${t('Retur gratuit 30 de zile')}</b><span>${p.warranty || t('Garanție 24 de luni')}</span></div></li>
        </ul>
      </div>
    </div>
  </section>

  ${p.highlights.length ? html`<section class="section section--tight"><div class="wrap"><ul class="highlights">${p.highlights.map((h) => html`<li>${icon('leaf', { size: 18 })}<span>${h}</span></li>`)}</ul></div></section>` : ''}

  <section class="section">
    <div class="wrap pdp-info">
      <div class="pdp-info__main">
        <h2 class="h2">${t('Despre {name}', { name: p.name })}</h2>
        ${p.description.map((d) => html`<p class="prose">${d}</p>`)}
        ${p.specs.length ? html`<h2 class="h3 pdp-info__sub">${t('Specificații')}</h2>
        <div class="specs">${p.specs.map((g) => html`<div class="specs__group"><h3>${g.group}</h3><dl>${g.rows.map((r) => html`<div><dt>${r.k}</dt><dd>${r.v}</dd></div>`)}</dl></div>`)}</div>` : ''}
      </div>
      <div class="pdp-info__side">
        ${p.in_box.length ? html`<div class="side-card"><h3>${icon('package', { size: 18 })} ${t('În cutie')}</h3><ul>${p.in_box.map((x) => html`<li>${x}</li>`)}</ul></div>` : ''}
        <div class="side-card"><h3>${icon('shield', { size: 18 })} ${t('Garanție și retur')}</h3>
          <p>${t('Produs nou, sigilat, cu factură fiscală și garanție de 24 de luni. Îl poți returna gratuit în 30 de zile de la livrare.')}</p>
          <a class="link-arrow" href="/retur">${t('Cum funcționează returul')} ${icon('arrow-right', { size: 14 })}</a></div>
        <div class="side-card"><h3>${icon('calendar', { size: 18 })} ${t('Rate fără dobândă')}</h3>
          <p>${t('3, 6 sau 12 rate 0% cu cardurile de credit partenere, pentru comenzi de peste 500 lei.')}</p></div>
      </div>
    </div>
  </section>

  ${related.length ? html`<section class="section section--last"><div class="wrap">
    <div class="section__head"><div><p class="eyebrow">${t('Recomandate')}</p><h2 class="h2">${t('Merg perfect împreună.')}</h2></div></div>
    <div class="hscroll" data-hscroll><div class="hscroll__track">${related.map((r) => productCard(r, { size: 'sm' }))}</div>
      <button class="hscroll__btn hscroll__btn--prev" type="button" aria-label=${t('Înapoi')} data-hscroll-prev>${icon('chevron-left', { size: 20 })}</button>
      <button class="hscroll__btn hscroll__btn--next" type="button" aria-label=${t('Înainte')} data-hscroll-next>${icon('chevron-right', { size: 20 })}</button></div>
  </div></section>` : ''}

  <div class="buybar" data-buybar>
    <div class="wrap buybar__inner">
      <div class="buybar__info"><b>${p.name}</b><span data-buybar-price>${lei(v.price)}</span></div>
      <button class="btn btn--dark" type="button" data-buybar-add ${v.stock <= 0 && !p.preorder ? html`disabled` : ''}>${p.preorder ? t('Precomandă') : t('Adaugă în coș')}</button>
    </div>
  </div>
  ${jsonScript('product-data', data)}`;

  const prices = p.variants.map((x) => x.price);
  const ld = {
    '@context': 'https://schema.org', '@type': 'Product', name: p.name, description: p.description.join(' '),
    brand: { '@type': 'Brand', name: /Beats/.test(p.name) ? 'Beats' : 'Apple' },
    image: Object.values(p.images).flat().slice(0, 6).map((i) => `${config.publicUrl}${i['1000']}`),
    sku: v.sku, mpn: v.mpn || undefined, category: p.category_name,
    offers: {
      '@type': 'AggregateOffer', priceCurrency: 'RON', lowPrice: (Math.min(...prices) / 100).toFixed(2), highPrice: (Math.max(...prices) / 100).toFixed(2),
      offerCount: p.variants.length, availability: p.preorder ? 'https://schema.org/PreOrder' : p.variants.some((x) => x.stock > 0) ? 'https://schema.org/InStock' : 'https://schema.org/OutOfStock',
      seller: { '@type': 'Organization', name: 'Livada' },
    },
  };
  const crumbsLd = { '@context': 'https://schema.org', '@type': 'BreadcrumbList', itemListElement: [
    { '@type': 'ListItem', position: 1, name: t('Acasă'), item: `${config.publicUrl}${localizePath('/').replace(/^\/$/, '')}` },
    { '@type': 'ListItem', position: 2, name: p.category_name, item: `${config.publicUrl}${localizePath(`/${p.category_slug}`)}` },
    { '@type': 'ListItem', position: 3, name: p.name, item: `${config.publicUrl}${localizePath(`/produs/${p.slug}`)}` }] };

  return page(ctx, {
    title: t('{name} — preț și livrare în România', { name: p.name }), description: t('{name}: {tagline} De la {price}, livrare în 24h, retur 30 de zile, rate 0%.', { name: p.name, tagline: p.tagline, price: lei(Math.min(...prices)) }),
    body, active: p.category_slug, canonical: `/produs/${p.slug}`, image: imageFor(p, v.options, '1000'), ogType: 'product',
    jsonld: [ld, crumbsLd], scripts: ['/js/product.js'], bodyClass: 'is-pdp',
  });
}
