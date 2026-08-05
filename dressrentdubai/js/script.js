/* Rent.Dress in Dubai — shared script for all pages.
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
   Photos: real shoots from Instagram @rent.dressindubai
   (files in images/, look numbers from the studio's catalog).
   ============================================================ */
const looks = [
  // Съёмки в пустыне — платье №76
  { name: 'Платье №76 · Рассвет', cat: 'desert', catLabel: 'В пустыне', img: 'images/desert-1.jpg' },
  { name: 'Платье №76 · Караван', cat: 'desert', catLabel: 'В пустыне', img: 'images/desert-2.jpg' },
  { name: 'Платье №76 · Портрет', cat: 'desert', catLabel: 'В пустыне', img: 'images/desert-3.jpg' },
  { name: 'Платье №76 · Дюны', cat: 'desert', catLabel: 'В пустыне', img: 'images/desert-4.jpg' },
  { name: 'Платье №76 · Закат', cat: 'desert', catLabel: 'В пустыне', img: 'images/desert-5.jpg' },

  // Архитектурные съёмки — платье №94, Лувр Абу-Даби
  { name: 'Платье №94 · Ночь', cat: 'city', catLabel: 'Архитектура', img: 'images/arch-1.jpg' },
  { name: 'Платье №94 · Купол', cat: 'city', catLabel: 'Архитектура', img: 'images/arch-2.jpg' },
  { name: 'Платье №94 · Лувр Абу-Даби', cat: 'city', catLabel: 'Архитектура', img: 'images/arch-3.jpg' },
  { name: 'Платье №94 · Золотой час', cat: 'city', catLabel: 'Архитектура', img: 'images/arch-4.jpg' },
  { name: 'Платье №94 · Силуэт', cat: 'city', catLabel: 'Архитектура', img: 'images/arch-5.jpg' },

  // Вечерние и смелые образы
  { name: 'Платье №60 · Перья', cat: 'evening', catLabel: 'Вечерние', img: 'images/evening-1.jpg' },
  { name: 'Платье №60 · Будуар', cat: 'evening', catLabel: 'Вечерние', img: 'images/evening-2.jpg' },
  { name: 'Платье №60 · Зеркало', cat: 'evening', catLabel: 'Вечерние', img: 'images/evening-3.jpg' },
  { name: 'Образ №130 · Леопард', cat: 'evening', catLabel: 'Вечерние', img: 'images/evening-4.jpg' },
  { name: 'Образ №130 · Сафари', cat: 'evening', catLabel: 'Вечерние', img: 'images/evening-5.jpg' },

  // Съёмки для будущих мам — шляпа №301
  { name: 'Шляпа №301 · Океан', cat: 'mama', catLabel: 'Будущим мамам', img: 'images/mama-1.jpg' },
  { name: 'Шляпа №301 · Бриз', cat: 'mama', catLabel: 'Будущим мамам', img: 'images/mama-2.jpg' },
  { name: 'Шляпа №301 · Волна', cat: 'mama', catLabel: 'Будущим мамам', img: 'images/mama-3.jpg' },
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
          <img src="${l.img}" alt="${l.name} — прокат образов для фотосессий в Дубае" loading="lazy">
        </div>
        <div class="gcard__body">
          <span class="gcard__cat">${l.catLabel}</span>
          <h3>${l.name}</h3>
          <p class="gcard__price">Аренда <span>· цена по запросу</span></p>
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
    desert: 'Съёмка в пустыне',
    city: 'Архитектурная съёмка',
    evening: 'Вечерний образ',
    mama: 'Съёмка для будущих мам',
    fullservice: 'Фотосессия под ключ',
  };

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const data = new FormData(form);
    const text = encodeURIComponent(
      `Здравствуйте! Меня зовут ${data.get('name')}.\n` +
      `Телефон: ${data.get('phone')}\n` +
      `Дата съёмки: ${data.get('date')}\n` +
      `Тип съёмки: ${typeLabels[data.get('type')] || data.get('type')}\n` +
      `Комментарий: ${data.get('message') || '—'}`
    );
    if (note) note.textContent = 'Спасибо! Открываем WhatsApp для завершения заявки...';
    window.open(`https://wa.me/971500000000?text=${text}`, '_blank');
    form.reset();
  });
}
