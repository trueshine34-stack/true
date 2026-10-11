// Livada — JS global (fără dependențe). Îmbunătățire progresivă: totul funcționează și fără JS.

export const $ = (s, el = document) => el.querySelector(s);
export const $$ = (s, el = document) => [...el.querySelectorAll(s)];
export const csrf = () => document.querySelector('meta[name="csrf-token"]')?.content || '';
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export async function api(url, { method = 'GET', body, form } = {}) {
  const opts = { method, headers: { Accept: 'application/json', 'X-CSRF-Token': csrf() }, credentials: 'same-origin' };
  if (form) opts.body = new URLSearchParams(new FormData(form));
  else if (body) { opts.body = JSON.stringify(body); opts.headers['Content-Type'] = 'application/json'; }
  const res = await fetch(url, opts);
  let data = {};
  try { data = await res.json(); } catch { /* răspuns fără JSON */ }
  if (!res.ok) { const e = new Error(data.error || 'A apărut o eroare. Încearcă din nou.'); e.data = data; e.status = res.status; throw e; }
  return data;
}

// ─── toast ──────────────────────────────────────────────────────────────
export function toast(message, { type = 'ok', image, action } = {}) {
  const box = $('[data-toasts]');
  if (!box) return;
  const t = document.createElement('div');
  t.className = `toast ${type === 'error' ? 'toast--error' : ''}`;
  t.setAttribute('role', 'status');
  t.innerHTML = `${image ? `<img src="${esc(image)}" alt="">` : ''}<span>${esc(message)}</span>${action ? `<a href="${esc(action.href)}">${esc(action.label)}</a>` : ''}`;
  box.append(t);
  setTimeout(() => { t.classList.add('is-out'); setTimeout(() => t.remove(), 320); }, type === 'error' ? 5200 : 3600);
}

// ─── header: umbră la scroll + anunțuri rotative ────────────────────────
const header = $('[data-header]');
if (header) {
  const onScroll = () => header.classList.toggle('is-scrolled', scrollY > 4);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}
const rot = $('[data-rotator]');
if (rot && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
  const items = $$('.announce__item', rot);
  let i = 0;
  if (items.length > 1) setInterval(() => { items[i].classList.remove('is-active'); i = (i + 1) % items.length; items[i].classList.add('is-active'); }, 4200);
}

// ─── overlay-uri (meniu mobil, căutare, coș) ────────────────────────────
let lastFocus = null;
function openLayer(el) {
  if (!el) return;
  lastFocus = document.activeElement;
  el.hidden = false;
  document.documentElement.style.overflow = 'hidden';
  const f = el.querySelector('input, button, a');
  setTimeout(() => f?.focus(), 30);
}
function closeLayer(el) {
  if (!el || el.hidden) return;
  el.hidden = true;
  if (!$$('[data-mobile-nav], [data-search-overlay], [data-cart-drawer], [data-locker-modal]').some((x) => !x.hidden)) document.documentElement.style.overflow = '';
  lastFocus?.focus?.();
}
const mobileNav = $('[data-mobile-nav]');
$$('[data-open-menu]').forEach((b) => b.addEventListener('click', () => { openLayer(mobileNav); b.setAttribute('aria-expanded', 'true'); }));
$$('[data-close-menu]').forEach((b) => b.addEventListener('click', () => { closeLayer(mobileNav); $('[data-open-menu]')?.setAttribute('aria-expanded', 'false'); }));
mobileNav?.addEventListener('click', (e) => { if (e.target === mobileNav) closeLayer(mobileNav); });

// căutare cu sugestii
const search = $('[data-search-overlay]');
const sInput = $('[data-search-input]');
const sResults = $('[data-search-results]');
const sDefault = sResults?.innerHTML;
$$('[data-open-search]').forEach((b) => b.addEventListener('click', () => openLayer(search)));
$$('[data-close-search]').forEach((b) => b.addEventListener('click', () => closeLayer(search)));
search?.addEventListener('click', (e) => { if (e.target === search) closeLayer(search); });
let sTimer, sSeq = 0;
sInput?.addEventListener('input', () => {
  clearTimeout(sTimer);
  const q = sInput.value.trim();
  if (q.length < 2) { sResults.innerHTML = sDefault; return; }
  sTimer = setTimeout(async () => {
    const seq = ++sSeq;
    try {
      const { results } = await api(`/api/search?q=${encodeURIComponent(q)}`);
      if (seq !== sSeq) return;
      sResults.innerHTML = results.length
        ? `<p class="eyebrow">Produse</p><div class="sresults">${results.map((r) => `<a class="sresult" href="/produs/${esc(r.slug)}">
            <img src="${esc(r.image || '')}" alt="" width="56" height="56"><span><b>${esc(r.name)}</b><small>${esc(r.category)}</small></span><span class="sresult__price">${esc(r.price)}</span></a>`).join('')}
            </div><a class="link-arrow" href="/cautare?q=${encodeURIComponent(q)}">Toate rezultatele pentru „${esc(q)}” →</a>`
        : `<p class="muted">Niciun rezultat pentru „${esc(q)}”. Încearcă „iPhone”, „MacBook” sau „husă”.</p>`;
    } catch { /* ignoră */ }
  }, 160);
});
sInput?.addEventListener('keydown', (e) => {
  const items = $$('.sresult', sResults);
  if (!items.length || !['ArrowDown', 'ArrowUp'].includes(e.key)) return;
  e.preventDefault();
  let i = items.findIndex((x) => x.classList.contains('is-active'));
  items[i]?.classList.remove('is-active');
  i = e.key === 'ArrowDown' ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
  items[i].classList.add('is-active');
  items[i].focus();
});

