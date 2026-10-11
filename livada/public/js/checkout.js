import { $, $$, api, toast, showErrors } from './app.js';

const form = $('[data-checkout]');
const cfg = JSON.parse($('#checkout-data')?.textContent || '{}');
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

if (form) {
  let shipType = 'courier';
  let lastCourier = form.querySelector('[data-ship-pane="courier"] input[name="delivery_method"]:checked')?.value;
  let picked = null; // lockerul ales

  const methodInput = () => form.querySelector('input[name="delivery_method"]:checked');
  const payInput = () => form.querySelector('input[name="payment_method"]:checked');

  // ─── tab-uri livrare ────────────────────────────────────────────────
  function setShip(type) {
    shipType = type;
    $$('[data-ship-tab]', form).forEach((b) => { const on = b.dataset.shipTab === type; b.classList.toggle('is-active', on); b.setAttribute('aria-selected', String(on)); });
    $$('[data-ship-pane]', form).forEach((p) => { p.hidden = p.dataset.shipPane !== type; });
    $$('input[name="delivery_method"]', form).forEach((i) => { i.checked = false; });
    if (type === 'courier') {
      const el = form.querySelector(`[data-ship-pane="courier"] input[value="${lastCourier}"]`) || form.querySelector('[data-ship-pane="courier"] input[name="delivery_method"]:not(:disabled)');
      if (el) el.checked = true;
    } else if (type === 'locker' && picked) {
      const el = form.querySelector(`[data-locker-method="${picked.provider}"]`);
      if (el) el.checked = true;
    } else if (type === 'pickup') {
      const el = form.querySelector('[data-ship-pane="pickup"] input[name="delivery_method"]');
      if (el) el.checked = true;
    }
    // facturarea „aceeași cu livrarea” are sens doar pentru curier
    const sameWrap = $('[data-billing-same-wrap]', form);
    sameWrap.hidden = type !== 'courier';
    $('[data-billing]', form).hidden = type === 'courier' && form.elements.billing_same.checked;
    updatePayments();
    quote();
  }
  $$('[data-ship-tab]', form).forEach((b) => b.addEventListener('click', () => setShip(b.dataset.shipTab)));

  form.addEventListener('change', (e) => {
    const t = e.target;
    if (t.name === 'delivery_method') {
      if (shipType === 'courier') lastCourier = t.value;
      updatePayments();
      quote();
    }
    if (t.name === 'payment_method' || t.name === 'installments') { $('[data-installments]', form).hidden = payInput()?.value !== 'installments'; quote(); updatePlaceLabel(); }
    if (t.name === 'ship_county') checkExpress();
    if (t.name === 'billing_same') $('[data-billing]', form).hidden = t.checked;
    if (t.matches('[data-customer-type]')) $('[data-company]', form).hidden = t.value !== 'pj';
  });

  // livrarea în aceeași zi doar în București / Ilfov
  function checkExpress() {
    const county = form.elements.ship_county.value;
    const express = form.querySelector('[data-type="express"] input');
    if (!express) return;
    const ok = !county || cfg.expressCounties.includes(county);
    express.disabled = !ok;
    $('[data-express-note]', form).hidden = ok || !county;
    if (!ok && express.checked) {
      const first = form.querySelector('[data-ship-pane="courier"] [data-type="courier"] input');
      first.checked = true; lastCourier = first.value; quote();
    }
  }
  checkExpress();

  // ramburs permis?
  let codAllowedByTotal = true;
  function updatePayments() {
    const m = methodInput();
    const codOk = (!m || m.dataset.cod === '1') && codAllowedByTotal;
    const cod = form.querySelector('input[name="payment_method"][value="cod"]');
    cod.disabled = !codOk;
    $('[data-pay-cod] small', form).dataset.base ||= $('[data-pay-cod] small', form).textContent;
    $('[data-pay-cod] small', form).textContent = codOk ? $('[data-pay-cod] small', form).dataset.base : 'Indisponibil pentru metoda de livrare aleasă — plătești cu cardul la comandă.';
    if (!codOk && cod.checked) { form.querySelector('input[name="payment_method"][value="card"]').checked = true; }
    updatePlaceLabel();
  }

  function updatePlaceLabel() {
    const p = payInput()?.value;
    $('[data-place-label]', form).textContent = p === 'card' || p === 'installments' ? 'Continuă spre plată' : 'Plasează comanda';
  }

  // ─── recalculare totaluri ───────────────────────────────────────────
  let qSeq = 0;
  async function quote() {
    const seq = ++qSeq;
    try {
      const r = await api('/api/checkout/quote', { method: 'POST', body: { delivery_method: methodInput()?.value || '', payment_method: payInput()?.value || '' } });
      if (seq !== qSeq) return;
      $('[data-summary-rows]', form).innerHTML = r.rows;
      $('[data-place-total]', form).textContent = r.totalLabel;
      $('[data-summary-eta]', form).textContent = r.eta;
      for (const [n, v] of Object.entries(r.installments)) { const el = form.querySelector(`[data-rate="${n}"]`); if (el) el.textContent = v; }
      const inst = form.querySelector('input[name="payment_method"][value="installments"]');
      inst.disabled = !r.installmentsAllowed;
      if (codAllowedByTotal !== r.codAllowed) { codAllowedByTotal = r.codAllowed; updatePayments(); }
    } catch { /* păstrăm valorile vechi */ }
  }

  // adrese salvate
  $$('[data-fill-address]', form).forEach((b) => b.addEventListener('click', () => {
    const a = JSON.parse(b.dataset.fillAddress);
    form.elements.ship_county.value = a.county;
    form.elements.ship_county.closest('.field')?.classList.add('has-value');
    form.elements.ship_city.value = a.city;
    form.elements.ship_street.value = a.street;
    form.elements.ship_postcode.value = a.postcode || '';
    if (a.phone && !form.elements.phone.value) form.elements.phone.value = a.phone;
    checkExpress();
  }));

  // ─── trimitere ──────────────────────────────────────────────────────
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (shipType === 'locker' && !picked) {
      showErrors(form, { locker_id: 'Alege un locker de pe hartă.' });
      return;
    }
    const btn = $('[data-place-order]', form);
    btn.classList.add('is-loading');
    try {
      const fd = new FormData(form);
      if (shipType === 'courier' && form.elements.billing_same.checked) fd.set('billing_same', 'on');
      else fd.delete('billing_same');
      const res = await fetch(form.action, { method: 'POST', body: new URLSearchParams(fd), headers: { Accept: 'application/json', 'X-CSRF-Token': fd.get('_csrf') } });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        const errs = data.errors || {};
        // erorile câmpurilor de adresă de facturare sunt afișate doar dacă secțiunea e vizibilă
        if (Object.keys(errs).some((k) => k.startsWith('bill_'))) { $('[data-billing]', form).hidden = false; }
        showErrors(form, errs, data.error);
        if (!Object.keys(errs).length) toast(data.error || 'Nu am putut plasa comanda.', { type: 'error' });
        return;
      }
      location.href = data.redirect;
    } catch {
      toast('Conexiunea a eșuat. Verifică internetul și încearcă din nou.', { type: 'error' });
    } finally { btn.classList.remove('is-loading'); }
  });

  // ─── harta lockerelor ───────────────────────────────────────────────
  const modal = $('[data-locker-modal]');
  let map, layer, lockers = [], meMarker = null, me = null, active = null, filterText = '';
  const enabled = new Set(Object.keys(cfg.lockerPrices || {}));

  const loadCss = (href) => new Promise((ok) => { const l = document.createElement('link'); l.rel = 'stylesheet'; l.href = href; l.onload = ok; document.head.append(l); });
  const loadJs = (src) => new Promise((ok, ko) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = ko; document.head.append(s); });

  async function ensureMap() {
    if (map) return;
    await Promise.all([loadCss('/vendor/leaflet/leaflet.css'), window.L ? null : loadJs('/vendor/leaflet/leaflet.js')]);
    const data = await (await fetch('/api/lockers')).json();
    lockers = data.rows.map((r) => Object.fromEntries(data.fields.map((f, i) => [f, r[i]])))
      .filter((l) => cfg.lockerPrices[l.provider]);
    lockers.forEach((l) => { l.search = fold(`${l.name} ${l.address} ${l.city} ${l.county} ${l.postcode}`); });
    for (const p of Object.keys(cfg.lockerPrices)) {
      const el = modal.querySelector(`[data-lf-count="${p}"]`);
      if (el) el.textContent = lockers.filter((l) => l.provider === p).length;
    }
    const L = window.L;
    map = L.map($('[data-locker-map]', modal), { zoomControl: true, attributionControl: true, minZoom: 6, maxBounds: [[43.2, 19.5], [48.6, 30.2]] })
      .setView([45.9, 24.9], 7);
    L.tileLayer(cfg.map.tiles, { attribution: cfg.map.attribution, subdomains: 'abcd', maxZoom: 19 }).addTo(map);
    layer = L.layerGroup().addTo(map);
    map.on('moveend', render);
    if (picked) map.setView([picked.lat, picked.lng], 15);
    else if (lockers.length) map.fitBounds(L.latLngBounds(lockers.map((l) => [l.lat, l.lng])), { padding: [20, 20] });
  }

  const visible = () => lockers.filter((l) => enabled.has(l.provider) && (!filterText || filterText.every((t) => l.search.includes(t))));

  function distKm(a, b) {
    const R = 6371, rad = Math.PI / 180;
    const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }

  function pinIcon(l) {
    const c = cfg.carriers[l.provider] || {};
    // Leaflet poziționează elementul exterior cu transform; forma „picăturii” e pe elementul interior
    return window.L.divIcon({ className: 'lpin-wrap', html: `<span class="lpin ${active?.id === l.id ? 'is-active' : ''}" style="--cb:${c.color || '#333'}"></span>`,
      iconSize: [26, 26], iconAnchor: [13, 30] });
  }

  const clusterSize = (n) => (n < 10 ? 36 : n < 50 ? 44 : n < 200 ? 52 : 60);

  function render() {
    if (!map) return;
    const L = window.L;
    layer.clearLayers();
    const list = visible();
    const b = map.getBounds().pad(0.15);
    const inView = list.filter((l) => b.contains([l.lat, l.lng]));
    const zoom = map.getZoom();
    if (zoom >= 14 || inView.length <= 120) {
      for (const l of inView) {
        const m = L.marker([l.lat, l.lng], { icon: pinIcon(l), title: l.name, keyboard: true, riseOnHover: true, zIndexOffset: active?.id === l.id ? 1000 : 0 });
        m.on('click', () => focusLocker(l, { pan: false }));
        layer.addLayer(m);
      }
    } else {
      // grupare pe o grilă de 64 px în spațiul ecranului
      const cells = new Map();
      for (const l of inView) {
        const p = map.latLngToLayerPoint([l.lat, l.lng]);
        const key = `${Math.floor(p.x / 64)}:${Math.floor(p.y / 64)}`;
        let c = cells.get(key);
        if (!c) { c = { items: [], lat: 0, lng: 0 }; cells.set(key, c); }
        c.items.push(l); c.lat += l.lat; c.lng += l.lng;
      }
      // grupurile vecine din grilă se pot suprapune pe ecran → le unim până nu se mai ating
      const radius = (n) => (n === 1 ? 16 : clusterSize(n) / 2);
      const centerOf = (g) => map.latLngToLayerPoint([g.lat / g.items.length, g.lng / g.items.length]);
      const groups = [...cells.values()].map((c) => ({ ...c, p: centerOf(c) }));
      for (let changed = true; changed;) {
        changed = false;
        for (let i = 0; i < groups.length; i++) {
          for (let j = i + 1; j < groups.length; j++) {
            const a = groups[i]; const g = groups[j];
            if (a.p.distanceTo(g.p) >= radius(a.items.length) + radius(g.items.length) + 6) continue;
            a.items.push(...g.items); a.lat += g.lat; a.lng += g.lng; a.p = centerOf(a);
            groups.splice(j, 1); j = i; changed = true;
          }
        }
      }
      for (const c of groups) {
        const n = c.items.length;
        if (n === 1) {
          const l = c.items[0];
          const m = L.marker([l.lat, l.lng], { icon: pinIcon(l), title: l.name });
          m.on('click', () => focusLocker(l, { pan: false }));
          layer.addLayer(m);
          continue;
        }
        const size = clusterSize(n);
        const m = L.marker([c.lat / n, c.lng / n], { icon: L.divIcon({ className: 'lcluster', html: `<span>${n}</span>`, iconSize: [size, size] }), title: `${n} lockere` });
        m.on('click', () => map.fitBounds(L.latLngBounds(c.items.map((x) => [x.lat, x.lng])), { padding: [40, 40], maxZoom: 16 }));
        layer.addLayer(m);
      }
    }
    renderList(list);
  }

  function renderList(list) {
    const box = $('[data-locker-list]', modal);
    const center = me || map.getCenter();
    const ref = { lat: center.lat, lng: center.lng };
    const sorted = list.map((l) => ({ l, d: distKm(ref, l) })).sort((a, b) => a.d - b.d).slice(0, 40);
    if (!sorted.length) { box.innerHTML = '<p class="muted" style="padding:12px">Niciun locker nu se potrivește căutării. Încearcă alt oraș sau activează mai multe rețele.</p>'; return; }
    box.innerHTML = `<p class="locker-list__head">${me ? 'Cele mai apropiate de tine' : 'Cele mai apropiate de centrul hărții'} · ${list.length} în total</p>` + sorted.map(({ l, d }) => {
      const c = cfg.carriers[l.provider] || {};
      const pr = cfg.lockerPrices[l.provider];
      return `<div class="litem ${active?.id === l.id ? 'is-active' : ''}" data-lid="${esc(l.id)}" role="button" tabindex="0">
        <span class="carrier" style="--cb:${c.color};--ci:${c.ink};--cs:30px">${esc(c.short)}</span>
        <span class="litem__main"><b>${esc(l.name)}</b><span>${esc(l.address)}</span><small>${esc(l.city)}${l.county ? `, ${esc(l.county)}` : ''} · ${esc(l.hours)} · ${pr.price ? `${(pr.price / 100).toFixed(2).replace('.', ',')} lei` : 'gratuit'}</small></span>
        <span class="litem__dist">${d < 1 ? `${Math.round(d * 1000)} m` : `${d.toFixed(d < 10 ? 1 : 0).replace('.', ',')} km`}</span>
        ${active?.id === l.id ? `<button type="button" class="btn btn--dark btn--sm litem__select" data-pick="${esc(l.id)}">Alege acest locker</button>` : ''}
      </div>`;
    }).join('');
  }

  function focusLocker(l, { pan = true } = {}) {
    active = l;
    if (pan) map.setView([l.lat, l.lng], Math.max(map.getZoom(), 16));
    else render();
    if (!pan) setTimeout(() => $(`[data-lid="${CSS.escape(l.id)}"]`, modal)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 30);
  }

  modal?.addEventListener('click', (e) => {
    const pick = e.target.closest('[data-pick]');
    if (pick) { choose(lockers.find((l) => l.id === pick.dataset.pick)); return; }
    const it = e.target.closest('[data-lid]');
    if (it) focusLocker(lockers.find((l) => l.id === it.dataset.lid));
    if (e.target === modal) closeModal();
  });
  modal?.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeModal();
    if (e.key === 'Enter' && e.target.matches('[data-lid]')) focusLocker(lockers.find((l) => l.id === e.target.dataset.lid));
  });

  function choose(l) {
    if (!l) return;
    picked = l;
    form.elements.locker_id.value = l.id;
    const radio = form.querySelector(`[data-locker-method="${l.provider}"]`);
    $$('input[name="delivery_method"]', form).forEach((i) => { i.checked = false; });
    radio.checked = true;
    const c = cfg.carriers[l.provider] || {};
    const pr = cfg.lockerPrices[l.provider];
    const box = $('[data-locker-picked]', form);
    box.innerHTML = `<span class="carrier" style="--cb:${c.color};--ci:${c.ink};--cs:40px">${esc(c.short)}</span>
      <span class="locker-pick__main"><b>${esc(l.name)}</b><span>${esc(l.address)}, ${esc(l.city)}</span><small>${esc(pr.name)} · ${esc(l.hours)} · ${esc(pr.eta)} · ${pr.price ? `${(pr.price / 100).toFixed(2).replace('.', ',')} lei` : 'livrare gratuită'}</small>
      ${pr.cod ? '' : '<small>Plata ramburs nu este disponibilă în această rețea.</small>'}</span>
      <button type="button" class="link-btn" data-open-lockers>Schimbă</button>`;
    box.hidden = false;
    $('.locker-open', form).hidden = true; // butonul mare; „Schimbă” din card rămâne
    // la locker, adresa de facturare se completează separat: pornim de la localitatea lockerului
    const bc = form.elements.bill_county;
    if (bc && !bc.value && [...bc.options].some((o) => o.value === l.county)) { bc.value = l.county; bc.closest('.field')?.classList.add('has-value'); }
    if (form.elements.bill_city && !form.elements.bill_city.value && l.city) form.elements.bill_city.value = l.city;
    showErrors(form, {});
    closeModal();
    updatePayments();
    quote();
  }

  async function openModal() {
    modal.hidden = false;
    document.documentElement.style.overflow = 'hidden';
    try {
      await ensureMap();
      setTimeout(() => { map.invalidateSize(); render(); }, 60);
      $('[data-locker-search]', modal).focus();
    } catch {
      $('[data-locker-list]', modal).innerHTML = '<p class="muted" style="padding:12px">Harta nu s-a putut încărca. Verifică conexiunea și încearcă din nou.</p>';
    }
  }
  function closeModal() { modal.hidden = true; document.documentElement.style.overflow = ''; }
  form.addEventListener('click', (e) => { if (e.target.closest('[data-open-lockers]')) openModal(); });
  $$('[data-close-lockers]').forEach((b) => b.addEventListener('click', closeModal));

  $('[data-locker-filters]', modal)?.addEventListener('change', (e) => {
    if (e.target.checked) enabled.add(e.target.value); else enabled.delete(e.target.value);
    render();
  });

  let fTimer;
  $('[data-locker-search]', modal)?.addEventListener('input', (e) => {
    clearTimeout(fTimer);
    fTimer = setTimeout(() => {
      const q = fold(e.target.value.trim());
      filterText = q ? q.split(/\s+/).filter(Boolean) : '';
      const list = visible();
      if (q && list.length) {
        // dacă textul identifică o localitate, centrăm harta pe ea
        const cityHits = list.filter((l) => fold(l.city).startsWith(q.split(' ')[0]));
        const target = cityHits.length ? cityHits : list;
        map.fitBounds(window.L.latLngBounds(target.map((l) => [l.lat, l.lng])), { padding: [30, 30], maxZoom: 15 });
      } else render();
    }, 220);
  });

  $('[data-locker-locate]', modal)?.addEventListener('click', () => {
    if (!navigator.geolocation) { toast('Browserul nu permite localizarea.', { type: 'error' }); return; }
    navigator.geolocation.getCurrentPosition((pos) => {
      me = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      if (meMarker) meMarker.remove();
      meMarker = window.L.marker([me.lat, me.lng], { icon: window.L.divIcon({ className: 'lme', iconSize: [18, 18] }), interactive: false, zIndexOffset: 2000 }).addTo(map);
      map.setView([me.lat, me.lng], 14);
    }, () => toast('Nu am putut afla locația. Caută după oraș sau stradă.', { type: 'error' }), { enableHighAccuracy: true, timeout: 8000 });
  });

  updatePayments();
  quote();
}
