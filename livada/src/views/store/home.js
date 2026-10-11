import { html } from '../../lib/html.js';
import { config } from '../../config.js';
import { getSetting } from '../../db/index.js';
import { page } from '../layout.js';
import { productGrid, productCard, carrierBadge } from '../components.js';
import { icon } from '../icons.js';
import { lei } from '../../lib/format.js';
import { categories, listProducts, productBySlug } from '../../services/catalog.js';
import { lockerStats, cutoffHint } from '../../services/shipping.js';

const CAT_IMG = {
  iphone: '/img/products/iphone-18-pro/silver-1-480.webp', mac: '/img/products/macbook-air-13-m5/skyblue-1-480.webp',
  ipad: '/img/products/ipad-air-11-m4/purple-1-480.webp', watch: '/img/products/apple-watch-series-12/lightgold-1-480.webp',
  airpods: '/img/products/airpods-pro-3/main-1-480.webp', 'tv-home': '/img/products/homepod-mini/orange-1-480.webp',
  accesorii: '/img/products/incarcator-magsafe/main-1-480.webp',
};

export function homePage(ctx) {
  const hero = productBySlug('iphone-18-pro');
  const duo = productBySlug('iphone-duo');
  const neo = productBySlug('macbook-neo');
  const watch = productBySlug('apple-watch-series-12');
  const featured = listProducts({ featured: true, limit: 8 });
  const accessories = listProducts({ category: 'accesorii', sort: 'recommended', inStock: true, limit: 40 }).slice(0, 8);
  const deals = listProducts({ promo: true, limit: 4 });
  const cats = categories();
  const stats = lockerStats();
  const totalLockers = stats.reduce((s, x) => s + x.n, 0);
  const co = getSetting('company') || {};
  const sh = getSetting('showroom') || {};
  const minPrice = (p) => Math.min(...p.variants.map((v) => v.price));
  const hint = cutoffHint();

  const body = html`
  <section class="hero">
    <div class="wrap hero__grid">
      <div class="hero__copy">
        <p class="eyebrow eyebrow--leaf">${icon('sparkle', { size: 14 })} Nou · iPhone 18 Pro și iPhone Duo</p>
        <h1 class="display">Tehnologia Apple,<br><em>culeasă cu grijă.</em></h1>
        <p class="lead">Cele mai noi iPhone, Mac, iPad și Apple Watch, livrate în 24 de ore oriunde în România — la ușă, în easybox sau FANbox. Cu retur gratuit 30 de zile și rate 0%.</p>
        <div class="hero__cta">
          <a class="btn btn--dark btn--lg" href="/produs/iphone-18-pro">Cumpără iPhone 18 Pro</a>
          <a class="btn btn--ghost btn--lg" href="/iphone">Toate modelele ${icon('arrow-right', { size: 18 })}</a>
        </div>
        <ul class="hero__perks">
          <li>${icon('truck', { size: 18 })} Livrare gratuită de la 500 lei</li>
          <li>${icon('return', { size: 18 })} Retur 30 de zile</li>
          <li>${icon('calendar', { size: 18 })} Rate 0% până la 12 luni</li>
        </ul>
      </div>
      <div class="hero__stage">
        <div class="hero__glow" aria-hidden="true"></div>
        <img class="hero__img" src=${hero.images.burgundy[0]['1000']} alt="iPhone 18 Pro Burgundia" width="1000" height="1000" fetchpriority="high">
        <a class="float-card float-card--price" href="/produs/iphone-18-pro">
          <span class="float-card__label">iPhone 18 Pro</span>
          <span class="float-card__value">de la ${lei(minPrice(hero))}</span>
          <span class="float-card__sub">sau ${lei(Math.ceil(minPrice(hero) / 12))}/lună · 12 rate 0%</span>
        </a>
        <div class="float-card float-card--ship">
          <span class="dot dot--live" aria-hidden="true"></span>
          <span>${hint || 'Livrare de mâine în toată țara'}</span>
        </div>
      </div>
    </div>
  </section>

  <section class="section section--tight">
    <div class="wrap">
      <ul class="catrail" aria-label="Categorii">
        ${cats.map((c) => html`<li><a class="catrail__item" href=${`/${c.slug}`}>
          <span class="catrail__img"><img src=${CAT_IMG[c.slug]} alt="" width="120" height="120" loading="lazy"></span>
          <span class="catrail__name">${c.name}</span>
        </a></li>`)}
      </ul>
    </div>
  </section>

  <section class="section">
    <div class="wrap">
      <div class="spotlight">
        <div class="spotlight__copy">
          <p class="eyebrow eyebrow--light">Precomandă · livrare din 23 octombrie</p>
          <h2 class="display display--md">iPhone Duo.<br><em>Se deschide o lume.</em></h2>
          <p class="spotlight__text">Primul iPhone pliabil: ecran interior de 7,6" fără cută vizibilă, titan de 5,2 mm și cipul A20 Pro. Precomandă acum și îl primești printre primii din România.</p>
          <div class="spotlight__cta">
            <a class="btn btn--light btn--lg" href="/produs/iphone-duo">Precomandă de la ${lei(minPrice(duo))}</a>
            <span class="spotlight__note">${icon('shield', { size: 16 })} Fără plată în avans la ramburs</span>
          </div>
        </div>
        <div class="spotlight__media">
          <img src=${duo.images['night-sky'][0]['1000']} alt="iPhone Duo Night Sky" width="1000" height="1000" loading="lazy">
        </div>
      </div>
    </div>
  </section>

  <section class="section">
    <div class="wrap">
      <div class="section__head">
        <div><p class="eyebrow">Noutăți</p><h2 class="h2">Cele mai noi de la Apple.</h2></div>
        <a class="link-arrow" href="/noutati">Toate noutățile ${icon('arrow-right', { size: 16 })}</a>
      </div>
      ${productGrid(featured, { eager: false })}
    </div>
  </section>

  <section class="section section--mist">
    <div class="wrap">
      <ul class="benefits">
        <li><span class="benefits__icon">${icon('truck', { size: 22 })}</span><div><b>Livrare în 24 de ore</b><p>Cu FAN Courier, Sameday, Cargus, DPD, GLS sau în aceeași zi în București.</p></div></li>
        <li><span class="benefits__icon">${icon('locker', { size: 22 })}</span><div><b>${totalLockers.toLocaleString('ro-RO')} de lockere pe hartă</b><p>easybox, FANbox, Ship & Go, DPD, GLS și Packeta — alegi locul pe hartă.</p></div></li>
        <li><span class="benefits__icon">${icon('return', { size: 22 })}</span><div><b>Retur gratuit 30 de zile</b><p>Dublu față de minimul legal. Ridicăm noi coletul.</p></div></li>
        <li><span class="benefits__icon">${icon('shield', { size: 22 })}</span><div><b>Garanție 24 de luni</b><p>Produse noi, sigilate, cu factură. Service autorizat.</p></div></li>
      </ul>
    </div>
  </section>

  <section class="section">
    <div class="wrap duo-tiles">
      <a class="tile tile--neo" href="/produs/macbook-neo">
        <div class="tile__copy"><p class="eyebrow">MacBook Neo</p><h3 class="h3">Primul tău Mac. <em>În patru culori.</em></h3><p>de la ${lei(minPrice(neo))}</p></div>
        <div class="tile__imgs">${['citrus', 'blush', 'indigo'].map((c, i) => html`<img src=${neo.images[c][0]['480']} alt="" width="480" height="480" loading="lazy" style=${`--i:${i}`}>`)}</div>
      </a>
      <a class="tile tile--watch" href="/produs/apple-watch-series-12">
        <div class="tile__copy"><p class="eyebrow">Apple Watch Series 12</p><h3 class="h3">Grija de tine, <em>la încheietură.</em></h3><p>de la ${lei(minPrice(watch))}</p></div>
        <div class="tile__imgs tile__imgs--single"><img src=${watch.images.radiantgold[0]['1000']} alt="" width="1000" height="1000" loading="lazy"></div>
      </a>
    </div>
  </section>

  ${deals.length ? html`<section class="section">
    <div class="wrap">
      <div class="section__head"><div><p class="eyebrow eyebrow--sale">Oferte</p><h2 class="h2">Prețuri mai mici, aceeași calitate.</h2></div>
      <a class="link-arrow" href="/oferte">Toate ofertele ${icon('arrow-right', { size: 16 })}</a></div>
      ${productGrid(deals)}
    </div>
  </section>` : ''}

  <section class="section">
    <div class="wrap">
      <div class="section__head"><div><p class="eyebrow">Accesorii</p><h2 class="h2">Completează-ți setup-ul.</h2></div>
      <a class="link-arrow" href="/accesorii">Toate accesoriile ${icon('arrow-right', { size: 16 })}</a></div>
      <div class="hscroll" data-hscroll>
        <div class="hscroll__track">${accessories.map((p) => productCard(p, { size: 'sm' }))}</div>
        <button class="hscroll__btn hscroll__btn--prev" type="button" aria-label="Înapoi" data-hscroll-prev>${icon('chevron-left', { size: 20 })}</button>
        <button class="hscroll__btn hscroll__btn--next" type="button" aria-label="Înainte" data-hscroll-next>${icon('chevron-right', { size: 20 })}</button>
      </div>
    </div>
  </section>

  <section class="section">
    <div class="wrap">
      <div class="deliver">
        <div class="deliver__copy">
          <p class="eyebrow">Livrare</p>
          <h2 class="h2">Livrăm cum îți convine.</h2>
          <p class="lead lead--sm">La ușă cu oricare dintre marii curieri, în lockerul de lângă casă sau direct din showroom-ul nostru din București. Alegi lockerul pe hartă, la checkout.</p>
          <a class="btn btn--ghost" href="/livrare">Metode și tarife ${icon('arrow-right', { size: 18 })}</a>
        </div>
        <ul class="deliver__list">
          ${[['fan', 'FAN Courier', 'La adresă · 24h'], ['sameday', 'Sameday', 'La adresă · 24h'], ['easybox', 'easybox', `${stats.find((s) => s.provider === 'easybox')?.n || 0} lockere`],
    ['fanbox', 'FANbox', `${stats.find((s) => s.provider === 'fanbox')?.n || 0} lockere`], ['cargus', 'Cargus', 'Adresă · Ship & Go'], ['dpd', 'DPD', 'Adresă · DPD Box'],
    ['gls', 'GLS', 'Adresă · ParcelLocker'], ['posta', 'Poșta Română', '2–5 zile'], ['packeta', 'Packeta', 'Z-BOX'], ['livada', 'Showroom', 'Ridicare în 2 ore']]
    .map(([c, n, s]) => html`<li>${carrierBadge(c, { size: 40 })}<span><b>${n}</b><small>${s}</small></span></li>`)}
        </ul>
      </div>
    </div>
  </section>

  <section class="section section--last">
    <div class="wrap story">
      <div class="story__copy">
        <p class="eyebrow">De ce Livada</p>
        <h2 class="h2">O livadă se îngrijește. <em>La fel și clienții.</em></h2>
        <p>Suntem o echipă mică din București care vinde un singur lucru: tehnologie Apple, nouă și sigilată, cu factură și garanție. Fără marketplace, fără vânzători terți, fără surprize.</p>
        <p>Te ajutăm să alegi configurația potrivită, îți transferăm datele de pe vechiul telefon în showroom și răspundem la telefon de luni până sâmbătă.</p>
        <div class="story__facts">
          <div><b>24h</b><span>livrare în toată țara</span></div>
          <div><b>30 zile</b><span>retur gratuit</span></div>
          <div><b>0%</b><span>dobândă la rate</span></div>
        </div>
      </div>
      <div class="story__card" id="showroom">
        <p class="eyebrow">Showroom</p>
        <h3 class="h3">${sh.name}</h3>
        <p>${icon('pin', { size: 18 })} ${sh.address}</p>
        <p>${icon('clock', { size: 18 })} ${sh.hours}</p>
        <p>${icon('phone', { size: 18 })} <a href=${`tel:${(co.phone || '').replace(/\s/g, '')}`}>${co.phone}</a></p>
        <a class="btn btn--dark" href="/contact#showroom">Vino să le încerci</a>
      </div>
    </div>
  </section>`;

  const org = {
    '@context': 'https://schema.org', '@type': 'Organization', name: 'Livada', legalName: co.legal_name, url: config.publicUrl,
    logo: `${config.publicUrl}/img/brand/icon-512.png`, email: co.email, telephone: co.phone,
    address: { '@type': 'PostalAddress', streetAddress: sh.address, addressLocality: 'București', addressCountry: 'RO' },
    sameAs: [co.instagram, co.facebook, co.tiktok].filter(Boolean),
  };
  const site = { '@context': 'https://schema.org', '@type': 'WebSite', name: 'Livada', url: config.publicUrl,
    potentialAction: { '@type': 'SearchAction', target: `${config.publicUrl}/cautare?q={q}`, 'query-input': 'required name=q' } };
  return page(ctx, { title: '', body, jsonld: [org, site], bodyClass: 'is-home' });
}
