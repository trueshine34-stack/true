/* DressRentDubai — shared script for all pages.
   Each block guards on element existence so one file serves the whole site. */

document.documentElement.classList.add('js');

const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// Footer year
const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

// Mobile menu
const burger = document.getElementById('burger');
const nav = document.getElementById('nav');
if (burger && nav) {
  burger.addEventListener('click', () => nav.classList.toggle('is-open'));
  nav.querySelectorAll('a').forEach(link =>
    link.addEventListener('click', () => nav.classList.remove('is-open'))
  );
}

// Scroll reveal
const revealEls = document.querySelectorAll('[data-reveal]');
if (revealEls.length) {
  const io = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        io.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  revealEls.forEach(el => io.observe(el));
}

/* ============================================================
   Gallery — horizontal rail with category tabs
   Replace `img` URLs with your own photos: keep portrait 3:4 shots.
   ============================================================ */
const looks = [
  // Вечерние
  { name: 'Elie Saab Midnight', cat: 'evening', catLabel: 'Вечернее', price: '850 AED',
    img: 'https://images.unsplash.com/photo-1539008835657-9e8e9680c956?auto=format&fit=crop&w=640&q=75' },
  { name: 'Zuhair Murad Aurora', cat: 'evening', catLabel: 'Вечернее', price: '1 200 AED',
    img: 'https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=640&q=75' },
  { name: 'Noir Velvet', cat: 'evening', catLabel: 'Вечернее', price: '700 AED',
    img: 'https://images.unsplash.com/photo-1524504388940-b1c1722653e1?auto=format&fit=crop&w=640&q=75' },
  { name: 'Desert Rose Gown', cat: 'evening', catLabel: 'Вечернее', price: '900 AED',
    img: 'https://images.unsplash.com/photo-1496747611176-843222e1e57c?auto=format&fit=crop&w=640&q=75' },
  { name: 'Sahara Couture', cat: 'evening', catLabel: 'Вечернее', price: '1 100 AED',
    img: 'https://images.unsplash.com/photo-1445205170230-053b83016050?auto=format&fit=crop&w=640&q=75' },

  // Коктейльные
  { name: 'Rose Cocktail', cat: 'cocktail', catLabel: 'Коктейльное', price: '420 AED',
    img: 'https://images.unsplash.com/photo-1572804013309-59a88b7e92f1?auto=format&fit=crop&w=640&q=75' },
  { name: 'Emerald Silk', cat: 'cocktail', catLabel: 'Коктейльное', price: '380 AED',
    img: 'https://images.unsplash.com/photo-1495385794356-15371f348c31?auto=format&fit=crop&w=640&q=75' },
  { name: 'Champagne Shine', cat: 'cocktail', catLabel: 'Коктейльное', price: '450 AED',
    img: 'https://images.unsplash.com/photo-1490725263030-1f0521cec8ec?auto=format&fit=crop&w=640&q=75' },
  { name: 'Ivory Charm', cat: 'cocktail', catLabel: 'Коктейльное', price: '400 AED',
    img: 'https://images.unsplash.com/photo-1515372039744-b8f02a3ae446?auto=format&fit=crop&w=640&q=75' },
  { name: 'Dune Sunset', cat: 'cocktail', catLabel: 'Коктейльное', price: '440 AED',
    img: 'https://images.unsplash.com/photo-1581044777550-4cfa60707c03?auto=format&fit=crop&w=640&q=75' },

  // Гостьям на свадьбу
  { name: 'Golden Guest', cat: 'wedding', catLabel: 'Гостье на свадьбу', price: '650 AED',
    img: 'https://images.unsplash.com/photo-1519741497674-611481863552?auto=format&fit=crop&w=640&q=75' },
  { name: 'Blush Elegance', cat: 'wedding', catLabel: 'Гостье на свадьбу', price: '590 AED',
    img: 'https://images.unsplash.com/photo-1509319117193-57bab727e09d?auto=format&fit=crop&w=640&q=75' },
  { name: 'Sahara Silk', cat: 'wedding', catLabel: 'Гостье на свадьбу', price: '620 AED',
    img: 'https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=640&q=75' },

  // Аксессуары
  { name: 'Pearl Set', cat: 'accessories', catLabel: 'Украшения', price: '250 AED',
    img: 'https://images.unsplash.com/photo-1515562141207-7a88fb7ce338?auto=format&fit=crop&w=640&q=75' },
  { name: 'Satin Clutch', cat: 'accessories', catLabel: 'Клатч', price: '120 AED',
    img: 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=640&q=75' },
  { name: 'Crystal Earrings', cat: 'accessories', catLabel: 'Украшения', price: '180 AED',
    img: 'https://images.unsplash.com/photo-1535632066927-ab7c9ab60908?auto=format&fit=crop&w=640&q=75' },
  { name: 'Couture Heels', cat: 'accessories', catLabel: 'Обувь', price: '200 AED',
    img: 'https://images.unsplash.com/photo-1543163521-1bf539c55dd2?auto=format&fit=crop&w=640&q=75' },
];

const rail = document.getElementById('rail');
if (rail) {
  const tabs = document.querySelectorAll('.tab');
  const ink = document.querySelector('.tabs__ink');
  const prevBtn = document.getElementById('railPrev');
  const nextBtn = document.getElementById('railNext');
  const bar = document.getElementById('railBar');

  function renderRail(cat) {
    const items = looks.filter(l => l.cat === cat);
    rail.innerHTML = items.map((l, i) => `
      <article class="gcard" style="--i:${i}">
        <div class="gcard__frame">
          <img src="${l.img}" alt="${l.name} — ${l.catLabel.toLowerCase()}, аренда в Дубае" loading="lazy">
        </div>
        <div class="gcard__body">
          <span class="gcard__cat">${l.catLabel}</span>
          <h3>${l.name}</h3>
          <p class="gcard__price">от ${l.price}<span> / вечер</span></p>
        </div>
      </article>`).join('');

    rail.scrollLeft = 0;
    rail.querySelectorAll('img').forEach(img => {
      if (img.complete && img.naturalWidth) {
        img.classList.add('is-loaded');
      } else {
        img.addEventListener('load', () => img.classList.add('is-loaded'), { once: true });
        img.addEventListener('error', () => img.closest('.gcard__frame').classList.add('is-fallback'), { once: true });
      }
    });
    updateProgress();
    updateArrows();
  }

  function moveInk(btn) {
    if (!ink || !btn) return;
    ink.style.left = btn.offsetLeft + 'px';
    ink.style.width = btn.offsetWidth + 'px';
  }

  function switchCat(btn) {
    tabs.forEach(t => t.classList.remove('is-active'));
    btn.classList.add('is-active');
    moveInk(btn);
    if (prefersReducedMotion) {
      renderRail(btn.dataset.cat);
      return;
    }
    rail.classList.add('is-leaving');
    setTimeout(() => {
      renderRail(btn.dataset.cat);
      rail.classList.remove('is-leaving');
    }, 250);
  }

  tabs.forEach(btn => btn.addEventListener('click', () => switchCat(btn)));

  // Arrows: scroll by one card + gap
  function cardStep() {
    const card = rail.querySelector('.gcard');
    return card ? card.offsetWidth + 26 : 320;
  }
  if (prevBtn && nextBtn) {
    prevBtn.addEventListener('click', () => rail.scrollBy({ left: -cardStep() * 2, behavior: 'smooth' }));
    nextBtn.addEventListener('click', () => rail.scrollBy({ left: cardStep() * 2, behavior: 'smooth' }));
  }

  function updateArrows() {
    if (!prevBtn || !nextBtn) return;
    const max = rail.scrollWidth - rail.clientWidth;
    prevBtn.disabled = rail.scrollLeft <= 4;
    nextBtn.disabled = rail.scrollLeft >= max - 4;
  }

  function updateProgress() {
    if (!bar) return;
    const max = rail.scrollWidth - rail.clientWidth;
    bar.style.transform = `scaleX(${max > 0 ? rail.scrollLeft / max : 1})`;
  }

  rail.addEventListener('scroll', () => { updateProgress(); updateArrows(); }, { passive: true });

  // Drag-to-scroll with mouse
  let dragStartX = 0;
  let dragStartLeft = 0;
  let dragging = false;

  rail.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'mouse') return;
    dragging = true;
    dragStartX = e.clientX;
    dragStartLeft = rail.scrollLeft;
  });
  window.addEventListener('pointermove', (e) => {
    if (!dragging) return;
    const delta = e.clientX - dragStartX;
    if (Math.abs(delta) > 4) rail.classList.add('is-dragging');
    rail.scrollLeft = dragStartLeft - delta;
  });
  window.addEventListener('pointerup', () => {
    dragging = false;
    rail.classList.remove('is-dragging');
  });

  const activeTab = document.querySelector('.tab.is-active') || tabs[0];
  renderRail(activeTab.dataset.cat);
  // Position the ink after fonts settle so widths are final
  requestAnimationFrame(() => moveInk(activeTab));
  window.addEventListener('load', () => moveInk(document.querySelector('.tab.is-active')));
  window.addEventListener('resize', () => {
    moveInk(document.querySelector('.tab.is-active'));
    updateProgress();
    updateArrows();
  });
}

// Booking form -> WhatsApp
const form = document.getElementById('bookingForm');
if (form) {
  const note = document.getElementById('formNote');
  const typeLabels = {
    evening: 'Вечернее платье',
    cocktail: 'Коктейльное платье',
    wedding: 'Образ гостьи на свадьбу',
    accessories: 'Только аксессуары',
  };

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const data = new FormData(form);
    const text = encodeURIComponent(
      `Здравствуйте! Меня зовут ${data.get('name')}.\n` +
      `Телефон: ${data.get('phone')}\n` +
      `Дата мероприятия: ${data.get('date')}\n` +
      `Тип образа: ${typeLabels[data.get('type')] || data.get('type')}\n` +
      `Комментарий: ${data.get('message') || '—'}`
    );
    if (note) note.textContent = 'Спасибо! Открываем WhatsApp для завершения заявки...';
    window.open(`https://wa.me/971500000000?text=${text}`, '_blank');
    form.reset();
  });
}
