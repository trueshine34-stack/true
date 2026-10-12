// Harta lockerelor și a punctelor de ridicare: căutare după localitate / stradă / cod poștal, filtre pe rețele,
// pini cu sigla curierului, grupuri colorate după rețea, listă sortată după distanță și fișa punctului.
// Folosită la checkout (alegerea lockerului) și pe pagina „Livrare” (explorare).
import { $, $$, toast, tr, num, langHeaders, LANG, LOCALE } from './app.js';

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const nf = (n) => num(n);
const leiFmt = (bani) => `${(bani / 100).toFixed(2).replace('.', ',')} lei`;
const dec = (x, f) => (LANG === 'en' ? x.toFixed(f) : x.toFixed(f).replace('.', ','));
const distLabel = (d) => (d < 1 ? `${Math.max(10, Math.round(d * 100) * 10)} m` : `${dec(d, d < 10 ? 1 : 0)} km`);

// programul punctului („L–V 08:00–20:00, S–D închis”) în limba paginii; în română rămâne neschimbat
const DAYS = { L: 0, Ma: 1, Mi: 2, J: 3, V: 4, S: 5, D: 6 };
let dayNames = null;
export function hoursText(h) {
  if (!h || LANG === 'ro') return h || '';
  if (h === 'Non-stop') return tr('Non-stop');
  // 1 ianuarie 2024 a fost luni → numele scurte ale zilelor în limba paginii
  dayNames ||= Array.from({ length: 7 }, (_, i) => new Intl.DateTimeFormat(LOCALE, { weekday: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2024, 0, 1 + i))));
  return h.replace(/(^|[\s,–])(Ma|Mi|L|J|V|S|D)(?=[\s–]|$)/g, (m, a, d) => a + dayNames[DAYS[d]])
    .replace(/închis/g, tr('închis')).replace(/non-stop/gi, tr('non-stop'));
}
const I = {
  pin: '<path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11Z"/><circle cx="12" cy="10" r="2.4"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.4"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  route: '<path d="M14 4.5h5.5V10M19.5 4.5 11 13M18 14v5.5H4.5V6H10"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  locker: '<rect x="4" y="3.5" width="16" height="17" rx="2"/><path d="M12 3.5v17M4 9.5h8M4 15h8M12 12h8"/>',
  store: '<path d="M4 9.5 5.5 4.5h13L20 9.5M4 9.5h16v2a2.7 2.7 0 0 1-5.3 0 2.7 2.7 0 0 1-5.4 0 2.7 2.7 0 0 1-5.3 0Z"/><path d="M5.5 13v6.5h13V13"/>',
  truck: '<path d="M3.5 6.5h10v9h-10zM13.5 9.5h4l3 3v3h-7"/><circle cx="7" cy="17" r="1.8"/><circle cx="17" cy="17" r="1.8"/>',
};
const svg = (name, size = 16) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${I[name]}</svg>`;

function distKm(a, b) {
  const R = 6371, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

const loadCss = (href) => new Promise((ok) => { const l = document.createElement('link'); l.rel = 'stylesheet'; l.href = href; l.onload = ok; l.onerror = ok; document.head.append(l); });
const loadJs = (src) => new Promise((ok, ko) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = ko; document.head.append(s); });

let dataPromise = null;
/** lista compactă de la server (o singură descărcare pe pagină; serverul o trimite comprimată, cu ETag) */
function loadLockers() {
  dataPromise ||= fetch('/api/lockers', { headers: langHeaders() }).then((r) => { if (!r.ok) throw new Error('lockers'); return r.json(); }).then((d) => {
    const ix = Object.fromEntries(d.fields.map((f, i) => [f, i]));
    return d.rows.map((r) => ({
      id: r[ix.id], provider: r[ix.provider], point: r[ix.kind] === 1, name: r[ix.name], address: r[ix.address], city: r[ix.city],
      county: r[ix.county], postcode: r[ix.postcode], lat: r[ix.lat], lng: r[ix.lng], hours: r[ix.hours], note: r[ix.note],
    }));
  });
  return dataPromise;
}

/**
 * @param {{ modal: HTMLElement, cfg: { map: {tiles:string, attribution:string}, networks: Record<string, any>, mode?: 'pick'|'browse' },
 *           onChoose?: (l:any) => void }} o
 */
export function createLockerPicker({ modal, cfg, onChoose }) {
  const nets = cfg.networks || {};
  const browse = cfg.mode === 'browse';
  const els = {
    map: $('[data-locker-map]', modal), list: $('[data-locker-list]', modal), card: $('[data-locker-card]', modal),
    search: $('[data-locker-search]', modal), suggest: $('[data-locker-suggest]', modal), filters: $('[data-locker-filters]', modal),
    count: $('[data-locker-count]', modal), viewBtns: $$('[data-locker-view]', modal),
  };
  let L, map, pinLayer, refLayer, lockers = [], active = null, picked = null, ref = null, subset = null, onlyLockers = false;
  let lastFocus = null, ready = null, frame = 0;
  const enabled = new Set(Object.keys(nets));

  // ─── încărcare ────────────────────────────────────────────────────────
  async function init() {
    await Promise.all([loadCss('/vendor/leaflet/leaflet.css'), window.L ? null : loadJs('/vendor/leaflet/leaflet.js')]);
    L = window.L;
    const all = await loadLockers();
    lockers = all.filter((l) => nets[l.provider]);
    for (const l of lockers) l.search = fold(`${l.name} ${l.address} ${l.city} ${l.county} ${l.postcode}`);
    for (const p of Object.keys(nets)) {
      const el = modal.querySelector(`[data-lf-count="${p}"]`);
      if (el) el.textContent = nf(lockers.filter((l) => l.provider === p).length);
    }
    map = L.map(els.map, { zoomControl: false, attributionControl: true, minZoom: 6, maxZoom: 19, maxBounds: [[42.6, 18.8], [49.2, 31.2]], worldCopyJump: false })
      .setView([45.94, 24.97], 7);
    L.control.zoom({ position: 'bottomright', zoomInTitle: tr('Mărește'), zoomOutTitle: tr('Micșorează') }).addTo(map);
    L.tileLayer(cfg.map.tiles, { attribution: cfg.map.attribution, subdomains: 'abcd', maxZoom: 19, detectRetina: true }).addTo(map);
    map.attributionControl.setPrefix(false);
    refLayer = L.layerGroup().addTo(map);
    pinLayer = L.layerGroup().addTo(map);
    map.on('moveend', schedule);
    map.on('click', () => { if (active && !matchMedia('(min-width: 900px)').matches) select(null); });
    if (picked) map.setView([picked.lat, picked.lng], 16);
    else map.fitBounds(L.latLngBounds(lockers.map((l) => [l.lat, l.lng])), { padding: [20, 20] });
  }

  const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(render); };
  const shown = (l) => enabled.has(l.provider) && (!onlyLockers || !l.point);

  // ─── marcaje ──────────────────────────────────────────────────────────
  function pinIcon(l) {
    const n = nets[l.provider];
    const cls = `lpin${l.point ? ' lpin--pt' : ''}${active?.id === l.id ? ' is-active' : ''}${picked?.id === l.id ? ' is-picked' : ''}`;
    return L.divIcon({ className: 'lpin-wrap', html: `<span class="${cls}" style="--c:${n.color}"><img src="${n.mark}" alt=""></span>`,
      iconSize: l.point ? [30, 30] : [36, 36], iconAnchor: l.point ? [15, 15] : [18, 43] });
  }

  function clusterIcon(items) {
    const n = items.length;
    const by = {};
    for (const l of items) by[l.provider] = (by[l.provider] || 0) + 1;
    let acc = 0;
    const stops = Object.entries(by).sort((a, b) => b[1] - a[1]).map(([p, c]) => {
      const from = acc; acc += (c / n) * 100;
      return `${nets[p].color} ${from.toFixed(2)}% ${acc.toFixed(2)}%`;
    });
    const size = n < 10 ? 38 : n < 50 ? 44 : n < 250 ? 52 : n < 1000 ? 60 : 68;
    return L.divIcon({ className: 'lcl-wrap', html: `<span class="lcl" style="--s:${size}px;background:conic-gradient(${stops.join(',')})"><b>${n >= 10000 ? `${Math.round(n / 1000)}k` : nf(n)}</b></span>`,
      iconSize: [size, size] });
  }

  function render() {
    if (!map) return;
    pinLayer.clearLayers();
    const bounds = map.getBounds();
    const pad = bounds.pad(0.2);
    const visible = lockers.filter((l) => shown(l) && pad.contains([l.lat, l.lng]));
    const zoom = map.getZoom();
    const pins = [];
    if (zoom >= 16 || visible.length <= 12) {
      for (const l of visible) pins.push({ items: [l] });
    } else {
      // grupare pe o grilă de 56 px, apoi unim grupurile care s-ar suprapune pe ecran
      const cells = new Map();
      for (const l of visible) {
        const p = map.latLngToLayerPoint([l.lat, l.lng]);
        const key = `${Math.floor(p.x / 56)}:${Math.floor(p.y / 56)}`;
        let c = cells.get(key);
        if (!c) { c = { items: [], x: 0, y: 0 }; cells.set(key, c); }
        c.items.push(l); c.x += p.x; c.y += p.y;
      }
      const groups = [...cells.values()].map((c) => ({ items: c.items, x: c.x / c.items.length, y: c.y / c.items.length }));
      const r = (g) => (g.items.length === 1 ? 18 : g.items.length < 10 ? 19 : g.items.length < 50 ? 22 : g.items.length < 250 ? 26 : 30);
      for (let changed = true; changed;) {
        changed = false;
        for (let i = 0; i < groups.length; i++) {
          for (let j = i + 1; j < groups.length; j++) {
            const a = groups[i], b = groups[j];
            if (Math.hypot(a.x - b.x, a.y - b.y) >= r(a) + r(b) + 4) continue;
            const na = a.items.length, nb = b.items.length;
            a.x = (a.x * na + b.x * nb) / (na + nb); a.y = (a.y * na + b.y * nb) / (na + nb);
            a.items.push(...b.items); groups.splice(j, 1); j = i; changed = true;
          }
        }
      }
      pins.push(...groups);
    }
    for (const g of pins) {
      if (g.items.length === 1) {
        const l = g.items[0];
        const m = L.marker([l.lat, l.lng], { icon: pinIcon(l), title: `${l.name}, ${l.address}`, keyboard: true, riseOnHover: true,
          zIndexOffset: active?.id === l.id ? 2000 : picked?.id === l.id ? 1500 : l.point ? 0 : 200 });
        m.on('click', (e) => { L.DomEvent.stopPropagation(e); select(l, { pan: false, scroll: true }); });
        pinLayer.addLayer(m);
        continue;
      }
      const ll = map.layerPointToLatLng([g.x, g.y]);
      const m = L.marker(ll, { icon: clusterIcon(g.items), title: tr('{n} puncte', { n: g.items.length }), keyboard: true });
      m.on('click', (e) => {
        L.DomEvent.stopPropagation(e);
        const b = L.latLngBounds(g.items.map((x) => [x.lat, x.lng]));
        // puncte practic în același loc (un mall, o benzinărie): le arătăm în listă în loc să mărim la infinit
        if (map.getZoom() >= 17 || b.getNorthEast().distanceTo(b.getSouthWest()) < 40) { subset = g.items; renderList(); setView('list'); }
        else map.fitBounds(b, { padding: [60, 60], maxZoom: 17 });
      });
      pinLayer.addLayer(m);
    }
    renderList(visible.filter((l) => bounds.contains([l.lat, l.lng])));
  }

  // ─── listă ────────────────────────────────────────────────────────────
  function refPoint() {
    if (ref) return ref;
    const c = map.getCenter();
    return { lat: c.lat, lng: c.lng, label: tr('centrul hărții') };
  }

  function item(l, d) {
    const n = nets[l.provider];
    const on = active?.id === l.id;
    return `<li><button type="button" class="litem${on ? ' is-active' : ''}${picked?.id === l.id ? ' is-picked' : ''}" data-lid="${esc(l.id)}" aria-pressed="${on}">
      <span class="carrier" style="--cs:36px"><img src="${n.mark}" alt=""></span>
      <span class="litem__main"><b>${esc(l.name)}</b><span>${esc(l.address)}, ${esc(l.city)}</span>
        <small>${l.point ? `${svg('store', 13)} ${tr('Punct de ridicare')} · ${esc(hoursText(l.hours) || tr('program afișat la punct'))}` : `${svg('locker', 13)} ${tr('Locker')} · ${esc(hoursText(l.hours))}`}</small></span>
      <span class="litem__side">${d !== undefined ? `<span class="litem__dist">${distLabel(d)}</span>` : ''}<span class="litem__price">${n.price ? leiFmt(n.price) : tr('Gratuit')}</span></span>
    </button></li>`;
  }

  const CITIES = [['București', 44.4268, 26.1025], ['Cluj-Napoca', 46.7712, 23.6236], ['Timișoara', 45.7489, 21.2087], ['Iași', 47.1585, 27.6014],
    ['Constanța', 44.1598, 28.6348], ['Brașov', 45.6427, 25.5887], ['Craiova', 44.3302, 23.7949], ['Galați', 45.4353, 28.008],
    ['Oradea', 47.0465, 21.9189], ['Ploiești', 44.9365, 26.0129], ['Sibiu', 45.7983, 24.1256], ['Arad', 46.1866, 21.3123]];

  /** fără o localitate aleasă și cu toată țara pe ecran, lista ar fi aleatorie: arătăm de unde să începi */
  function renderStart(inView) {
    const by = {};
    for (const l of lockers) if (shown(l)) by[l.provider] = (by[l.provider] || 0) + 1;
    els.list.innerHTML = `<div class="lstart">
      <p class="lstart__title"><b>${tr('Unde vrei să ridici coletul?')}</b><span>${tr('Caută localitatea (merge și cu satele), strada sau codul poștal — ori apasă „Lângă mine”.')}</span></p>
      <p class="lstart__label">${tr('Orașe mari')}</p>
      <div class="lstart__cities">${CITIES.map(([n, lat, lng]) => `<button type="button" class="chip" data-goto="${lat},${lng}" data-goto-label="${esc(n)}">${esc(n)}</button>`).join('')}</div>
      <p class="lstart__label">${tr('Rețele pe hartă')}</p>
      <ul class="lstart__nets">${Object.entries(nets).filter(([k]) => by[k]).map(([k, n]) => `<li><span class="carrier" style="--cs:32px"><img src="${n.mark}" alt=""></span>
        <span><b>${esc(n.name)}</b><small>${tr(by[k] === 1 ? '{n} punct' : '{n} puncte', { n: nf(by[k]) })}${n.brand ? ` · ${esc(n.brand)}` : ''}</small></span>
        ${browse ? '' : `<em>${n.price ? leiFmt(n.price) : tr('Gratuit')}</em>`}</li>`).join('')}</ul>
    </div>`;
    if (els.count) els.count.textContent = nf(inView.length);
  }

  function renderList(inView) {
    if (!els.list) return;
    if (!subset && !ref && map.getZoom() < 10) { renderStart(inView || lockers.filter(shown)); return; }
    const r = refPoint();
    let rows, head;
    if (subset) {
      rows = subset.map((l) => ({ l, d: distKm(r, l) })).sort((a, b) => a.d - b.d);
      head = `<p class="llist__head"><b>${tr('{n} puncte în același loc', { n: rows.length })}</b><button type="button" class="link-btn" data-subset-clear>${tr('Toată zona')}</button></p>`;
    } else {
      inView ||= lockers.filter((l) => shown(l) && map.getBounds().contains([l.lat, l.lng]));
      rows = inView.map((l) => ({ l, d: distKm(r, l) })).sort((a, b) => a.d - b.d).slice(0, 60);
      if (!rows.length) {
        const near = lockers.filter(shown).map((l) => ({ l, d: distKm(r, l) })).sort((a, b) => a.d - b.d).slice(0, 8);
        rows = near;
        head = `<p class="llist__head">${tr('Niciun punct în zona hărții.')} <b>${tr('Cele mai apropiate de {label}:', { label: esc(r.label) })}</b></p>`;
      } else {
        head = `<p class="llist__head"><b>${tr(inView.length === 1 ? '{n} punct în zona hărții' : '{n} puncte în zona hărții', { n: nf(inView.length) })}</b> · ${tr('după distanța față de {label}', { label: esc(r.label) })}</p>`;
      }
    }
    if (els.count) els.count.textContent = subset ? subset.length : nf(inView?.length ?? rows.length);
    els.list.innerHTML = rows.length ? `${head}<ul class="llist">${rows.map(({ l, d }) => item(l, d)).join('')}</ul>`
      : `<p class="llist__empty">${tr('Nicio rețea selectată. Activează cel puțin o rețea din filtrele de mai sus.')}</p>`;
  }

  // ─── fișa punctului ───────────────────────────────────────────────────
  function renderCard() {
    if (!els.card) return;
    if (!active) { els.card.hidden = true; modal.classList.remove('has-card'); return; }
    const l = active, n = nets[l.provider];
    const d = ref ? distKm(ref, l) : null;
    els.card.innerHTML = `
      <div class="lcard__head">
        <span class="carrier" style="--cs:44px"><img src="${n.mark}" alt=""></span>
        <span class="lcard__net"><b>${esc(n.name)}</b><small>${l.point ? tr('Punct de ridicare') : tr('Locker automat')}${n.brand ? ` · ${esc(n.brand)}` : ''}${d !== null ? ` · ${tr('{dist} de {label}', { dist: distLabel(d), label: esc(ref.label) })}` : ''}</small></span>
        <button type="button" class="icon-btn icon-btn--sm" data-card-close aria-label="${esc(tr('Închide fișa'))}">${svg('close', 18)}</button>
      </div>
      <h3 class="lcard__name">${esc(l.name)}</h3>
      <p class="lcard__addr">${esc(l.address)}<br>${esc(l.city)}, ${l.county === 'București' ? 'București' : tr('jud. {county}', { county: esc(l.county) })}${l.postcode ? ` · ${esc(l.postcode)}` : ''}</p>
      ${l.note ? `<p class="lcard__note">${svg('info', 15)}<span>${esc(l.note)}</span></p>` : ''}
      <ul class="lcard__facts">
        <li>${svg('clock', 15)}<span>${l.hours === 'Non-stop' ? tr('<b class="ok">Non-stop</b>, 24/7') : esc(hoursText(l.hours) || tr('Program afișat la punct'))}</span></li>
        ${browse ? '' : `<li>${svg('truck', 15)}<span>${esc(n.eta)} · <b>${n.price ? leiFmt(n.price) : tr('livrare gratuită')}</b></span></li>`}
        ${browse ? '' : `<li>${svg(n.cod ? 'check' : 'info', 15)}<span>${n.cod ? (l.point ? tr('Poți plăti ramburs la punct') : tr('Poți plăti ramburs, cu cardul la locker')) : tr('Plata ramburs nu e disponibilă în această rețea')}</span></li>`}
      </ul>
      <div class="lcard__actions">
        ${browse ? '' : `<button type="button" class="btn btn--dark" data-pick="${esc(l.id)}">${picked?.id === l.id ? `${svg('check', 18)} ${tr('Ales')}` : tr('Alege acest punct')}</button>`}
        <a class="btn btn--ghost" href="https://www.google.com/maps/dir/?api=1&destination=${l.lat},${l.lng}" target="_blank" rel="noopener">${svg('route', 16)} ${tr('Traseu')}</a>
      </div>`;
    els.card.hidden = false;
    modal.classList.add('has-card');
  }

  function select(l, { pan = true, scroll = false } = {}) {
    active = l;
    renderCard();
    if (l && pan) {
      const z = Math.max(map.getZoom(), 16);
      map.setView([l.lat, l.lng], z);
    } else schedule();
    if (l && scroll) setTimeout(() => modal.querySelector(`[data-lid="${CSS.escape(l.id)}"]`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 40);
    if (l && !matchMedia('(min-width: 900px)').matches) setView('map');
  }

  function setRef(point, { marker = true } = {}) {
    ref = point;
    refLayer.clearLayers();
    if (point && marker) {
      const cls = point.kind === 'me' ? 'lme' : 'lplace';
      L.marker([point.lat, point.lng], { icon: L.divIcon({ className: cls, iconSize: [18, 18] }), interactive: false, zIndexOffset: 3000, keyboard: false }).addTo(refLayer);
    }
  }

  // ─── căutare ──────────────────────────────────────────────────────────
  let sugSeq = 0, sugItems = [], sugIndex = -1;
  const placeCache = new Map();
  async function suggest(q) {
    const seq = ++sugSeq;
    const fq = fold(q.trim());
    if (fq.length < 2) { hideSuggest(); return; }
    const words = fq.split(/\s+/).filter(Boolean);
    const lockerHits = lockers.filter((l) => shown(l) && words.every((w) => l.search.includes(w)))
      .sort((a, b) => Number(!fold(a.name).includes(words[0])) - Number(!fold(b.name).includes(words[0])))
      .slice(0, /^\d{6}$/.test(fq) ? 8 : 4);
    let places = placeCache.get(fq);
    if (!places) {
      try {
        places = (await (await fetch(`/api/places?q=${encodeURIComponent(q.trim())}`, { headers: langHeaders() })).json()).places || [];
        placeCache.set(fq, places);
      } catch { places = []; }
    }
    if (seq !== sugSeq) return;
    sugItems = [...places.map((p) => ({ type: 'place', p })), ...lockerHits.map((l) => ({ type: 'locker', l }))];
    sugIndex = sugItems.length ? 0 : -1;
    if (!sugItems.length) {
      els.suggest.innerHTML = `<li class="lsug__empty">${tr('Nu am găsit localitatea sau punctul. Încearcă numele orașului sau codul poștal.')}</li>`;
      els.suggest.hidden = false;
      els.search.setAttribute('aria-expanded', 'true');
      return;
    }
    let lastType = '';
    els.suggest.innerHTML = sugItems.map((s, i) => {
      const head = s.type !== lastType ? `<li class="lsug__group" role="presentation">${s.type === 'place' ? tr('Localități') : tr('Lockere și puncte')}</li>` : '';
      lastType = s.type;
      if (s.type === 'place') {
        const p = s.p;
        return `${head}<li role="option" id="lsug-${i}" class="lsug${i === sugIndex ? ' is-on' : ''}" data-sug="${i}">
          <span class="lsug__ico">${svg('pin', 18)}</span><span class="lsug__main"><b>${esc(p.name)}</b><small>${p.county === 'București' ? tr('Municipiul București') : tr('jud. {county}', { county: esc(p.county) })}</small></span>
          <span class="lsug__meta">${p.near ? tr(p.near === 1 ? '{n} punct în zonă' : '{n} puncte în zonă', { n: nf(p.near) }) : tr('cele mai apropiate')}</span></li>`;
      }
      const l = s.l, n = nets[l.provider];
      return `${head}<li role="option" id="lsug-${i}" class="lsug${i === sugIndex ? ' is-on' : ''}" data-sug="${i}">
        <span class="carrier" style="--cs:30px"><img src="${n.mark}" alt=""></span><span class="lsug__main"><b>${esc(l.name)}</b><small>${esc(l.address)}, ${esc(l.city)}</small></span></li>`;
    }).join('');
    els.suggest.hidden = false;
    els.search.setAttribute('aria-expanded', 'true');
    els.search.setAttribute('aria-activedescendant', sugIndex >= 0 ? `lsug-${sugIndex}` : '');
  }
  function hideSuggest() {
    els.suggest.hidden = true; sugItems = []; sugIndex = -1;
    els.search.setAttribute('aria-expanded', 'false'); els.search.removeAttribute('aria-activedescendant');
  }
  function moveSuggest(dir) {
    if (!sugItems.length) return;
    sugIndex = (sugIndex + dir + sugItems.length) % sugItems.length;
    $$('.lsug', els.suggest).forEach((li) => li.classList.toggle('is-on', Number(li.dataset.sug) === sugIndex));
    els.search.setAttribute('aria-activedescendant', `lsug-${sugIndex}`);
    $(`#lsug-${sugIndex}`, els.suggest)?.scrollIntoView({ block: 'nearest' });
  }
  function pickSuggest(i) {
    const s = sugItems[i];
    if (!s) return;
    hideSuggest();
    subset = null;
    if (s.type === 'locker') {
      els.search.value = s.l.name;
      select(s.l);
      return;
    }
    const p = s.p;
    els.search.value = p.county === 'București' || fold(p.name) === fold(p.county) ? p.name : `${p.name}, ${p.county}`;
    goTo({ lat: p.lat, lng: p.lng, label: p.name, kind: 'place' }, p.near);
  }
  /** centrează harta pe un loc; dacă nu sunt puncte în jur, cuprinde și cele mai apropiate */
  function goTo(point, nearCount) {
    setRef(point);
    select(null, { pan: false });
    const nearest = lockers.filter(shown).map((l) => ({ l, d: distKm(point, l) })).sort((a, b) => a.d - b.d).slice(0, 6);
    if (!nearCount && nearest.length) {
      map.fitBounds(L.latLngBounds([[point.lat, point.lng], ...nearest.map(({ l }) => [l.lat, l.lng])]), { padding: [50, 50], maxZoom: 15 });
    } else map.setView([point.lat, point.lng], nearCount > 40 ? 13 : 14);
    if (!matchMedia('(min-width: 900px)').matches) setView('list');
  }

  let sTimer;
  els.search?.addEventListener('input', () => { clearTimeout(sTimer); sTimer = setTimeout(() => suggest(els.search.value), 140); });
  els.search?.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (els.suggest.hidden) suggest(els.search.value); else moveSuggest(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveSuggest(-1); }
    else if (e.key === 'Enter') { e.preventDefault(); if (sugIndex >= 0) pickSuggest(sugIndex); else suggest(els.search.value); }
    else if (e.key === 'Escape' && !els.suggest.hidden) { e.stopPropagation(); hideSuggest(); }
  });
  els.search?.addEventListener('focus', () => { if (els.search.value.trim().length >= 2) suggest(els.search.value); });
  els.suggest?.addEventListener('mousedown', (e) => { const li = e.target.closest('[data-sug]'); if (li) { e.preventDefault(); pickSuggest(Number(li.dataset.sug)); } });
  modal.addEventListener('click', (e) => { if (!e.target.closest('.lsearch')) hideSuggest(); });

  // ─── geolocalizare ────────────────────────────────────────────────────
  function locate() {
    if (!navigator.geolocation) { toast(tr('Browserul nu permite localizarea. Caută după localitate.'), { type: 'error' }); return; }
    const btn = $('[data-locker-locate]', modal);
    btn?.classList.add('is-loading');
    navigator.geolocation.getCurrentPosition((pos) => {
      btn?.classList.remove('is-loading');
      const me = { lat: pos.coords.latitude, lng: pos.coords.longitude, label: tr('tine'), kind: 'me' };
      if (!(me.lat > 43 && me.lat < 48.5 && me.lng > 20 && me.lng < 30)) { toast(tr('Ești în afara României — caută localitatea de livrare.'), { type: 'error' }); return; }
      els.search.value = '';
      subset = null;
      goTo(me, lockers.some((l) => shown(l) && distKm(me, l) < 3) ? 1 : 0);
    }, () => { btn?.classList.remove('is-loading'); toast(tr('Nu am putut afla locația. Caută după localitate sau stradă.'), { type: 'error' }); },
    { enableHighAccuracy: true, timeout: 9000, maximumAge: 120000 });
  }

  // ─── evenimente ───────────────────────────────────────────────────────
  modal.addEventListener('click', (e) => {
    const t = e.target;
    const pick = t.closest('[data-pick]');
    if (pick) { const l = lockers.find((x) => x.id === pick.dataset.pick); if (l) { picked = l; onChoose?.(l); } return; }
    const it = t.closest('[data-lid]');
    if (it) { const l = lockers.find((x) => x.id === it.dataset.lid); if (l) select(l); return; }
    if (t.closest('[data-card-close]')) { select(null, { pan: false }); return; }
    if (t.closest('[data-subset-clear]')) { subset = null; renderList(); return; }
    const g = t.closest('[data-goto]');
    if (g) { const [lat, lng] = g.dataset.goto.split(',').map(Number); els.search.value = g.dataset.gotoLabel; goTo({ lat, lng, label: g.dataset.gotoLabel, kind: 'place' }, 99); return; }
    if (t.closest('[data-locker-locate]')) { locate(); return; }
    const v = t.closest('[data-locker-view]');
    if (v) { setView(v.dataset.lockerView); return; }
    if (t.closest('[data-close-lockers]') || t === modal) close();
  });
  modal.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { if (active && !matchMedia('(min-width: 900px)').matches) select(null, { pan: false }); else close(); }
    if (e.key === 'Tab') trapFocus(e);
  });
  els.filters?.addEventListener('change', (e) => {
    const t = e.target;
    if (t.matches('[data-only-lockers]')) onlyLockers = t.checked;
    else if (t.checked) enabled.add(t.value); else enabled.delete(t.value);
    subset = null;
    if (active && !shown(active)) select(null, { pan: false });
    schedule();
  });

  function setView(v) {
    modal.dataset.view = v;
    els.viewBtns.forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.lockerView === v)));
    if (v === 'map') setTimeout(() => map?.invalidateSize(), 30);
  }

  function trapFocus(e) {
    const f = $$('button:not([disabled]), a[href], input, [tabindex]:not([tabindex="-1"])', modal).filter((x) => x.offsetParent !== null);
    if (!f.length) return;
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }

  // ─── API ──────────────────────────────────────────────────────────────
  async function open({ focusSearch = true, locateMe = false } = {}) {
    lastFocus = document.activeElement;
    modal.hidden = false;
    document.documentElement.style.overflow = 'hidden';
    setView('map');
    try {
      ready ||= init();
      await ready;
      requestAnimationFrame(() => {
        map.invalidateSize();
        if (picked) { active = picked; renderCard(); map.setView([picked.lat, picked.lng], Math.max(map.getZoom(), 16)); }
        schedule();
      });
      if (locateMe) locate();
      else if (focusSearch && matchMedia('(min-width: 900px)').matches) els.search?.focus();
    } catch {
      ready = null;
      els.list.innerHTML = `<p class="llist__empty">${tr('Harta nu s-a putut încărca. Verifică conexiunea și încearcă din nou.')}</p>`;
    }
  }
  function close() {
    modal.hidden = true;
    document.documentElement.style.overflow = '';
    hideSuggest();
    lastFocus?.focus?.();
  }
  return { open, close, setPicked: (l) => { picked = l; }, lockers: () => lockers };
}
