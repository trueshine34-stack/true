import { $, $$, api, toast, showErrors } from './app.js';
import { createLockerPicker } from './lockers.js';

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
    if (t.name === 'email') quote(); // codurile „doar la prima comandă” depind de email
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
    const card = form.querySelector('input[name="payment_method"][value="card"]');
    $('[data-pay-cod] small', form).textContent = codOk ? $('[data-pay-cod] small', form).dataset.base
      : `Indisponibil pentru metoda de livrare aleasă${card ? ' — plătești cu cardul la comandă' : ''}.`;
    if (!codOk && cod.checked) (card || form.querySelector('input[name="payment_method"][value="transfer"]')).checked = true;
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
      const r = await api('/api/checkout/quote', { method: 'POST', body: { delivery_method: methodInput()?.value || '', payment_method: payInput()?.value || '', email: form.elements.email?.value || '' } });
      if (seq !== qSeq) return;
      $('[data-summary-rows]', form).innerHTML = r.rows;
      if (r.promo !== undefined) $('[data-summary-promo]', form).innerHTML = r.promo;
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
  const nets = cfg.lockers?.networks || {};
  const picker = modal && createLockerPicker({ modal, cfg: cfg.lockers, onChoose: choose });

  function choose(l) {
    if (!l) return;
    picked = l;
    form.elements.locker_id.value = l.id;
    $$('input[name="delivery_method"]', form).forEach((i) => { i.checked = false; });
    const radio = form.querySelector(`[data-locker-method="${l.provider}"]`);
    if (radio) radio.checked = true;
    const n = nets[l.provider] || {};
    const box = $('[data-locker-picked]', form);
    box.innerHTML = `<span class="carrier" style="--cs:48px"><img src="${n.mark}" alt=""></span>
      <span class="locker-pick__main">
        <small class="locker-pick__net">${esc(n.name)} · ${l.point ? 'punct de ridicare' : 'locker non-stop'}</small>
        <b>${esc(l.name)}</b><span>${esc(l.address)}, ${esc(l.city)}${l.county && l.county !== l.city ? `, ${esc(l.county)}` : ''}</span>
        ${l.note ? `<small>${esc(l.note)}</small>` : ''}
        <small>${l.point ? esc(l.hours || '') + ' · ' : ''}${esc(n.eta)} · ${n.price ? `${(n.price / 100).toFixed(2).replace('.', ',')} lei` : 'livrare gratuită'}</small>
        ${n.cod ? '' : '<small class="warn">Plata ramburs nu e disponibilă în această rețea.</small>'}
      </span>
      <button type="button" class="btn btn--ghost btn--sm" data-open-lockers>Schimbă</button>`;
    box.hidden = false;
    $('[data-locker-find]', form).hidden = true;
    $('.locker-open', form).hidden = true;
    const nd = $('.lnets-wrap', form); if (nd) nd.open = false;
    // la locker, adresa de facturare se completează separat: pornim de la localitatea lockerului
    const bc = form.elements.bill_county;
    if (bc && !bc.value && [...bc.options].some((o) => o.value === l.county)) { bc.value = l.county; bc.closest('.field')?.classList.add('has-value'); }
    if (form.elements.bill_city && !form.elements.bill_city.value && l.city) form.elements.bill_city.value = l.city;
    showErrors(form, {});
    picker.setPicked(l);
    picker.close();
    updatePayments();
    quote();
  }

  form.addEventListener('click', (e) => {
    const b = e.target.closest('[data-open-lockers]');
    if (b && picker) picker.open({ locateMe: b.hasAttribute('data-locate') });
  });

  updatePayments();
  quote();
}
