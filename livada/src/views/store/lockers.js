import { html } from '../../lib/html.js';
import { config } from '../../config.js';
import { icon } from '../icons.js';
import { estimate, carrier, lockerStats } from '../../services/shipping.js';

const nf = (n) => Number(n || 0).toLocaleString('ro-RO');
export const lockerPrice = (m, subtotal) => (m.free_over !== null && m.free_over !== undefined && subtotal >= m.free_over ? 0 : m.price);

/** rețelele de lockere active care au puncte în bază, cu prețul și termenul pentru coșul curent */
export function lockerNetworks(methods, subtotal = 0) {
  const stats = Object.fromEntries(lockerStats().map((s) => [s.provider, s]));
  return methods.filter((m) => m.type === 'locker' && stats[m.carrier]?.n).map((m) => {
    const c = carrier(m.carrier);
    const s = stats[m.carrier];
    return {
      key: m.carrier, code: m.code, name: m.name, brand: c.network || '', color: c.color, mark: c.mark, logo: c.logo,
      price: lockerPrice(m, subtotal), free_over: m.free_over, eta: estimate(m), cod: !!m.cod_allowed,
      lockers: Number(s.lockers || 0), points: Number(s.points || 0), cities: Number(s.cities || 0), counties: Number(s.counties || 0),
    };
  });
}

/** configurația pentru public/js/lockers.js */
export const lockerConfig = (nets, mode = 'pick') => ({
  mode,
  map: { tiles: config.map.tiles, attribution: config.map.attribution },
  networks: Object.fromEntries(nets.map((n) => [n.key, { name: n.name, brand: n.brand, color: n.color, mark: n.mark, code: n.code, price: n.price, eta: n.eta, cod: n.cod }])),
});

/** fereastra cu harta: căutare, filtre pe rețele, listă + hartă, fișa punctului */
export function lockerModal(nets, { title = 'Alege lockerul', mode = 'pick' } = {}) {
  const total = nets.reduce((a, n) => a + n.lockers + n.points, 0);
  return html`<div class="locker-modal" hidden data-locker-modal data-mode=${mode} role="dialog" aria-modal="true" aria-labelledby="locker-title">
  <div class="locker-modal__panel">
    <header class="lm-head">
      <div class="lm-head__title"><h2 id="locker-title">${title}</h2><p>${nf(total)} lockere și puncte de ridicare în toată țara</p></div>
      <div class="lsearch">
        ${icon('search', { size: 18 })}
        <input type="search" data-locker-search placeholder="Localitate, stradă sau cod poștal" aria-label="Caută localitatea sau punctul"
          role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="lsug-list" autocomplete="off" spellcheck="false" enterkeyhint="search">
        <ul class="lsug-list" id="lsug-list" role="listbox" data-locker-suggest hidden></ul>
      </div>
      <button class="btn btn--ghost btn--sm lm-locate" type="button" data-locker-locate aria-label="Lângă mine">${icon('locate', { size: 16 })}<span>Lângă mine</span></button>
      <button class="icon-btn lm-close" type="button" aria-label="Închide harta" data-close-lockers>${icon('close', { size: 22 })}</button>
    </header>
    <div class="lm-filters" data-locker-filters role="group" aria-label="Rețele">
      ${nets.map((n) => html`<label class="lf" style=${`--c:${n.color}`}><input type="checkbox" value=${n.key} checked>
        <span><span class="carrier" style="--cs:22px"><img src=${n.mark} alt=""></span>${n.name}<small data-lf-count=${n.key}>${nf(n.lockers + n.points)}</small></span></label>`)}
      <span class="lm-filters__sep" aria-hidden="true"></span>
      <label class="lf lf--kind"><input type="checkbox" data-only-lockers><span>${icon('locker', { size: 16 })}Doar lockere non-stop</span></label>
    </div>
    <div class="lm-body" data-view="map">
      <aside class="lm-list" data-locker-list aria-live="polite"><p class="llist__empty">Se încarcă punctele…</p></aside>
      <div class="lm-map">
        <div class="lm-map__canvas" data-locker-map data-tone=${config.map.tone}></div>
        <div class="lcard" data-locker-card hidden></div>
      </div>
    </div>
    <div class="lm-switch" role="group" aria-label="Mod de afișare">
      <button type="button" data-locker-view="map" aria-pressed="true">${icon('pin', { size: 16 })} Hartă</button>
      <button type="button" data-locker-view="list" aria-pressed="false">${icon('menu', { size: 16 })} Listă <b data-locker-count></b></button>
    </div>
  </div>
</div>`;
}

/** rândurile cu rețelele (logo, câte lockere/puncte, preț) */
export function networkRows(nets, { showPrice = true } = {}) {
  return html`<ul class="lnets">${nets.map((n) => html`<li class="lnet">
    <span class="carrier" style="--cs:40px"><img src=${n.mark} alt=""></span>
    <span class="lnet__main"><b>${n.name}</b><small>${n.lockers ? `${nf(n.lockers)} lockere` : ''}${n.lockers && n.points ? ' · ' : ''}${n.points ? `${nf(n.points)} puncte` : ''}</small></span>
    ${showPrice ? html`<span class="lnet__price"><b>${n.price ? `${(n.price / 100).toFixed(2).replace('.', ',')} lei` : 'Gratuit'}</b><small>${n.eta}</small></span>` : ''}
  </li>`)}</ul>`;
}

