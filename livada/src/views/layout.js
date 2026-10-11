import fs from 'node:fs';
import path from 'node:path';
import { ROOT, config } from '../config.js';
import { html, raw, jsonLd, cx } from '../lib/html.js';
import { asset } from '../lib/static.js';
import { getSetting } from '../db/index.js';
import { categories, listProducts } from '../services/catalog.js';
import { icon } from './icons.js';
import { lei } from '../lib/format.js';

const LOGO_SVG = fs.readFileSync(path.join(ROOT, 'public/img/brand/logo.svg'), 'utf8')
  .replaceAll('#0E1411', 'var(--logo-ink)').replaceAll('#1F7A4D', 'var(--logo-leaf)')
  .replace('<svg ', '<svg class="logo-svg" focusable="false" ');
export const logo = () => raw(LOGO_SVG);

let navCache = null;
let navCacheAt = 0;
function navData() {
  if (!navCache || Date.now() - navCacheAt > 60_000) {
    navCache = categories().map((c) => ({ ...c, products: listProducts({ category: c.slug, sort: 'recommended', limit: c.slug === 'accesorii' ? 6 : 8 }) }));
    navCacheAt = Date.now();
  }
  return navCache;
}
export const invalidateNav = () => { navCache = null; };

function announcement() {
  const msgs = (getSetting('store') || {}).announcement || [];
  if (!msgs.length) return '';
  return html`<div class="announce" role="region" aria-label="Anunțuri">
    <div class="announce__track" data-rotator>${msgs.map((m, i) => html`<span class=${cx('announce__item', i === 0 && 'is-active')}>${m}</span>`)}</div>
  </div>`;
}

function header(ctx, active) {
  const nav = navData();
  const count = ctx.cartCount ? ctx.cartCount() : 0;
  return html`
  <header class="site-header" data-header>
    <div class="wrap site-header__bar">
      <button class="icon-btn site-header__menu" type="button" aria-label="Meniu" aria-expanded="false" aria-controls="mobile-nav" data-open-menu>${icon('menu', { size: 22 })}</button>
      <a class="site-header__logo" href="/" aria-label="Livada — prima pagină">${logo()}</a>
      <nav class="mainnav" aria-label="Categorii">
        <ul class="mainnav__list">
          ${nav.map((c) => html`<li class="mainnav__item">
            <a class=${cx('mainnav__link', active === c.slug && 'is-active')} href=${`/${c.slug}`}>${c.name}</a>
            <div class="flyout" role="group" aria-label=${c.title}>
              <div class="wrap flyout__inner">
                <div class="flyout__intro">
                  <p class="eyebrow">${c.title}</p>
                  <p class="flyout__tagline">${c.tagline}</p>
                  <a class="link-arrow" href=${`/${c.slug}`}>Vezi toate (${c.count}) ${icon('arrow-right', { size: 16 })}</a>
                </div>
                <ul class="flyout__grid">
                  ${c.products.map((p) => html`<li><a class="flyout__item" href=${`/produs/${p.slug}`}>
                    <span class="flyout__img">${p.image ? html`<img src=${p.image} alt="" loading="lazy" width="96" height="96">` : ''}</span>
                    <span class="flyout__name">${p.name}</span>
                    ${p.badge ? html`<span class="flyout__badge">${p.badge}</span>` : ''}
                  </a></li>`)}
                </ul>
              </div>
            </div>
          </li>`)}
        </ul>
      </nav>
      <div class="site-header__actions">
        <button class="icon-btn" type="button" aria-label="Caută" data-open-search>${icon('search', { size: 21 })}</button>
        <a class="icon-btn hide-sm" href=${ctx.user ? '/cont/favorite' : '/cont/autentificare?next=/cont/favorite'} aria-label="Favorite">${icon('heart', { size: 21 })}</a>
        <a class="icon-btn" href=${ctx.user ? '/cont' : '/cont/autentificare'} aria-label=${ctx.user ? `Contul meu (${ctx.user.first_name})` : 'Autentificare'}>
          ${ctx.user ? html`<span class="avatar" aria-hidden="true">${(ctx.user.first_name || '?')[0]}</span>` : icon('user', { size: 21 })}
        </a>
        <a class="icon-btn cart-btn" href="/cos" aria-label="Coșul de cumpărături" data-open-cart>
          ${icon('bag', { size: 21 })}<span class=${cx('cart-count', !count && 'is-empty')} data-cart-count>${count || ''}</span>
        </a>
      </div>
    </div>
  </header>
  <div class="mobile-nav" id="mobile-nav" hidden data-mobile-nav>
    <div class="mobile-nav__panel">
      <div class="mobile-nav__head"><a href="/" class="site-header__logo">${logo()}</a><button class="icon-btn" type="button" aria-label="Închide" data-close-menu>${icon('close', { size: 22 })}</button></div>
      <ul class="mobile-nav__list">
        ${nav.map((c) => html`<li><a href=${`/${c.slug}`}><span>${c.name}</span><small>${c.count}</small>${icon('chevron-right', { size: 18 })}</a></li>`)}
      </ul>
      <ul class="mobile-nav__list mobile-nav__list--small">
        <li><a href="/oferte">${icon('tag', { size: 18 })}<span>Oferte</span></a></li>
        <li><a href=${ctx.user ? '/cont' : '/cont/autentificare'}>${icon('user', { size: 18 })}<span>${ctx.user ? 'Contul meu' : 'Autentificare'}</span></a></li>
        <li><a href="/livrare">${icon('truck', { size: 18 })}<span>Livrare și lockere</span></a></li>
        <li><a href="/contact">${icon('phone', { size: 18 })}<span>Contact</span></a></li>
      </ul>
    </div>
  </div>`;
}

