const DRD_LANG_KEY = 'drd_lang';

const DRD_I18N = {
  ru: {
    'nav.catalog': 'Каталог',
    'nav.how': 'Как это работает',
    'nav.pricing': 'Цены',
    'nav.reviews': 'Отзывы',
    'nav.contact': 'Контакты',
    'nav.book': 'Забронировать образ',
    'topbar.location': 'Дубай, ОАЭ · Доставка по всему городу',

    'hero.eyebrow': 'Студия аренды образов №1 в Дубае',
    'hero.title': 'Безупречный образ<br> на любой вечер',
    'hero.text': 'Вечерние и коктейльные платья, аксессуары и украшения от мировых брендов — в аренду с примеркой, стилистом и доставкой по Дубаю.',
    'hero.cta1': 'Смотреть каталог',
    'hero.cta2': 'Записаться на примерку',
    'hero.stat1': 'образов в наличии',
    'hero.stat2': 'довольных клиенток',
    'hero.stat3': 'средний рейтинг',

    'about.eyebrow': 'О студии',
    'about.title': 'Роскошь, доступная на один вечер',
    'about.text': 'DressRentDubai — это шоурум премиальных вечерних образов в центре Дубая. Мы работаем с оригинальными коллекциями от Elie Saab, Zuhair Murad, Cavalli и локальных дизайнерских брендов ОАЭ. Каждое платье проходит химчистку и проверку качества перед каждой примеркой.',
    'about.li1': 'Персональный подбор образа под мероприятие',
    'about.li2': 'Примерка в шоуруме или у вас дома',
    'about.li3': 'Аксессуары, украшения и клатчи в комплекте',
    'about.li4': 'Страховка от мелких повреждений включена',
    'about.card1': '✨ Стилист поможет с выбором',
    'about.card2': '🚗 Доставка за 2 часа',

    'catalog.eyebrow': 'Каталог',
    'catalog.title': 'Подберите образ по тегам',
    'catalog.search': 'Поиск по названию или тегу...',
    'catalog.all': 'Все',
    'catalog.empty': 'Ничего не найдено. Попробуйте другой тег или сбросьте фильтры.',
    'catalog.emptyCatalog': 'Каталог пока пуст — загляните позже, мы добавляем новые образы каждую неделю.',
    'catalog.perEvening': '/ вечер',
    'catalog.instaHint': 'Тег на карточке — это и хэштег в Instagram: ищите ещё больше образов по нему у нас в профиле.',

    'how.eyebrow': 'Процесс',
    'how.title': 'Как это работает',
    'how.step1.title': 'Выберите образ',
    'how.step1.text': 'Просмотрите каталог онлайн или посетите шоурум в Downtown Dubai.',
    'how.step2.title': 'Забронируйте дату',
    'how.step2.text': 'Укажите дату мероприятия — мы зарезервируем образ и подберём размер.',
    'how.step3.title': 'Примерьте и получите',
    'how.step3.text': 'Доставим образ к вам или пригласим на примерку в студию.',
    'how.step4.title': 'Верните после вечера',
    'how.step4.text': 'Заберём образ в течение 24 часов после мероприятия. Химчистка — за наш счёт.',

    'pricing.eyebrow': 'Тарифы',
    'pricing.title': 'Прозрачные цены аренды',
    'pricing.plan1.title': 'Коктейльный',
    'pricing.plan1.price': 'от 350 AED',
    'pricing.plan1.li1': 'Коктейльные платья',
    'pricing.plan1.li2': 'Аксессуары по запросу',
    'pricing.plan1.li3': 'Примерка в шоуруме',
    'pricing.plan2.badge': 'Популярный',
    'pricing.plan2.title': 'Вечерний',
    'pricing.plan2.price': 'от 650 AED',
    'pricing.plan2.li1': 'Дизайнерские вечерние платья',
    'pricing.plan2.li2': 'Клатч и украшения в комплекте',
    'pricing.plan2.li3': 'Доставка и забор включены',
    'pricing.plan2.li4': 'Консультация стилиста',
    'pricing.plan3.title': 'Премиум',
    'pricing.plan3.price': 'от 1 200 AED',
    'pricing.plan3.li1': 'Образы от кутюр-брендов',
    'pricing.plan3.li2': 'Полный образ: платье + украшения + сумка',
    'pricing.plan3.li3': 'Персональный стилист на дом',
    'pricing.perEvening': '/ вечер',
    'pricing.select': 'Выбрать',
    'pricing.note': 'Залог возвращается сразу после осмотра образа при возврате. Аренда на несколько дней — по индивидуальному расчёту.',

    'reviews.eyebrow': 'Отзывы',
    'reviews.title': 'Что говорят наши клиентки',
    'reviews.r1': '"Нашла идеальный образ для гала-ужина за один день. Стилист помог подобрать украшения — выглядело как с обложки журнала!"',
    'reviews.r1.cite': '— Алина, Dubai Marina',
    'reviews.r2': '"Доставили платье прямо в отель за 2 часа до мероприятия. Сервис на высшем уровне, обязательно вернусь ещё."',
    'reviews.r2.cite': '— Мария, JBR',
    'reviews.r3': '"Аренда обошлась в разы дешевле покупки, а платье было в идеальном состоянии. Спасибо команде DressRentDubai!"',
    'reviews.r3.cite': '— Сара, Downtown Dubai',

    'booking.eyebrow': 'Бронирование',
    'booking.title': 'Оставьте заявку на подбор образа',
    'booking.text': 'Заполните форму — стилист свяжется с вами в течение 30 минут и предложит подходящие варианты.',
    'booking.addr': '📍 Downtown Dubai, Boulevard Plaza, шоурум 12',
    'booking.phone': '📞 +971 50 000 0000',
    'booking.email': '✉️ hello@dressrentdubai.com',
    'booking.hours': '🕙 Ежедневно, 10:00 – 22:00',
    'booking.name': 'Имя',
    'booking.namePh': 'Ваше имя',
    'booking.phoneLabel': 'Телефон / WhatsApp',
    'booking.date': 'Дата мероприятия',
    'booking.type': 'Тип образа',
    'booking.typeEvening': 'Вечернее платье',
    'booking.typeCocktail': 'Коктейльное платье',
    'booking.typeWedding': 'Образ гостьи на свадьбу',
    'booking.typeAccessories': 'Только аксессуары',
    'booking.message': 'Комментарий',
    'booking.messagePh': 'Размер, цвет, повод...',
    'booking.submit': 'Отправить заявку',
    'booking.sending': 'Спасибо! Открываем WhatsApp для завершения заявки...',

    'footer.rights': 'Все права защищены.',
    'footer.admin': 'Вход для администратора',
  },

  en: {
    'nav.catalog': 'Catalog',
    'nav.how': 'How it works',
    'nav.pricing': 'Pricing',
    'nav.reviews': 'Reviews',
    'nav.contact': 'Contact',
    'nav.book': 'Book a Look',
    'topbar.location': 'Dubai, UAE · City-wide delivery',

    'hero.eyebrow': 'Dubai\'s #1 Look Rental Studio',
    'hero.title': 'The Perfect Look<br> for Every Evening',
    'hero.text': 'Evening and cocktail dresses, accessories and jewelry from world-renowned brands — rent with a fitting, a stylist, and delivery across Dubai.',
    'hero.cta1': 'Browse Catalog',
    'hero.cta2': 'Book a Fitting',
    'hero.stat1': 'looks in stock',
    'hero.stat2': 'happy clients',
    'hero.stat3': 'average rating',

    'about.eyebrow': 'About the Studio',
    'about.title': 'Luxury Made Available for One Night',
    'about.text': 'DressRentDubai is a premium evening-look showroom in the heart of Dubai. We work with original collections from Elie Saab, Zuhair Murad, Cavalli and local UAE designer brands. Every look is dry-cleaned and quality-checked before each fitting.',
    'about.li1': 'Personal look selection for your event',
    'about.li2': 'Fitting at our showroom or at your home',
    'about.li3': 'Accessories, jewelry and clutches included',
    'about.li4': 'Minor-damage insurance included',
    'about.card1': '✨ A stylist will help you choose',
    'about.card2': '🚗 Delivery in 2 hours',

    'catalog.eyebrow': 'Catalog',
    'catalog.title': 'Find Your Look by Tag',
    'catalog.search': 'Search by name or tag...',
    'catalog.all': 'All',
    'catalog.empty': 'Nothing found. Try another tag or clear the filters.',
    'catalog.emptyCatalog': 'The catalog is empty right now — check back soon, we add new looks every week.',
    'catalog.perEvening': '/ evening',
    'catalog.instaHint': 'The tag on each card is also our Instagram hashtag — find more looks like it on our profile.',

    'how.eyebrow': 'Process',
    'how.title': 'How It Works',
    'how.step1.title': 'Pick a Look',
    'how.step1.text': 'Browse the catalog online or visit our showroom in Downtown Dubai.',
    'how.step2.title': 'Book a Date',
    'how.step2.text': 'Tell us your event date — we\'ll reserve the look and match your size.',
    'how.step3.title': 'Try It On',
    'how.step3.text': 'We deliver to you or invite you for a fitting at the studio.',
    'how.step4.title': 'Return After the Event',
    'how.step4.text': 'We pick it up within 24 hours after your event. Dry cleaning is on us.',

    'pricing.eyebrow': 'Pricing',
    'pricing.title': 'Transparent Rental Rates',
    'pricing.plan1.title': 'Cocktail',
    'pricing.plan1.price': 'from 350 AED',
    'pricing.plan1.li1': 'Cocktail dresses',
    'pricing.plan1.li2': 'Accessories on request',
    'pricing.plan1.li3': 'Showroom fitting',
    'pricing.plan2.badge': 'Most Popular',
    'pricing.plan2.title': 'Evening',
    'pricing.plan2.price': 'from 650 AED',
    'pricing.plan2.li1': 'Designer evening dresses',
    'pricing.plan2.li2': 'Clutch and jewelry included',
    'pricing.plan2.li3': 'Delivery and pickup included',
    'pricing.plan2.li4': 'Stylist consultation',
    'pricing.plan3.title': 'Premium',
    'pricing.plan3.price': 'from 1,200 AED',
    'pricing.plan3.li1': 'Couture-brand looks',
    'pricing.plan3.li2': 'Full look: dress + jewelry + bag',
    'pricing.plan3.li3': 'Personal stylist at your location',
    'pricing.perEvening': '/ evening',
    'pricing.select': 'Choose',
    'pricing.note': 'The deposit is refunded right after the look is inspected on return. Multi-day rentals are quoted individually.',

    'reviews.eyebrow': 'Reviews',
    'reviews.title': 'What Our Clients Say',
    'reviews.r1': '"Found the perfect look for a gala dinner in one day. The stylist helped me pick jewelry — I looked like I stepped out of a magazine!"',
    'reviews.r1.cite': '— Alina, Dubai Marina',
    'reviews.r2': '"They delivered the dress straight to my hotel two hours before the event. Top-notch service, I\'ll definitely be back."',
    'reviews.r2.cite': '— Maria, JBR',
    'reviews.r3': '"Renting cost a fraction of buying, and the dress was in perfect condition. Thank you, DressRentDubai team!"',
    'reviews.r3.cite': '— Sarah, Downtown Dubai',

    'booking.eyebrow': 'Booking',
    'booking.title': 'Request a Look Selection',
    'booking.text': 'Fill out the form — a stylist will contact you within 30 minutes with suitable options.',
    'booking.addr': '📍 Downtown Dubai, Boulevard Plaza, showroom 12',
    'booking.phone': '📞 +971 50 000 0000',
    'booking.email': '✉️ hello@dressrentdubai.com',
    'booking.hours': '🕙 Daily, 10:00 AM – 10:00 PM',
    'booking.name': 'Name',
    'booking.namePh': 'Your name',
    'booking.phoneLabel': 'Phone / WhatsApp',
    'booking.date': 'Event date',
    'booking.type': 'Look type',
    'booking.typeEvening': 'Evening dress',
    'booking.typeCocktail': 'Cocktail dress',
    'booking.typeWedding': 'Wedding guest look',
    'booking.typeAccessories': 'Accessories only',
    'booking.message': 'Comment',
    'booking.messagePh': 'Size, color, occasion...',
    'booking.submit': 'Send Request',
    'booking.sending': 'Thank you! Opening WhatsApp to complete your request...',

    'footer.rights': 'All rights reserved.',
    'footer.admin': 'Admin sign-in',
  },
};

function drdGetLang() {
  return localStorage.getItem(DRD_LANG_KEY) === 'en' ? 'en' : 'ru';
}

function drdSetLang(lang) {
  localStorage.setItem(DRD_LANG_KEY, lang);
}

function drdT(key) {
  const lang = drdGetLang();
  return (DRD_I18N[lang] && DRD_I18N[lang][key]) || DRD_I18N.ru[key] || key;
}

function drdApplyI18n(root = document) {
  const lang = drdGetLang();
  root.documentElement ? null : null;
  document.documentElement.lang = lang;

  root.querySelectorAll('[data-i18n]').forEach(el => {
    el.innerHTML = drdT(el.getAttribute('data-i18n'));
  });
  root.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    el.setAttribute('placeholder', drdT(el.getAttribute('data-i18n-placeholder')));
  });
  root.querySelectorAll('[data-lang-btn]').forEach(el => {
    el.textContent = lang === 'ru' ? 'EN' : 'RU';
  });
}
