document.getElementById('year').textContent = new Date().getFullYear();

// Mobile menu
const burger = document.getElementById('burger');
const nav = document.getElementById('nav');
burger.addEventListener('click', () => nav.classList.toggle('is-open'));
nav.querySelectorAll('a').forEach(link =>
  link.addEventListener('click', () => nav.classList.remove('is-open'))
);

// Catalog data (управляется через /admin.html, хранится в localStorage)
const grid = document.getElementById('grid');

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function renderCards(filter) {
  const dresses = drdLoadCatalog();
  const items = filter === 'all' ? dresses : dresses.filter(d => d.cat === filter);

  if (!items.length) {
    grid.innerHTML = '<p class="grid__empty">В этой категории пока нет образов.</p>';
    return;
  }

  grid.innerHTML = items.map(d => `
    <div class="card">
      <div class="card__media">${d.image ? `<img src="${d.image}" alt="${escapeHtml(d.name)}">` : (d.icon || '👗')}</div>
      <div class="card__body">
        <span class="card__cat">${DRD_CATEGORIES[d.cat] || d.cat}</span>
        <h3>${escapeHtml(d.name)}</h3>
        <p class="card__price">${escapeHtml(d.price)} <span>/ вечер</span></p>
        ${d.tags && d.tags.length ? `<div class="card__tags">${d.tags.map(t => `<span class="tag">${escapeHtml(t)}</span>`).join('')}</div>` : ''}
      </div>
    </div>
  `).join('');
}

renderCards('all');

document.querySelectorAll('.filter').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelector('.filter.is-active').classList.remove('is-active');
    btn.classList.add('is-active');
    renderCards(btn.dataset.filter);
  });
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

  note.textContent = 'Спасибо! Открываем WhatsApp для завершения заявки...';
  window.open(`https://wa.me/971500000000?text=${text}`, '_blank');
  form.reset();
});