function footer() {
  const co = getSetting('company') || {};
  const sh = getSetting('showroom') || {};
  const cats = navData();
  return html`
  <footer class="site-footer">
    <div class="wrap">
      <div class="footer-top">
        <div class="footer-brand">
          <a href="/" class="site-header__logo" aria-label="Livada">${logo()}</a>
          <p class="footer-brand__tag">Tehnologia Apple, <em>culeasă cu grijă.</em></p>
          <form class="newsletter" action="/newsletter" method="post" data-ajax data-reset>
            <label for="nl-email">Noutăți și oferte, cel mult de două ori pe lună</label>
            <div class="newsletter__row">
              <input id="nl-email" name="email" type="email" required placeholder="email@exemplu.ro" autocomplete="email">
              <button class="btn btn--dark btn--sm" type="submit">Abonează-te</button>
            </div>
          </form>
        </div>
        <nav class="footer-cols" aria-label="Linkuri utile">
          <div><h3>Magazin</h3><ul>${cats.map((c) => html`<li><a href=${`/${c.slug}`}>${c.name}</a></li>`)}<li><a href="/oferte">Oferte</a></li></ul></div>
          <div><h3>Ajutor</h3><ul>
            <li><a href="/livrare">Livrare și lockere</a></li><li><a href="/retur">Retur 30 de zile</a></li>
            <li><a href="/garantie">Garanție și service</a></li><li><a href="/ajutor">Întrebări frecvente</a></li><li><a href="/contact">Contact</a></li>
          </ul></div>
          <div><h3>Livada</h3><ul>
            <li><a href="/despre">Despre noi</a></li><li><a href="/contact#showroom">Showroom</a></li>
            <li><a href="/termeni">Termeni și condiții</a></li><li><a href="/confidentialitate">Confidențialitate</a></li>
            <li><a href="/cookies">Cookie-uri</a></li>
          </ul></div>
          <div class="footer-contact"><h3>Contact</h3>
            <p><a href=${`tel:${(co.phone || '').replace(/\s/g, '')}`}>${co.phone}</a><br><small>${co.phone_hours}</small></p>
            <p><a href=${`mailto:${co.email}`}>${co.email}</a></p>
            <p><small>${sh.address}</small></p>
          </div>
        </nav>
      </div>
      <div class="footer-trust">
        <div class="footer-trust__marks" aria-label="Metode de plată și livrare">
          ${['Visa', 'Mastercard', 'Apple Pay', 'Google Pay', 'Ramburs', 'Rate 0%'].map((m) => html`<span class="paymark">${m}</span>`)}
        </div>
        <div class="anpc">
          <a href="https://anpc.ro/ce-este-sal/" target="_blank" rel="noopener" class="anpc__badge"><b>ANPC</b><span>Soluționarea alternativă<br>a litigiilor</span></a>
          <a href="https://ec.europa.eu/consumers/odr" target="_blank" rel="noopener" class="anpc__badge"><b>SOL</b><span>Soluționarea online<br>a litigiilor</span></a>
        </div>
      </div>
      <div class="footer-legal">
        <p>© ${new Date().getFullYear()} ${co.legal_name} · CUI ${co.cui} · Reg. Com. ${co.reg_com} · Capital social ${co.capital} · ${co.address}</p>
        <p>Livada este un magazin independent. Apple, iPhone, iPad, Mac, Apple Watch, AirPods și AirTag sunt mărci înregistrate ale Apple Inc.</p>
      </div>
    </div>
  </footer>`;
}

