document.getElementById('year').textContent = new Date().getFullYear();

// Mobile menu
const burger = document.getElementById('burger');
const nav = document.getElementById('nav');
burger.addEventListener('click', () => nav.classList.toggle('is-open'));
nav.querySelectorAll('a').forEach(link =>
  link.addEventListener('click', () => nav.classList.remove('is-open'))
);

// Catalog data
const dresses = [
  { name: 'Elie Saab Midnight', cat: 'evening', catLabel: 'Вечернее', price: '850 AED', icon: '👗' },
  { name: 'Zuhair Murad Aurora', cat: 'evening', catLabel: 'Вечернее', price: '1 200 AED', icon: '👗' },
  { name: 'Rose Cocktail', cat: 'cocktail', catLabel: 'Коктейльное', price: '420 AED', icon: '💃' },
  { name: 'Emerald Silk', cat: 'cocktail', catLabel: 'Коктейльное', price: '380 AED', icon: '💃' },
  { name: 'Golden Guest', cat: 'wedding', catLabel: 'Гостье на свадьбу', price: '650 AED', icon: '🌸' },
  { name: 'Blush Elegance', cat: 'wedding', catLabel: 'Гостье на свадьбу', price: '590 AED', icon: '🌸' },
  { name: 'Diamond Set', cat: 'accessories', catLabel: 'Украшения', price: '250 AED', icon: '💎' },
  { name: 'Satin Clutch', cat: 'accessories', catLabel: 'Клатч', price: '120 AED', icon: '👛' },
];

const grid = document.getElementById('grid');

function renderCards(filter) {
  const items = filter === 'all' ? dresses : dresses.filter(d => d.cat === filter);
  grid.innerHTML = items.map(d => `
    <div class="card">
      <div class="card__media">${d.icon}</div>
      <div class="card__body">
        <span class="card__cat">${d.catLabel}</span>
        <h3>${d.name}</h3>
        <p class="card__price">${d.price} <span>/ вечер</span></p>
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
