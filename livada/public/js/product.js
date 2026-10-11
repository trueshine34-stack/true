import { $, $$, api, toast, openCart, setCartCount } from './app.js';

const data = JSON.parse($('#product-data')?.textContent || 'null');
const form = $('[data-add-to-cart]');

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = new Intl.NumberFormat('ro-RO', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const lei = (b) => `${fmt.format(b / 100)} lei`;

if (data && form) {
  const keys = data.options.map((o) => o.key);
  let current = data.variants.find((v) => v.id === data.selected) || data.variants[0];
  const sel = { ...current.options };

  const matches = (v, s) => keys.every((k) => v.options[k] === s[k]);
  const available = (v) => data.preorder || v.stock > 0;

  /** cea mai bună variantă pentru o alegere: întâi potrivire exactă, apoi cât mai multe opțiuni păstrate */
  function resolve(changedKey, s = sel) {
    const exact = data.variants.find((v) => matches(v, s));
    if (exact) return exact;
    const scored = data.variants.filter((v) => v.options[changedKey] === s[changedKey])
      .map((v) => ({ v, score: keys.filter((k) => v.options[k] === s[k]).length * 2 + (available(v) ? 1 : 0) }))
      .sort((a, b) => b.score - a.score || a.v.price - b.v.price);
    return scored[0]?.v || data.variants[0];
  }

  function updateOptionsUI() {
    for (const o of data.options) {
      const fs = form.querySelector(`[data-option="${o.key}"]`);
      if (!fs) continue;
      const label = o.values.find((x) => x.value === sel[o.key])?.label || '';
      $('[data-option-value]', fs).textContent = label;
      for (const input of $$('input', fs)) {
        input.checked = input.value === sel[o.key];
        // variantă cu restul opțiunilor neschimbate; dacă nu există (ex. titan → doar GPS + Cellular),
        // alegerea schimbă și alte opțiuni, iar diferența de preț se calculează față de varianta rezultată
        const probe = data.variants.find((v) => keys.every((k) => (k === o.key ? v.options[k] === input.value : v.options[k] === sel[k])));
        const target = probe || resolve(o.key, { ...sel, [o.key]: input.value });
        const out = probe ? !available(probe) : !data.variants.some((v) => v.options[o.key] === input.value && available(v));
        const card = input.closest('.opt-card, .swatch-pick');
        card?.classList.toggle('is-unavailable', out);
        card?.classList.toggle('is-alt', !probe && !out); // disponibilă cu altă carcasă / conectivitate
        const delta = card?.querySelector('[data-delta]');
        if (delta) {
          const d = target ? target.price - current.price : 0;
          delta.textContent = out ? 'stoc epuizat' : !target || target.id === current.id || d === 0 ? '' : `${d > 0 ? '+' : '−'}${lei(Math.abs(d))}`;
        }
      }
    }
  }

  function updateGallery() {
    if (!data.image_key) return;
    const imgs = data.images[current.options[data.image_key]];
    if (!imgs) return;
    const track = $('[data-gallery-track]');
    const gallery = $('[data-gallery]');
    const alt = `${data.name}`;
    track.innerHTML = imgs.map((im, i) => `<figure class="gallery__slide"><img src="${esc(im['1000'])}" alt="${esc(alt)}" width="1000" height="1000" ${i ? 'loading="lazy"' : ''}></figure>`).join('');
    track.scrollLeft = 0;
    const thumbs = $('[data-gallery-thumbs]');
    if (thumbs) thumbs.innerHTML = imgs.length > 1 ? imgs.map((im, i) => `<button type="button" class="gallery__thumb ${i ? '' : 'is-active'}" aria-label="Imaginea ${i + 1}" data-index="${i}"><img src="${esc(im['480'])}" alt="" width="80" height="80"></button>`).join('') : '';
    gallery.classList.remove('is-swapping'); void gallery.offsetWidth; gallery.classList.add('is-swapping');
  }

  function updateStock() {
    const box = $('[data-stock]');
    const btn = $('[data-add-btn]');
    const barBtn = $('[data-buybar-add]');
    let html;
    if (data.preorder) html = box.innerHTML;
    else if (current.stock <= 0) html = `<p class="stock stock--out"><svg class="i" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.4"/></svg><span><b>Stoc epuizat</b> în această configurație. Alege altă variantă.</span></p>`;
    else {
      const eta = data.eta || '';
      html = `<p class="stock ${current.stock <= 3 ? 'stock--low' : 'stock--in'}"><span class="dot dot--live"></span><span><b>${current.stock <= 3 ? `Doar ${current.stock} în stoc` : 'În stoc'}.</b>${eta ? ` Livrare estimată: ${eta}` : ''}</span></p>`;
    }
    box.innerHTML = html;
    const disabled = !available(current);
    btn.disabled = disabled;
    if (barBtn) barBtn.disabled = disabled;
  }

  function render() {
    $('[data-variant-input]').value = current.id;
    $('[data-amount]').textContent = lei(current.price);
    $('[data-amount]').classList.toggle('is-sale', !!current.compare_at);
    const cmp = $('[data-compare]');
    cmp.hidden = !current.compare_at;
    cmp.textContent = current.compare_at ? lei(current.compare_at) : '';
    const inst = $('[data-installments]');
    if (inst) { inst.hidden = current.price < data.installmentsMin; $('[data-monthly]').textContent = lei(Math.ceil(current.price / 12)); }
    $('[data-sku]').textContent = current.sku;
    const mpn = $('[data-mpn]');
    if (mpn) mpn.textContent = current.mpn || '';
    const bp = $('[data-buybar-price]');
    if (bp) bp.textContent = lei(current.price);
    updateOptionsUI();
    updateStock();
    const url = new URL(location.href);
    url.searchParams.set('v', current.sku);
    history.replaceState(null, '', url);
  }

  form.addEventListener('change', (e) => {
    const input = e.target;
    if (!input.name?.startsWith('opt-')) return;
    const key = input.name.slice(4);
    const prevImg = current.options[data.image_key];
    sel[key] = input.value;
    current = resolve(key);
    Object.assign(sel, current.options);
    render();
    if (data.image_key && current.options[data.image_key] !== prevImg) updateGallery();
  });

  // galerie: miniaturi + sincronizare la swipe
  document.addEventListener('click', (e) => {
    const t = e.target.closest('.gallery__thumb');
    if (!t) return;
    const track = $('[data-gallery-track]');
    track.scrollTo({ left: track.clientWidth * Number(t.dataset.index), behavior: 'smooth' });
  });
  $('[data-gallery-track]')?.addEventListener('scroll', (e) => {
    const i = Math.round(e.target.scrollLeft / e.target.clientWidth);
    $$('.gallery__thumb').forEach((b, j) => b.classList.toggle('is-active', i === j));
  }, { passive: true });

  // adaugă în coș
  async function add() {
    const btn = $('[data-add-btn]');
    btn.classList.add('is-loading');
    try {
      const r = await api(form.action, { method: 'POST', form });
      setCartCount(r.count);
      const img = data.images[current.options[data.image_key]]?.[0]?.['480'] || Object.values(data.images)[0]?.[0]?.['480'];
      toast(`${data.name} a fost adăugat în coș`, { image: img, action: { href: '/cos', label: 'Vezi coșul' } });
      openCart();
    } catch (err) {
      toast(err.message, { type: 'error' });
    } finally { btn.classList.remove('is-loading'); }
  }
  form.addEventListener('submit', (e) => { e.preventDefault(); add(); });
  $('[data-buybar-add]')?.addEventListener('click', add);

  // bara de cumpărare mobilă apare după ce butonul principal iese din ecran
  const bar = $('[data-buybar]');
  const anchor = $('[data-add-btn]');
  if (bar && anchor && 'IntersectionObserver' in window) {
    new IntersectionObserver(([en]) => bar.classList.toggle('is-visible', !en.isIntersecting && en.boundingClientRect.top < 0)).observe(anchor);
  }

  render();
}
export { setCartCount };
