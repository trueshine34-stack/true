document.getElementById('year').textContent = new Date().getFullYear();

// Mobile menu
const burger = document.getElementById('burger');
const nav = document.getElementById('nav');
burger.addEventListener('click', () => nav.classList.toggle('is-open'));
nav.querySelectorAll('a').forEach(link =>
  link.addEventListener('click', () => nav.classList.remove('is-open'))
);

// Language toggle
drdApplyI18n();
document.getElementById('langToggle').addEventListener('click', () => {
  drdSetLang(drdGetLang() === 'ru' ? 'en' : 'ru');
  drdApplyI18n();
  renderCatalog();
});

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// Catalog
const grid = document.getElementById('grid');
const filtersEl = document.getElementById('filters');
const searchInput = document.getElementById('catalogSearch');

let allItems = [];
let activeTags = new Set();

function buildFilters() {
  filtersEl.innerHTML = '';
  const allBtn = document.createElement('button');
  allBtn.className = 'filter' + (activeTags.size === 0 ? ' is-active' : '');
  allBtn.dataset.tag = '';
  allBtn.setAttribute('data-i18n', 'catalog.all');
  allBtn.textContent = drdT('catalog.all');
  filtersEl.appendChild(allBtn);

  DRD_TAGS.forEach(slug => {
    const btn = document.createElement('button');
    btn.className = 'filter' + (activeTags.has(slug) ? ' is-active' : '');
    btn.dataset.tag = slug;
    btn.textContent = drdTagLabel(slug);
    filtersEl.appendChild(btn);
  });
}

filtersEl.addEventListener('click', (e) => {
  const btn = e.target.closest('.filter');
  if (!btn) return;
  const tag = btn.dataset.tag;

  if (tag === '') {
    activeTags.clear();
    filtersEl.querySelectorAll('.filter').forEach(b => b.classList.remove('is-active'));
    btn.classList.add('is-active');
  } else {
    filtersEl.querySelector('.filter[data-tag=""]').classList.remove('is-active');
    btn.classList.toggle('is-active');
    if (activeTags.has(tag)) activeTags.delete(tag); else activeTags.add(tag);
    if (activeTags.size === 0) filtersEl.querySelector('.filter[data-tag=""]').classList.add('is-active');
  }
  renderGrid();
});

function matchesQuery(item, query) {
  if (!query) return true;
  const q = query.toLowerCase();
  if (item.name.toLowerCase().includes(q)) return true;
  return (item.tags || []).some(t => t.toLowerCase().includes(q) || drdTagLabel(t).toLowerCase().includes(q));
}

function renderGrid() {
  const query = searchInput.value.trim();
  let items = allItems.filter(i => matchesQuery(i, query));
  if (activeTags.size > 0) {
    items = items.filter(i => (i.tags || []).some(t => activeTags.has(t)));
  }

  if (!allItems.length) {
    grid.innerHTML = `<p class="grid__empty">${drdT('catalog.emptyCatalog')}</p>`;
    return;
  }
  if (!items.length) {
    grid.innerHTML = `<p class="grid__empty">${drdT('catalog.empty')}</p>`;
    return;
  }

  grid.innerHTML = items.map(item => `
    <div class="card">
      <div class="card__media">${item.image ? `<img src="${item.image}" alt="${escapeHtml(item.name)}" loading="lazy">` : DRD_DEFAULT_ICON}</div>
      <div class="card__body">
        <h3>${escapeHtml(item.name)}</h3>
        ${item.price ? `<p class="card__price">${escapeHtml(item.price)} <span>${drdT('catalog.perEvening')}</span></p>` : ''}
        ${item.tags && item.tags.length ? `<div class="card__tags">${item.tags.map(t => `<span class="tag" data-tag="${t}">${drdTagLabel(t)}</span>`).join('')}</div>` : ''}
      </div>
    </div>
  `).join('');

  grid.querySelectorAll('.tag').forEach(el => {
    el.addEventListener('click', () => {
      const tag = el.dataset.tag;
      filtersEl.querySelector('.filter[data-tag=""]').classList.remove('is-active');
      const btn = filtersEl.querySelector(`.filter[data-tag="${tag}"]`);
      if (btn) btn.classList.add('is-active');
      activeTags.add(tag);
      renderGrid();
      document.getElementById('catalog').scrollIntoView({ behavior: 'smooth' });
    });
  });
}

function renderCatalog() {
  buildFilters();
  renderGrid();
}

searchInput.addEventListener('input', renderGrid);

drdFetchPublicCatalog().then(items => {
  allItems = items;
  renderCatalog();
});

// Booking form -> WhatsApp
const form = document.getElementById('bookingForm');
const note = document.getElementById('formNote');

form.addEventListener('submit', (e) => {
  e.preventDefault();
  const data = new FormData(form);
  const name = data.get('name');
  const phone = data.get('phone');
  const date = data.get('date');
  const type = data.get('type');
  const message = data.get('message');

  const text = encodeURIComponent(
    `Здравствуйте! Меня зовут ${name}.\nТелефон: ${phone}\nДата мероприятия: ${date}\nТип образа: ${type}\nКомментарий: ${message || '—'}`
  );

  note.textContent = drdT('booking.sending');
  window.open(`https://wa.me/971500000000?text=${text}`, '_blank');
  form.reset();
});