function overlays(ctx) {
  return html`
  <div class="search-overlay" hidden data-search-overlay role="dialog" aria-modal="true" aria-label="Caută în magazin">
    <div class="search-overlay__panel">
      <form class="wrap search-overlay__form" action="/cautare" method="get" role="search">
        ${icon('search', { size: 22 })}
        <input type="search" name="q" placeholder="Caută iPhone, MacBook, husă MagSafe…" autocomplete="off" aria-label="Caută" data-search-input>
        <button class="icon-btn" type="button" aria-label="Închide căutarea" data-close-search>${icon('close', { size: 22 })}</button>
      </form>
      <div class="wrap search-overlay__body" data-search-results>
        <p class="eyebrow">Căutări populare</p>
        <div class="chips">${['iPhone 18 Pro', 'iPhone Duo', 'MacBook Air', 'AirPods Pro 3', 'Apple Watch', 'Husă MagSafe', 'iPad Air', 'AirTag'].map((q) => html`<a class="chip" href=${`/cautare?q=${encodeURIComponent(q)}`}>${q}</a>`)}</div>
      </div>
    </div>
  </div>
  <aside class="drawer" id="cart-drawer" hidden data-cart-drawer aria-label="Coșul tău" role="dialog" aria-modal="true">
    <div class="drawer__panel">
      <div class="drawer__head"><h2>Coșul tău</h2><button class="icon-btn" type="button" aria-label="Închide coșul" data-close-cart>${icon('close', { size: 22 })}</button></div>
      <div class="drawer__body" data-cart-body><div class="skeleton-list"><span></span><span></span></div></div>
      <div class="drawer__foot" data-cart-foot hidden></div>
    </div>
  </aside>
  <div class="toasts" aria-live="polite" data-toasts></div>
  <div class="cookie" hidden data-cookie role="dialog" aria-label="Preferințe cookie">
    <p><b>Folosim cookie-uri</b> esențiale pentru coș și autentificare. Cu acordul tău, folosim și cookie-uri de analiză ca să îmbunătățim magazinul. <a href="/cookies">Detalii</a></p>
    <div class="cookie__actions"><button class="btn btn--ghost btn--sm" type="button" data-cookie-choice="essential">Doar esențiale</button><button class="btn btn--dark btn--sm" type="button" data-cookie-choice="all">Accept toate</button></div>
  </div>`;
}

/**
 * Layout-ul magazinului.
 * @param {object} ctx
 * @param {{title:string, description?:string, body:any, active?:string, canonical?:string, image?:string, jsonld?:object[], scripts?:string[], bodyClass?:string, noindex?:boolean, minimal?:boolean}} o
 */
export function page(ctx, o) {
  const title = o.title ? `${o.title} — Livada` : 'Livada — Tehnologia Apple, culeasă cu grijă';
  const desc = o.description || 'Magazin online de produse Apple în România: iPhone, Mac, iPad, Apple Watch, AirPods și accesorii. Livrare în 24h prin curier sau easybox, retur 30 de zile, rate 0%.';
  const canonical = `${config.publicUrl}${o.canonical || ctx.path}`;
  const image = o.image ? (o.image.startsWith('http') ? o.image : `${config.publicUrl}${o.image}`) : `${config.publicUrl}/img/brand/og-default.png`;
  const flash = ctx.flashMessage;
  return html`<!doctype html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${title}</title>
<meta name="description" content=${desc}>
<link rel="canonical" href=${canonical}>
${o.noindex ? raw('<meta name="robots" content="noindex, nofollow">') : ''}
<meta name="theme-color" content="#FFFFFF" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0B0F0D" media="(prefers-color-scheme: dark)">
<meta property="og:type" content=${o.ogType || 'website'}><meta property="og:site_name" content="Livada">
<meta property="og:title" content=${title}><meta property="og:description" content=${desc}>
<meta property="og:url" content=${canonical}><meta property="og:image" content=${image}><meta property="og:locale" content="ro_RO">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="/img/brand/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/img/brand/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<link rel="preload" href="/fonts/inter-latin.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href=${asset('/css/store.css')}>
<meta name="csrf-token" content=${ctx.csrf}>
${(o.jsonld || []).map((j) => jsonLd(j))}
</head>
<body class=${cx(o.bodyClass)} data-user=${ctx.user ? '1' : '0'}>
<a class="skip" href="#main">Sari la conținut</a>
${o.minimal ? '' : announcement()}
${o.minimal ? html`<header class="site-header site-header--minimal"><div class="wrap site-header__bar"><a class="site-header__logo" href="/" aria-label="Livada">${logo()}</a>
  <span class="secure-note">${icon('shield', { size: 18 })} Plată securizată</span></div></header>` : header(ctx, o.active)}
${flash ? html`<div class="wrap"><div class=${`flash flash--${flash.type}`} role="status">${icon(flash.type === 'error' ? 'info' : 'check', { size: 18 })}<span>${flash.message}</span></div></div>` : ''}
<main id="main" class=${cx('main', o.mainClass)}>${o.body}</main>
${o.minimal ? '' : footer()}
${overlays(ctx)}
<script type="module" src=${asset('/js/app.js')}></script>
${(o.scripts || []).map((s) => html`<script type="module" src=${asset(s)}></script>`)}
</body>
</html>`;
}

export const priceTag = (bani, compare) => html`<span class="price">${compare ? html`<s class="price__old">${lei(compare)}</s>` : ''}<span class=${cx('price__now', compare && 'is-sale')}>${lei(bani)}</span></span>`;