// coș (drawer)
const drawer = $('[data-cart-drawer]');
export function setCartCount(n) {
  $$('[data-cart-count]').forEach((el) => {
    el.textContent = n || '';
    el.classList.toggle('is-empty', !n);
    el.classList.remove('is-bump');
    void el.offsetWidth;
    if (n) el.classList.add('is-bump');
  });
}
export async function refreshDrawer() {
  if (!drawer) return;
  const d = await api('/cos/drawer');
  $('[data-cart-body]', drawer).innerHTML = d.body;
  const foot = $('[data-cart-foot]', drawer);
  foot.innerHTML = d.foot;
  foot.hidden = !d.foot;
  setCartCount(d.count);
}
export async function openCart() {
  openLayer(drawer);
  try { await refreshDrawer(); } catch { /* */ }
}
$$('[data-open-cart]').forEach((a) => a.addEventListener('click', (e) => {
  if (location.pathname === '/cos' || location.pathname === '/checkout') return;
  e.preventDefault();
  openCart();
}));
$$('[data-close-cart]').forEach((b) => b.addEventListener('click', () => closeLayer(drawer)));
drawer?.addEventListener('click', (e) => { if (e.target === drawer) closeLayer(drawer); });

addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  [drawer, search, mobileNav].forEach(closeLayer);
});
addEventListener('keydown', (e) => {
  if ((e.key === 'k' && (e.metaKey || e.ctrlKey)) || (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName))) {
    e.preventDefault();
    openLayer(search);
  }
});

// ─── acțiuni coș (cantitate / ștergere / promo) prin delegare ───────────
document.addEventListener('submit', async (e) => {
  const form = e.target;
  const isQty = form.matches('[data-cart-qty]');
  const isRemove = form.matches('[data-cart-remove]');
  const isPromo = form.matches('[data-cart-promo]');
  if (!isQty && !isRemove && !isPromo) return;
  e.preventDefault();
  const fd = new FormData(form);
  if (e.submitter?.name) fd.set(e.submitter.name, e.submitter.value);
  try {
    const res = await fetch(form.action, { method: 'POST', body: new URLSearchParams(fd), headers: { Accept: 'application/json', 'X-CSRF-Token': csrf() } });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.errors ? Object.values(data.errors)[0] : data.error);
    if (data.message && isPromo) toast(data.message);
    // reîmprospătare fragmente
    const page = $('[data-cart-page]');
    if (page) page.outerHTML = await (await fetch('/cos/fragment', { headers: { Accept: 'text/html' } })).text();
    if (drawer && !drawer.hidden) await refreshDrawer();
    else if (typeof data.count === 'number') setCartCount(data.count);
    if (location.pathname === '/checkout' && isPromo) location.reload();
  } catch (err) { toast(err.message || 'Nu am putut actualiza coșul.', { type: 'error' }); }
});

// ─── formulare AJAX cu erori pe câmpuri ──────────────────────────────────
export function showErrors(form, errors = {}, message) {
  $$('.has-error', form).forEach((el) => el.classList.remove('has-error'));
  $$('[data-error-for]', form).forEach((el) => { if (!el.dataset.hint) el.dataset.hint = el.classList.contains('is-error') ? '' : el.textContent; el.textContent = el.dataset.hint; el.classList.remove('is-error'); });
  const box = $('[data-form-error]', form);
  let first = null;
  for (const [name, msg] of Object.entries(errors)) {
    const input = form.querySelector(`[name="${CSS.escape(name)}"]`);
    const slot = form.querySelector(`[data-error-for="${CSS.escape(name)}"]`);
    if (slot) { slot.textContent = msg; slot.classList.add('is-error'); }
    const wrap = input?.closest('.field, .check');
    wrap?.classList.add('has-error');
    if (!first) first = input || slot;
  }
  if (box) {
    box.hidden = !message || Object.keys(errors).length > 0;
    box.textContent = message || '';
  } else if (message && !Object.keys(errors).length) toast(message, { type: 'error' });
  first?.focus?.({ preventScroll: true });
  first?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
}

document.addEventListener('submit', async (e) => {
  const form = e.target;
  if (!form.matches('[data-ajax]')) return;
  e.preventDefault();
  const btn = e.submitter || form.querySelector('[type="submit"]');
  btn?.classList.add('is-loading');
  try {
    const data = await api(form.action, { method: 'POST', form });
    showErrors(form, {});
    if (form.matches('[data-reset]')) form.reset();
    if (data.replace) { (form.closest('.panel, .auth__card') || form).innerHTML = data.replace; }
    if (data.redirect) { location.href = data.redirect; return; }
    if (data.message) toast(data.message);
    form.dispatchEvent(new CustomEvent('ajax:done', { detail: data }));
  } catch (err) {
    showErrors(form, err.data?.errors, err.message);
    form.dispatchEvent(new CustomEvent('ajax:error', { detail: err }));
  } finally {
    btn?.classList.remove('is-loading');
  }
});

// confirmare pentru acțiuni distructive
document.addEventListener('submit', (e) => {
  const msg = e.target.dataset?.confirm;
  if (msg && !confirm(msg)) e.preventDefault();
}, true);

// select de sortare cu trimitere automată
$$('[data-autosubmit] select').forEach((s) => s.addEventListener('change', () => s.form.submit()));

// parole: arată/ascunde
document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-toggle-password]');
  if (!b) return;
  const input = b.parentElement.querySelector('input');
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  b.setAttribute('aria-label', show ? 'Ascunde parola' : 'Arată parola');
});

// ─── favorite ───────────────────────────────────────────────────────────
$$('[data-favorite]').forEach(async (btn) => {
  if (document.body.dataset.user === '1') {
    try {
      const { ids } = await api('/api/favorites');
      btn.setAttribute('aria-pressed', String(ids.includes(Number(btn.dataset.favorite))));
    } catch { /* */ }
  }
  btn.addEventListener('click', async () => {
    try {
      const r = await api(`/api/favorites/${btn.dataset.favorite}`, { method: 'POST' });
      btn.setAttribute('aria-pressed', String(r.on));
      toast(r.message, { action: r.on ? { href: '/cont/favorite', label: 'Vezi' } : undefined });
    } catch (err) {
      if (err.status === 401) location.href = `/cont/autentificare?next=${encodeURIComponent(location.pathname)}`;
      else toast(err.message, { type: 'error' });
    }
  });
});

// ─── scroll orizontal cu săgeți ─────────────────────────────────────────
$$('[data-hscroll]').forEach((wrap) => {
  const track = $('.hscroll__track', wrap);
  const prev = $('[data-hscroll-prev]', wrap), next = $('[data-hscroll-next]', wrap);
  const update = () => {
    if (prev) prev.disabled = track.scrollLeft < 8;
    if (next) next.disabled = track.scrollLeft + track.clientWidth > track.scrollWidth - 8;
  };
  const step = () => track.clientWidth * 0.8;
  prev?.addEventListener('click', () => track.scrollBy({ left: -step(), behavior: 'smooth' }));
  next?.addEventListener('click', () => track.scrollBy({ left: step(), behavior: 'smooth' }));
  track.addEventListener('scroll', update, { passive: true });
  update();
});

// swatch-uri pe carduri: hover → imaginea culorii
$$('[data-product-card]').forEach((card) => {
  const img = $('[data-card-img]', card);
  if (!img) return;
  const orig = img.getAttribute('src');
  const origSet = img.getAttribute('srcset');
  $$('[data-swatch-img]', card).forEach((sw) => {
    if (!sw.dataset.swatchImg) return;
    sw.addEventListener('mouseenter', () => { img.removeAttribute('srcset'); img.src = sw.dataset.swatchImg; });
  });
  card.addEventListener('mouseleave', () => { img.src = orig; if (origSet) img.setAttribute('srcset', origSet); });
});

// ─── cookie consent ─────────────────────────────────────────────────────
const cookie = $('[data-cookie]');
const getConsent = () => { try { return localStorage.getItem('lv_consent'); } catch { return 'unknown'; } };
if (cookie && !getConsent()) setTimeout(() => { cookie.hidden = false; }, 900);
$$('[data-cookie-choice]').forEach((b) => b.addEventListener('click', () => {
  try { localStorage.setItem('lv_consent', b.dataset.cookieChoice); } catch { /* */ }
  cookie.hidden = true;
}));
$$('[data-cookie-reset]').forEach((b) => b.addEventListener('click', () => {
  try { localStorage.removeItem('lv_consent'); } catch { /* */ }
  if (cookie) cookie.hidden = false;
}));

// ─── apariție la scroll ─────────────────────────────────────────────────
if ('IntersectionObserver' in window) {
  const io = new IntersectionObserver((entries) => entries.forEach((en) => { if (en.isIntersecting) { en.target.classList.add('is-in'); io.unobserve(en.target); } }), { rootMargin: '0px 0px -8% 0px' });
  $$('.section .pgrid > *, .benefits li, .tile, .deliver, .story > *, .spotlight').forEach((el, i) => { el.classList.add('reveal'); el.style.transitionDelay = `${(i % 4) * 60}ms`; io.observe(el); });
}
