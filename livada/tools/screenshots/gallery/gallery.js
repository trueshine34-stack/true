/* ==========================================================================
   Livada — galeria de capturi de ecran (screenshots/index.html)
   JS simplu, fără framework-uri și fără module: funcționează și din file://
   (datele vin din manifest.js → window.MANIFEST) și prin http.
   ========================================================================== */
(function () {
  'use strict';

  var M = window.MANIFEST;
  var KEY = 'livada-shots:v1';
  var doc = document.documentElement;
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };
  var norm = function (s) { return String(s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/ё/g, 'е'); };
  var uniq = function (a) { return a.filter(function (x, i) { return a.indexOf(x) === i; }); };
  var byOrder = function (order) { return function (a, b) { return (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99); }; };

  // ─── iconițe (SVG inline, stroke = currentColor) ─────────────────────────
  var IC = {
    search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    sun: '<circle cx="12" cy="12" r="3.8"/><path d="M12 2.8v1.9M12 19.3v1.9M2.8 12h1.9M19.3 12h1.9M5.5 5.5l1.3 1.3M17.2 17.2l1.3 1.3M5.5 18.5l1.3-1.3M17.2 6.8l1.3-1.3"/>',
    moon: '<path d="M19.5 14.6A7.8 7.8 0 0 1 9.4 4.5a7.8 7.8 0 1 0 10.1 10.1z"/>',
    auto: '<circle cx="12" cy="12" r="8.2"/><path d="M12 3.8a8.2 8.2 0 0 1 0 16.4z" fill="currentColor" stroke="none"/>',
    desktop: '<rect x="2.8" y="4.2" width="18.4" height="12.3" rx="2"/><path d="M8.5 20h7M12 16.5V20"/>',
    mobile: '<rect x="6.8" y="2.8" width="10.4" height="18.4" rx="2.6"/><path d="M10.6 5.6h2.8"/>',
    close: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    prev: '<path d="M14.5 18l-6-6 6-6"/>',
    next: '<path d="M9.5 6l6 6-6 6"/>',
    external: '<path d="M14 4h6v6M20 4l-8.5 8.5M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"/>',
    download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>',
    copy: '<rect x="8.5" y="8.5" width="11.5" height="11.5" rx="2"/><path d="M15.5 8.5v-3A1.5 1.5 0 0 0 14 4H5.5A1.5 1.5 0 0 0 4 5.5V14a1.5 1.5 0 0 0 1.5 1.5h3"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    split: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M12 4.5v15"/>',
    fit: '<path d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15"/>',
    zoom: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2M11 8.2v5.6M8.2 11h5.6"/>',
    expand: '<path d="M14 4h6v6M10 20H4v-6M20 4l-6.5 6.5M4 20l6.5-6.5"/>',
    keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2.2"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14.2h8"/>',
    up: '<path d="M12 19V5M5.5 11.5L12 5l6.5 6.5"/>',
    layers: '<path d="M12 3.5l8.5 4.5-8.5 4.5L3.5 8z"/><path d="M3.5 12.2l8.5 4.5 8.5-4.5"/><path d="M3.5 16.2l8.5 4.5 8.5-4.5" opacity=".55"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1.6"/><rect x="13" y="4" width="7" height="7" rx="1.6"/><rect x="4" y="13" width="7" height="7" rx="1.6"/><rect x="13" y="13" width="7" height="7" rx="1.6"/>',
    gridS: '<rect x="3.5" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="16" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="3.5" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="16" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="3.5" y="16" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="16" width="4.5" height="4.5" rx="1"/><rect x="16" y="16" width="4.5" height="4.5" rx="1"/>',
    gridL: '<rect x="4" y="4" width="16" height="16" rx="2.2"/>',
    photo: '<rect x="3" y="4.5" width="18" height="15" rx="2.2"/><circle cx="9" cy="10" r="1.8"/><path d="M21 16l-5-5-8.5 8.5"/>',
    pages: '<rect x="3.5" y="4" width="17" height="16" rx="2.2"/><path d="M3.5 8.5h17M7 6.3h.01M9.5 6.3h.01"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>',
    alert: '<path d="M12 4.5l8.5 15h-17z"/><path d="M12 10.5v4M12 17h.01"/>'
  };
  var icon = function (n, cls) { return '<svg class="ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' + IC[n] + '</svg>'; };
  var MARK = '<svg class="mark" viewBox="0 0 32 32" aria-hidden="true" focusable="false"><rect width="14" height="14" rx="3.5"/><rect y="18" width="14" height="14" rx="3.5"/><rect x="18" y="18" width="14" height="14" rx="3.5"/><path class="mark__leaf" d="M32 0A14 14 0 0 1 18 14 14 14 0 0 1 32 0Z"/></svg>';

  // ─── textele interfeței (RU implicit, EN la cerere) ──────────────────────
  var T = {
    ru: {
      title: 'Livada — скриншоты', skip: 'Перейти к скриншотам', sub: 'Скриншоты всех страниц',
      h1: 'Все страницы <em>Livada</em> в одном месте',
      updated: 'Снято', uiLang: 'Язык интерфейса', mode: { auto: 'Тема интерфейса: как в системе', light: 'Тема интерфейса: светлая', dark: 'Тема интерфейса: тёмная' },
      keys: 'Горячие клавиши', tabs: { pages: 'Страницы', compare: 'Сравнение', photos: 'Фото товаров' },
      search: 'Поиск: название, адрес, состояние…', searchPh: 'Поиск: товар, цвет, артикул…', searchBtn: 'Поиск', clear: 'Очистить',
      lang: 'Язык', theme: 'Тема', device: 'Устройство', section: 'Раздел', category: 'Категория', all: 'Все', size: 'Размер карточек',
      sizes: { s: 'Мелкие', m: 'Средние', l: 'Крупные' },
      light: 'Светлая', dark: 'Тёмная', desktop: 'Десктоп', mobile: 'Мобильный',
      langName: { ro: 'румынский', en: 'английский', ru: 'русский' },
      st: { pages: 'страниц и состояний', shots: 'скриншотов', langs: 'языки', themes: 'темы', devices: 'устройства', photos: 'фото товаров' },
      lead: function (c) { return 'Каждая страница магазина и админки снята на ' + c.langs + ', в светлой и тёмной теме, на компьютере (1440 px) и телефоне (390 px). Кликните по карточке, чтобы открыть скриншот целиком.'; },
      howto: 'Как пользоваться',
      tips: [
        'Переключатели <b>Язык · Тема · Устройство</b> вверху меняют вариант для всех карточек сразу, а маленькие переключатели на карточке — только для неё.',
        'Во вкладке <b>Сравнение</b> выберите, что сравнивать (язык, тему, устройство) — варианты встанут рядом.',
        'В просмотре нажмите <b>Сравнить</b>, чтобы открыть два варианта рядом с общей прокруткой.',
        'Адрес страницы меняется вместе с открытым скриншотом — ссылкой можно поделиться.'
      ],
      compareBy: 'Сравнить по', dimsHint: 'выберите одно или несколько', open: 'Открыть', openCompare: 'Открыть рядом',
      fit: 'Вписать', actual: '100%', zoomFit: 'Вписать по ширине (Z)', zoomActual: 'Реальный размер 100% (Z)',
      original: 'Открыть оригинал в новой вкладке (O)', download: 'Скачать', copyPath: 'Копировать путь к файлу (P)', copyLink: 'Копировать ссылку на этот вид',
      copied: 'Путь скопирован', linkCopied: 'Ссылка скопирована', copyFail: 'Не удалось скопировать',
      close: 'Закрыть (Esc)', prev: 'Предыдущая (←)', next: 'Следующая (→)', prevPhoto: 'Предыдущее фото (←)', nextPhoto: 'Следующее фото (→)',
      split: 'Сравнить', splitOff: 'Без сравнения', splitTitle: 'Показать рядом другой вариант (C)', splitWith: 'Рядом:',
      shown: 'показан', missing: 'нет такого варианта', noShot: 'Нет скриншота', loading: 'Загрузка…', loadErr: 'Не удалось загрузить изображение',
      empty: 'Ничего не найдено', emptyHint: 'Попробуйте изменить запрос или выбрать другой раздел.', reset: 'Сбросить фильтры',
      noManifest: 'Не найден manifest.js', noManifestHint: 'Положите index.html, assets/ и manifest.js в одну папку со скриншотами (img/, thumb/, photos/) и откройте index.html ещё раз.',
      totop: 'Наверх', sections: 'Разделы', pageOf: 'из', variants: 'вариантов', photoOf: 'фото',
      kb: [
        ['/', 'поиск'], ['← →', 'предыдущая / следующая страница'], ['↑ ↓', 'перебрать варианты страницы'],
        ['L', 'сменить язык'], ['T', 'сменить тему'], ['D', 'десктоп ↔ мобильный'], ['C', 'сравнение рядом'],
        ['Z', 'вписать ↔ 100%'], ['O', 'открыть оригинал'], ['P', 'копировать путь'], ['Пробел', 'прокрутка скриншота'], ['Esc', 'закрыть'], ['?', 'эта подсказка']
      ],
      kbNote: 'L, T и D работают и на сетке — меняют вариант у всех карточек сразу.',
      footer: 'Скриншоты Livada', pl: { shots: ['скриншот', 'скриншота', 'скриншотов'], pages: ['страница', 'страницы', 'страниц'], photos: ['фото', 'фото', 'фото'], products: ['товар', 'товара', 'товаров'] }
    },
    en: {
      title: 'Livada — screenshots', skip: 'Skip to screenshots', sub: 'Screenshots of every page',
      h1: 'Every <em>Livada</em> page in one place',
      updated: 'Captured', uiLang: 'Interface language', mode: { auto: 'Interface theme: system', light: 'Interface theme: light', dark: 'Interface theme: dark' },
      keys: 'Keyboard shortcuts', tabs: { pages: 'Pages', compare: 'Compare', photos: 'Product photos' },
      search: 'Search: title, URL, state…', searchPh: 'Search: product, colour, slug…', searchBtn: 'Search', clear: 'Clear',
      lang: 'Language', theme: 'Theme', device: 'Device', section: 'Section', category: 'Category', all: 'All', size: 'Card size',
      sizes: { s: 'Small', m: 'Medium', l: 'Large' },
      light: 'Light', dark: 'Dark', desktop: 'Desktop', mobile: 'Mobile',
      langName: { ro: 'Romanian', en: 'English', ru: 'Russian' },
      st: { pages: 'pages & states', shots: 'screenshots', langs: 'languages', themes: 'themes', devices: 'devices', photos: 'product photos' },
      lead: function (c) { return 'Every store and admin page captured in ' + c.langs + ', in light and dark themes, on desktop (1440 px) and phone (390 px). Click a card to open the full-page screenshot.'; },
      howto: 'How to use',
      tips: [
        'The <b>Language · Theme · Device</b> switches at the top change every card at once; the small switches on a card change only that card.',
        'In the <b>Compare</b> tab pick what to compare (language, theme, device) and the variants line up side by side.',
        'In the viewer press <b>Compare</b> to see two variants side by side with synced scrolling.',
        'The address follows the open screenshot, so you can share the link.'
      ],
      compareBy: 'Compare by', dimsHint: 'pick one or more', open: 'Open', openCompare: 'Open side by side',
      fit: 'Fit', actual: '100%', zoomFit: 'Fit to width (Z)', zoomActual: 'Actual size 100% (Z)',
      original: 'Open original in a new tab (O)', download: 'Download', copyPath: 'Copy file path (P)', copyLink: 'Copy link to this view',
      copied: 'Path copied', linkCopied: 'Link copied', copyFail: 'Could not copy',
      close: 'Close (Esc)', prev: 'Previous (←)', next: 'Next (→)', prevPhoto: 'Previous photo (←)', nextPhoto: 'Next photo (→)',
      split: 'Compare', splitOff: 'No comparison', splitTitle: 'Show another variant side by side (C)', splitWith: 'Beside:',
      shown: 'showing', missing: 'variant not captured', noShot: 'No screenshot', loading: 'Loading…', loadErr: 'Could not load the image',
      empty: 'Nothing found', emptyHint: 'Try another search or pick a different section.', reset: 'Reset filters',
      noManifest: 'manifest.js not found', noManifestHint: 'Put index.html, assets/ and manifest.js in the same folder as the screenshots (img/, thumb/, photos/) and open index.html again.',
      totop: 'Back to top', sections: 'Sections', pageOf: 'of', variants: 'variants', photoOf: 'photos',
      kb: [
        ['/', 'search'], ['← →', 'previous / next page'], ['↑ ↓', 'cycle through the page variants'],
        ['L', 'switch language'], ['T', 'switch theme'], ['D', 'desktop ↔ mobile'], ['C', 'side-by-side compare'],
        ['Z', 'fit ↔ 100%'], ['O', 'open original'], ['P', 'copy path'], ['Space', 'scroll the screenshot'], ['Esc', 'close'], ['?', 'this help']
      ],
      kbNote: 'L, T and D also work on the grid — they switch every card at once.',
      footer: 'Livada screenshots', pl: { shots: ['screenshot', 'screenshots'], pages: ['page', 'pages'], photos: ['photo', 'photos'], products: ['product', 'products'] }
    }
  };
  var CAT = { iphone: ['iPhone', 'iPhone'], mac: ['Mac', 'Mac'], ipad: ['iPad', 'iPad'], watch: ['Watch', 'Watch'], airpods: ['AirPods', 'AirPods'],
    'tv-home': ['TV и дом', 'TV & Home'], accesorii: ['Аксессуары', 'Accessories'], accessories: ['Аксессуары', 'Accessories'] };

  // ─── starea (salvată în localStorage doar pentru comoditate) ─────────────
  var saved = {};
  try { saved = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { saved = {}; }
  var S = {
    ui: saved.ui === 'en' ? 'en' : 'ru', mode: saved.mode || 'auto', tab: saved.tab || 'pages',
    lang: saved.lang, theme: saved.theme, device: saved.device, group: saved.group || 'all', density: saved.density || 'm',
    dims: Array.isArray(saved.dims) && saved.dims.length ? saved.dims : ['lang'], pcat: saved.pcat || 'all', howto: saved.howto !== false, q: '', pq: ''
  };
  function save() {
    try { localStorage.setItem(KEY, JSON.stringify({ ui: S.ui, mode: S.mode, tab: S.tab, lang: S.lang, theme: S.theme, device: S.device, group: S.group, density: S.density, dims: S.dims, pcat: S.pcat, howto: S.howto })); } catch (e) { /* stocare indisponibilă */ }
  }
  var t = function (k) { return T[S.ui][k]; };
  var L = function (o) { return o == null ? '' : typeof o === 'string' ? o : (o[S.ui] || o.ru || o.en || o.ro || ''); };
  var plural = function (n, key) {
    var f = T[S.ui].pl[key];
    if (S.ui === 'en') return f[n === 1 ? 0 : 1];
    var m10 = n % 10, m100 = n % 100;
    return f[m10 === 1 && m100 !== 11 ? 0 : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 1 : 2];
  };
  var fmt = function (n) { return Number(n).toLocaleString(S.ui === 'en' ? 'en-GB' : 'ru-RU'); };

  // ─── datele: paginile (un id = o pagină / o stare) și variantele lor ─────
  var LANG_ORDER = ['ro', 'en', 'ru'], THEME_ORDER = ['light', 'dark'], DEVICE_ORDER = ['desktop', 'mobile'];
  var DIMS = ['lang', 'theme', 'device'];
  var vk = function (s) { return s.lang + '|' + s.theme + '|' + s.device; };
  var shots = (M && M.shots) || [];
  var photos = (M && M.photos) || [];
  var pages = [], byId = Object.create(null);
  shots.forEach(function (s) {
    var p = byId[s.id];
    if (!p) { p = byId[s.id] = { id: s.id, group: s.group || 'other', section: s.section, title: s.title || s.id, state: s.state, url: s.url || '', v: {}, lang: [], theme: [], device: [], n: 0, ord: pages.length }; pages.push(p); }
    p.v[vk(s)] = s; p.n++;
    DIMS.forEach(function (d) { if (p[d].indexOf(s[d]) < 0) p[d].push(s[d]); });
  });
  var VALS = {
    lang: uniq(shots.map(function (s) { return s.lang; })).sort(byOrder(LANG_ORDER)),
    theme: uniq(shots.map(function (s) { return s.theme; })).sort(byOrder(THEME_ORDER)),
    device: uniq(shots.map(function (s) { return s.device; })).sort(byOrder(DEVICE_ORDER))
  };
  pages.forEach(function (p) { p.lang.sort(byOrder(LANG_ORDER)); p.theme.sort(byOrder(THEME_ORDER)); p.device.sort(byOrder(DEVICE_ORDER)); });
  var GROUPS = ((M && M.groups) || []).filter(function (g) { return pages.some(function (p) { return p.group === g.id; }); });
  uniq(pages.map(function (p) { return p.group; })).forEach(function (id) { if (!GROUPS.some(function (g) { return g.id === id; })) GROUPS.push({ id: id, title: id }); });
  var gIdx = function (id) { return GROUPS.findIndex(function (g) { return g.id === id; }); };
  var gTitle = function (id) { var g = GROUPS[gIdx(id)]; return g ? L(g.title) : id; };
  var secKey = function (p) { return p.group + '|' + (p.section ? (p.section.ru || p.section.en || L(p.section)) : ''); };
  var secFirst = {};
  pages.forEach(function (p) { var k = secKey(p); if (!(k in secFirst)) secFirst[k] = p.ord; });
  pages.sort(function (a, b) { return gIdx(a.group) - gIdx(b.group) || secFirst[secKey(a)] - secFirst[secKey(b)] || a.ord - b.ord; });
  pages.forEach(function (p) {
    var parts = [p.id, p.url, p.group, gTitle(p.group)];
    [p.title, p.state, p.section].forEach(function (o) { if (o) parts.push(typeof o === 'string' ? o : Object.keys(o).map(function (k) { return o[k]; }).join(' ')); });
    (GROUPS[gIdx(p.group)] && typeof GROUPS[gIdx(p.group)].title === 'object') && parts.push(GROUPS[gIdx(p.group)].title.ru, GROUPS[gIdx(p.group)].title.en);
    p.hay = norm(parts.join(' '));
  });
  DIMS.forEach(function (d) { if (VALS[d].indexOf(S[d]) < 0) S[d] = VALS[d][0]; });
  if (S.group !== 'all' && gIdx(S.group) < 0) S.group = 'all';
  S.dims = S.dims.filter(function (d) { return DIMS.indexOf(d) >= 0; }); if (!S.dims.length) S.dims = ['lang'];
  if (['pages', 'compare', 'photos'].indexOf(S.tab) < 0 || (S.tab === 'photos' && !photos.length)) S.tab = 'pages';

  // fotografii: categorie → produs → fotografii
  var catOrder = [], prods = [], prodBy = Object.create(null);
  photos.forEach(function (ph, i) {
    ph.i = i;
    if (catOrder.indexOf(ph.category) < 0) catOrder.push(ph.category);
    var pr = prodBy[ph.slug];
    if (!pr) { pr = prodBy[ph.slug] = { slug: ph.slug, name: ph.product || ph.slug, category: ph.category, items: [], ord: prods.length }; prods.push(pr); }
    pr.items.push(ph);
    ph.hay = norm([ph.product, ph.slug, ph.variant, ph.category, catTitle(ph.category)].join(' '));
  });
  prods.sort(function (a, b) { return catOrder.indexOf(a.category) - catOrder.indexOf(b.category) || a.ord - b.ord; });
  if (S.pcat !== 'all' && catOrder.indexOf(S.pcat) < 0) S.pcat = 'all';
  function catTitle(id) {
    var ext = M && M.photoCategories && M.photoCategories.find && M.photoCategories.find(function (c) { return c.id === id; });
    if (ext) return L(ext.title);
    var c = CAT[id];
    return c ? c[S.ui === 'en' ? 1 : 0] : String(id || '—').replace(/^\w/, function (x) { return x.toUpperCase(); });
  }

  // varianta dorită pentru o pagină (global + suprascrierea de pe card) și cea mai apropiată existentă
  var overrides = Object.create(null);
  function want(p) { var o = overrides[p.id] || {}; return { lang: o.lang || S.lang, theme: o.theme || S.theme, device: o.device || S.device }; }
  function resolve(p, w) {
    var hit = p.v[vk(w)];
    if (hit) return hit;
    var best = null, bs = -1e9;
    Object.keys(p.v).forEach(function (k) {
      var s = p.v[k];
      var sc = (s.lang === w.lang) * 100 + (s.device === w.device) * 30 + (s.theme === w.theme) * 10 - (LANG_ORDER.indexOf(s.lang) + 1 || 9);
      if (sc > bs) { bs = sc; best = s; }
    });
    return best;
  }
  var dimLabel = function (d, v) { return d === 'lang' ? String(v).toUpperCase() : (t(v) || v); };
  var dimIcon = function (d, v) { return d === 'theme' ? icon(v === 'dark' ? 'moon' : 'sun') : d === 'device' ? icon(v === 'mobile' ? 'mobile' : 'desktop') : ''; };
  var variantLabel = function (s) { return [dimLabel('lang', s.lang), dimLabel('theme', s.theme), dimLabel('device', s.device)].join(' · '); };
  var thumbH = function (s) { return s.w && s.h ? Math.max(40, Math.min(640, Math.round(s.h * 480 / s.w))) : 640; };
  var cssW = function (s) { return s.w ? (s.device === 'mobile' ? Math.round(s.w / 2) : s.w) : (s.device === 'mobile' ? 390 : 1440); };
  function hashFor(p, s, extra) { return 'id=' + encodeURIComponent(p.id) + '&lang=' + s.lang + '&theme=' + s.theme + '&device=' + s.device + (extra || ''); }

  // evidențierea căutării (doar când normalizarea păstrează lungimea textului)
  var tokens = [];
  function hl(text) {
    text = String(text || '');
    if (!tokens.length) return esc(text);
    var n = norm(text);
    if (n.length !== text.length) return esc(text);
    var marks = [];
    tokens.forEach(function (tk) { var i = n.indexOf(tk); while (i >= 0 && tk) { marks.push([i, i + tk.length]); i = n.indexOf(tk, i + tk.length); } });
    if (!marks.length) return esc(text);
    marks.sort(function (a, b) { return a[0] - b[0]; });
    var out = '', pos = 0;
    marks.forEach(function (m) { if (m[0] < pos) m[0] = pos; if (m[1] <= m[0]) return; out += esc(text.slice(pos, m[0])) + '<mark>' + esc(text.slice(m[0], m[1])) + '</mark>'; pos = m[1]; });
    return out + esc(text.slice(pos));
  }

  // ─── elementele de bază ──────────────────────────────────────────────────
  var elTop = $('#top'), elBar = $('#bar'), elMain = $('#main'), elToc = $('#toc'), elFoot = $('#foot'), elLb = $('#lb'), elKeys = $('#keys'), elToast = $('#toast'), elTotop = $('#totop');

  function applyMode() {
    if (S.mode === 'light' || S.mode === 'dark') doc.dataset.theme = S.mode; else delete doc.dataset.theme;
  }

  // ─── antet + statistici ──────────────────────────────────────────────────
  function renderTop() {
    doc.lang = S.ui;
    document.title = t('title');
    $('.skip').textContent = t('skip');
    var gen = M && M.generated ? new Date(M.generated) : null;
    var genTxt = gen && !isNaN(gen) ? gen.toLocaleString(S.ui === 'en' ? 'en-GB' : 'ru-RU', { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
    var langNames = VALS.lang.map(function (l) { return t('langName')[l] || l; });
    var langsTxt = langNames.length > 1 ? langNames.slice(0, -1).join(', ') + (S.ui === 'en' ? ' and ' : ' и ') + langNames[langNames.length - 1] : langNames.join('');
    var stat = function (num, lab, extra) { return '<div class="stat"><dt>' + lab + '</dt><dd><b>' + num + '</b>' + (extra ? '<span>' + extra + '</span>' : '') + '</dd></div>'; };
    var nm = { auto: 'auto', light: 'sun', dark: 'moon' };
    elTop.innerHTML =
      '<div class="wrap top__row">' +
        '<a class="brand" href="#" data-act="home" aria-label="Livada — ' + esc(t('sub')) + '">' + MARK + '<span class="brand__txt"><span class="brand__name">Livada</span><span class="brand__sub">' + esc(t('sub')) + '</span></span></a>' +
        '<div class="top__tools">' +
          '<div class="seg seg--xs" role="group" aria-label="' + esc(t('uiLang')) + '">' +
            ['ru', 'en'].map(function (l) { return '<button type="button" data-ui="' + l + '" aria-pressed="' + (S.ui === l) + '">' + l.toUpperCase() + '</button>'; }).join('') + '</div>' +
          '<button type="button" class="iconbtn" data-act="mode" title="' + esc(t('mode')[S.mode]) + '" aria-label="' + esc(t('mode')[S.mode]) + '">' + icon(nm[S.mode] || 'auto') + '</button>' +
          '<button type="button" class="iconbtn hide-touch" data-act="keys" title="' + esc(t('keys')) + ' (?)" aria-label="' + esc(t('keys')) + '">' + icon('keyboard') + '</button>' +
        '</div>' +
      '</div>' +
      '<div class="wrap hero">' +
        (genTxt ? '<p class="eyebrow"><span class="dot"></span>' + esc(t('updated')) + ' ' + esc(genTxt) + '</p>' : '') +
        '<h1 class="hero__title">' + t('h1') + '</h1>' +
        '<p class="hero__lead">' + esc(t('lead')({ langs: langsTxt })) + '</p>' +
        '<dl class="stats">' +
          stat(fmt(pages.length), t('st').pages) +
          stat(fmt(shots.length), t('st').shots) +
          stat(VALS.lang.map(function (l) { return l.toUpperCase(); }).join(' · '), t('st').langs) +
          stat(VALS.theme.map(function (v) { return dimIcon('theme', v); }).join(''), t('st').themes, VALS.theme.map(function (v) { return t(v); }).join(' · ')) +
          stat(VALS.device.map(function (v) { return dimIcon('device', v); }).join(''), t('st').devices, VALS.device.map(function (v) { return t(v); }).join(' · ')) +
          (photos.length ? stat(fmt(photos.length), t('st').photos, fmt(prods.length) + ' ' + plural(prods.length, 'products')) : '') +
        '</dl>' +
        '<details class="howto"' + (S.howto ? ' open' : '') + '><summary>' + icon('info') + '<span>' + esc(t('howto')) + '</span>' + icon('next', 'howto__chev') + '</summary>' +
          '<div class="howto__body"><ul class="howto__tips">' + t('tips').map(function (x) { return '<li>' + x + '</li>'; }).join('') + '</ul>' +
          '<ul class="kb">' + t('kb').map(function (k) { return '<li>' + k[0].split(' ').map(function (x) { return '<kbd>' + esc(x) + '</kbd>'; }).join('') + '<span>' + esc(k[1]) + '</span></li>'; }).join('') + '</ul></div>' +
        '</details>' +
      '</div>';
    elFoot.innerHTML = '<div class="wrap foot__in"><span class="brand brand--sm">' + MARK + '<span>Livada</span></span><span>' + esc(t('footer')) + (genTxt ? ' · ' + esc(genTxt) : '') + '</span><span class="mono">manifest.json · ' + fmt(shots.length) + ' ' + plural(shots.length, 'shots') + '</span></div>';
    elTotop.innerHTML = icon('up');
    elTotop.setAttribute('aria-label', t('totop')); elTotop.title = t('totop');
  }

  // ─── bara lipicioasă: file, căutare, comutatoare, secțiuni ───────────────
  function seg(k, items, cur, label, cls) {
    return '<div class="seg ' + (cls || '') + '" role="group" aria-label="' + esc(label) + '">' + items.map(function (it) {
      return '<button type="button" data-k="' + k + '" data-v="' + esc(it.v) + '" aria-pressed="' + (String(it.v) === String(cur)) + '" title="' + esc(it.title || it.label) + '"' + (it.disabled ? ' disabled' : '') + '>' + (it.html || esc(it.label)) + '</button>';
    }).join('') + '</div>';
  }
  function dimSeg(d, cls) {
    return '<div class="ctl"><span class="ctl__lab">' + esc(t(d)) + '</span>' + seg(d, VALS[d].map(function (v) {
      return { v: v, label: dimLabel(d, v), title: d === 'lang' ? (t('langName')[v] || v) : dimLabel(d, v), html: d === 'lang' ? esc(v.toUpperCase()) : dimIcon(d, v) + '<span class="seg__txt">' + esc(dimLabel(d, v)) + '</span>' };
    }), S[d], t(d), cls) + '</div>';
  }
  function renderBar() {
    var isPh = S.tab === 'photos';
    var tabs = [['pages', 'pages', pages.length], ['compare', 'split', null]];
    if (photos.length) tabs.push(['photos', 'photo', photos.length]);
    elBar.innerHTML =
      '<div class="wrap bar__in">' +
        '<div class="bar__row">' +
          '<div class="tabs" role="tablist" aria-label="Livada">' + tabs.map(function (x) {
            return '<button type="button" role="tab" id="tab-' + x[0] + '" aria-controls="main" aria-selected="' + (S.tab === x[0]) + '" data-tab="' + x[0] + '">' + icon(x[1]) + '<span>' + esc(t('tabs')[x[0]]) + '</span>' + (x[2] != null ? '<span class="tabs__n">' + fmt(x[2]) + '</span>' : '') + '</button>';
          }).join('') + '</div>' +
          '<label class="search" id="search">' + icon('search') + '<span class="visually-hidden">' + esc(t('searchBtn')) + '</span>' +
            '<input type="search" id="q" autocomplete="off" spellcheck="false" placeholder="' + esc(isPh ? t('searchPh') : t('search')) + '" value="' + esc(isPh ? S.pq : S.q) + '">' +
            '<kbd class="search__kbd">/</kbd><button type="button" class="search__x" data-act="clear" aria-label="' + esc(t('clear')) + '" title="' + esc(t('clear')) + '">' + icon('close') + '</button></label>' +
          '<div class="bar__ctl">' +
            (isPh ? '' : dimSeg('lang', 'seg--lang') + dimSeg('theme') + dimSeg('device')) +
            '<div class="ctl ctl--size"><span class="ctl__lab">' + esc(t('size')) + '</span>' + seg('density', [['s', 'gridS'], ['m', 'grid'], ['l', 'gridL']].map(function (x) { return { v: x[0], label: t('sizes')[x[0]], html: icon(x[1]) }; }), S.density, t('size'), 'seg--icon') + '</div>' +
          '</div>' +
        '</div>' +
        '<div class="bar__row bar__row--chips">' +
          (S.tab === 'compare' ? '<div class="dims" role="group" aria-label="' + esc(t('compareBy')) + '"><span class="dims__lab">' + icon('split') + esc(t('compareBy')) + '</span>' + DIMS.filter(function (d) { return VALS[d].length > 1; }).map(function (d) {
            return '<button type="button" class="dim" data-dim="' + d + '" aria-pressed="' + (S.dims.indexOf(d) >= 0) + '">' + esc(t(d)) + '<span>' + VALS[d].map(function (v) { return dimLabel(d, v); }).join(' / ') + '</span></button>';
          }).join('') + '</div>' : '') +
          '<div class="chips" id="chips" role="group" aria-label="' + esc(isPh ? t('category') : t('section')) + '"></div>' +
        '</div>' +
      '</div>';
    renderChips();
  }
  function renderChips() {
    var box = $('#chips'); if (!box) return;
    var items;
    if (S.tab === 'photos') {
      var tk = toks(S.pq);
      var cnt = function (c) { return photos.filter(function (ph) { return (c === 'all' || ph.category === c) && match(ph.hay, tk); }).length; };
      items = [{ v: 'all', label: t('all'), n: cnt('all') }].concat(catOrder.map(function (c) { return { v: c, label: catTitle(c), n: cnt(c) }; }));
      box.innerHTML = items.map(function (it) { return chip('pcat', it, S.pcat); }).join('');
    } else {
      var tq = toks(S.q);
      var c2 = function (g) { return pages.filter(function (p) { return (g === 'all' || p.group === g) && match(p.hay, tq); }).length; };
      items = [{ v: 'all', label: t('all'), n: c2('all') }].concat(GROUPS.map(function (g) { return { v: g.id, label: L(g.title), n: c2(g.id) }; }));
      box.innerHTML = items.map(function (it) { return chip('group', it, S.group); }).join('');
    }
  }
  function chip(k, it, cur) {
    return '<button type="button" class="chip" data-k="' + k + '" data-v="' + esc(it.v) + '" aria-pressed="' + (it.v === cur) + '"' + (!it.n && it.v !== cur ? ' data-empty' : '') + '>' + esc(it.label) + '<span class="chip__n">' + fmt(it.n) + '</span></button>';
  }
  function syncBar() {
    $$('[data-k]', elBar).forEach(function (b) { b.setAttribute('aria-pressed', String(S[b.dataset.k]) === b.dataset.v); });
    $$('[data-dim]', elBar).forEach(function (b) { b.setAttribute('aria-pressed', S.dims.indexOf(b.dataset.dim) >= 0); });
  }

  // ─── filtrare ────────────────────────────────────────────────────────────
  function toks(q) { return norm(q).split(/\s+/).filter(Boolean); }
  function match(hay, tk) { for (var i = 0; i < tk.length; i++) if (hay.indexOf(tk[i]) < 0) return false; return true; }
  var visible = []; // paginile afișate (ordinea folosită de ← → în vizualizare)
  var visPhotos = [];
  function filtered() {
    var tk = toks(S.q); tokens = tk;
    return pages.filter(function (p) { return (S.group === 'all' || p.group === S.group) && match(p.hay, tk); });
  }
  function sectionsOf(list) {
    var out = [], idx = {};
    list.forEach(function (p) {
      var k = secKey(p);
      if (!(k in idx)) { idx[k] = out.length; out.push({ key: k, group: p.group, title: p.section ? L(p.section) : gTitle(p.group), items: [] }); }
      out[idx[k]].items.push(p);
    });
    return out;
  }

  // ─── randare incrementală (bucăți mici pe cadru, fără blocaje) ───────────
  var job = 0, flushFn = null;
  function schedule(tasks, done) {
    var my = ++job, i = 0;
    function step() {
      if (my !== job) return;
      var t0 = performance.now();
      while (i < tasks.length && performance.now() - t0 < 10) tasks[i++]();
      if (i < tasks.length) requestAnimationFrame(step); else { flushFn = null; done && done(); }
    }
    flushFn = function () { if (my !== job) return; while (i < tasks.length) tasks[i++](); flushFn = null; done && done(); };
    step();
  }
  var flush = function () { if (flushFn) flushFn(); };

  // încărcarea leneșă a miniaturilor (IntersectionObserver + loading="lazy")
  var io = 'IntersectionObserver' in window ? new IntersectionObserver(function (ents) {
    ents.forEach(function (e) { if (e.isIntersecting) { var im = e.target; io.unobserve(im); if (im.dataset.src && !im.getAttribute('src')) im.src = im.dataset.src; } });
  }, { rootMargin: '900px 0px' }) : null;
  function observe(root) {
    $$('img[data-src]:not([src])', root).forEach(function (im) { if (io) io.observe(im); else im.src = im.dataset.src; });
  }
  document.addEventListener('load', function (e) { var im = e.target; if (im.tagName === 'IMG' && im.classList.contains('shot')) im.classList.add('is-in'); }, true);
  document.addEventListener('error', function (e) { var im = e.target; if (im.tagName === 'IMG' && im.classList.contains('shot')) { im.classList.add('is-err'); im.parentNode && im.parentNode.classList.add('is-err'); } }, true);

  // ─── cardurile ───────────────────────────────────────────────────────────
  function frameHTML(s, alt, eager) {
    var img = '<img class="shot" ' + (eager ? 'src' : 'data-src') + '="' + esc(s.thumb) + '" alt="' + esc(alt) + '" width="480" height="' + thumbH(s) + '" decoding="async" loading="lazy" draggable="false">';
    if (s.device === 'mobile') return '<div class="dev dev--phone is-' + s.theme + '"><div class="dev__screen"><span class="dev__island" aria-hidden="true"></span>' + img + '</div></div>';
    return '<div class="dev dev--desk is-' + s.theme + '"><div class="dev__bar" aria-hidden="true"><i></i><i></i><i></i><span class="dev__url">' + esc((s.lang && s.lang !== 'ro' && s.group !== 'admin' && s.group !== 'email' ? '/' + s.lang : '') + (s.url || '/')) + '</span></div><div class="dev__screen">' + img + '</div></div>';
  }
  function stageHTML(p, s, w, eager) {
    var miss = DIMS.filter(function (d) { return w && s[d] !== w[d]; });
    var alt = L(p.title) + (p.state ? ' — ' + L(p.state) : '') + ' (' + variantLabel(s) + ')';
    return '<a class="stage" href="#' + hashFor(p, s) + '" data-open="' + esc(p.id) + '" data-file="' + esc(s.file) + '" aria-label="' + esc(t('open') + ': ' + alt) + '">' +
      frameHTML(s, alt, eager) +
      (miss.length ? '<span class="stage__note" title="' + esc(t('missing')) + ': ' + miss.map(function (d) { return dimLabel(d, w[d]); }).join(', ') + '">' + icon('info') + esc(t('shown')) + ' ' + miss.map(function (d) { return dimLabel(d, s[d]); }).join(' · ') + '</span>' : '') +
      '<span class="stage__zoom" aria-hidden="true">' + icon('expand') + '</span></a>';
  }
  function miniToggles(p, s) {
    return DIMS.map(function (d) {
      var vals = d === 'lang' ? p.lang : VALS[d];
      if (d === 'lang' && vals.length < 2) return '<span class="mini mini--one" title="' + esc(t('langName')[s.lang] || s.lang) + '">' + esc(s.lang.toUpperCase()) + '</span>';
      return '<div class="mini" role="group" aria-label="' + esc(t(d)) + '">' + vals.map(function (v) {
        var has = p[d].indexOf(v) >= 0;
        return '<button type="button" data-card="' + d + '" data-v="' + v + '" aria-pressed="' + (s[d] === v) + '"' + (has ? '' : ' disabled') + ' title="' + esc(d === 'lang' ? (t('langName')[v] || v) : dimLabel(d, v)) + (has ? '' : ' — ' + t('missing')) + '">' + (d === 'lang' ? esc(v.toUpperCase()) : dimIcon(d, v)) + '</button>';
      }).join('') + '</div>';
    }).join('') + '<span class="card__n" title="' + esc(p.n + ' ' + plural(p.n, 'shots')) + '">' + icon('layers') + p.n + '</span>';
  }
  function cardHTML(p) {
    var w = want(p), s = resolve(p, w);
    return '<article class="card" data-id="' + esc(p.id) + '">' + stageHTML(p, s, w) +
      '<div class="card__body"><h3 class="card__title">' + hl(L(p.title)) + '</h3>' +
      (p.state ? '<p class="card__state">' + hl(L(p.state)) + '</p>' : '') +
      '<p class="card__url" title="' + esc(p.id) + '">' + hl(p.url || p.id) + '</p>' +
      '<div class="card__tools">' + miniToggles(p, s) + '</div></div></article>';
  }
  function nearView(el, m) { var r = el.getBoundingClientRect(); return r.bottom > -(m || 200) && r.top < innerHeight + (m || 200); }
  function updateCard(card) {
    var p = byId[card.dataset.id]; if (!p) return;
    var w = want(p), s = resolve(p, w);
    var old = $('.stage', card);
    var tools = $('.card__tools', card); if (tools) tools.innerHTML = miniToggles(p, s);
    if (old && old.dataset.file === s.file) { var tmp0 = document.createElement('div'); tmp0.innerHTML = stageHTML(p, s, w); var n0 = $('.stage__note', tmp0), o0 = $('.stage__note', old); if (o0) o0.remove(); if (n0) old.insertBefore(n0, $('.stage__zoom', old)); return; }
    var tmp = document.createElement('div'); tmp.innerHTML = stageHTML(p, s, w);
    var ns = tmp.firstChild, im = $('img.shot', ns);
    if (!old || !nearView(card)) { old ? old.replaceWith(ns) : card.prepend(ns); observe(ns); return; }
    // vizibil: preîncărcăm, apoi schimbăm fără „pâlpâire”
    var swapped = false;
    var swap = function () { if (swapped) return; swapped = true; im.classList.add('is-in'); ns.classList.add('is-swap'); if (old.parentNode) old.replaceWith(ns); };
    im.src = im.dataset.src;
    if (im.decode) im.decode().then(swap, swap); else im.onload = im.onerror = swap;
    setTimeout(swap, 900);
  }
  function updateAllCards() { $$('.card', elMain).forEach(updateCard); $$('.crow', elMain).forEach(updateRow); }

  // ─── vizualizarea „Pagini” ───────────────────────────────────────────────
  function renderPages() {
    var list = visible = filtered();
    if (!list.length) return renderEmpty();
    var secs = sectionsOf(list);
    elMain.className = 'main main--' + S.tab + ' d-' + S.density;
    elMain.innerHTML = secs.map(function (sec, i) {
      var gt = gTitle(sec.group);
      return '<section class="sec" id="s-' + i + '" aria-labelledby="s-' + i + '-h"><header class="sec__head">' +
        (gt && gt !== sec.title ? '<p class="sec__eyebrow">' + esc(gt) + '</p>' : '') +
        '<h2 id="s-' + i + '-h" class="sec__title">' + esc(sec.title) + '</h2><span class="sec__n">' + fmt(sec.items.length) + '</span></header>' +
        '<div class="' + (S.tab === 'compare' ? 'rows' : 'grid') + '"></div></section>';
    }).join('');
    var tasks = [];
    secs.forEach(function (sec, i) {
      var box = $('#s-' + i + ' > div', elMain), step = S.tab === 'compare' ? 3 : 12;
      for (var j = 0; j < sec.items.length; j += step) (function (chunk) {
        tasks.push(function () { box.insertAdjacentHTML('beforeend', chunk.map(S.tab === 'compare' ? rowHTML : cardHTML).join('')); observe(box); });
      })(sec.items.slice(j, j + step));
    });
    renderToc(secs.map(function (sec, i) { return { id: 's-' + i, group: sec.group, title: sec.title, n: sec.items.length }; }));
    schedule(tasks);
  }

  // ─── vizualizarea „Comparație” ───────────────────────────────────────────
  function combos(p) {
    var base = { lang: S.lang, theme: S.theme, device: S.device };
    var b0 = resolve(p, base); base = { lang: b0.lang, theme: b0.theme, device: b0.device };
    var out = [base];
    S.dims.forEach(function (d) {
      var vals = p[d]; if (vals.length < 2) return;
      var next = [];
      out.forEach(function (c) { vals.forEach(function (v) { var x = Object.assign({}, c); x[d] = v; next.push(x); }); });
      out = next;
    });
    return out;
  }
  function rowHTML(p) {
    var cs = combos(p);
    var dimsUsed = S.dims.filter(function (d) { return p[d].length > 1; });
    return '<article class="crow" data-id="' + esc(p.id) + '"><header class="crow__head"><div class="crow__txt"><h3 class="card__title">' + hl(L(p.title)) + '</h3>' +
      '<p class="crow__meta">' + (p.state ? '<span>' + hl(L(p.state)) + '</span>' : '') + '<span class="card__url">' + hl(p.url || p.id) + '</span></p></div>' +
      (cs.length > 1 && dimsUsed.length ? '<button type="button" class="btn btn--sm" data-split="' + esc(p.id) + '">' + icon('split') + '<span>' + esc(t('openCompare')) + '</span></button>' : '') + '</header>' +
      '<div class="crow__panels n-' + Math.min(cs.length, 8) + '">' + panelsHTML(p, cs, dimsUsed) + '</div></article>';
  }
  function panelsHTML(p, cs, dimsUsed) {
    return cs.map(function (c) {
      var s = p.v[vk(c)];
      var cap = DIMS.map(function (d) { var on = dimsUsed.indexOf(d) >= 0; return '<span class="' + (on ? 'on' : '') + '">' + (d === 'lang' ? '' : dimIcon(d, c[d])) + esc(dimLabel(d, c[d])) + '</span>'; }).join('');
      return '<figure class="panel">' + (s ? stageHTML(p, s, null) : '<div class="stage stage--none">' + icon('alert') + '<span>' + esc(t('noShot')) + '</span></div>') + '<figcaption>' + cap + '</figcaption></figure>';
    }).join('');
  }
  function updateRow(row) {
    var p = byId[row.dataset.id]; if (!p) return;
    var box = $('.crow__panels', row), cs = combos(p);
    box.className = 'crow__panels n-' + Math.min(cs.length, 8);
    box.innerHTML = panelsHTML(p, cs, S.dims.filter(function (d) { return p[d].length > 1; }));
    $$('img.shot', box).forEach(function (im) { if (nearView(row, 400)) im.src = im.dataset.src; });
    observe(box);
  }

  // ─── vizualizarea „Fotografii produse” ───────────────────────────────────
  function renderPhotos() {
    var tk = toks(S.pq); tokens = tk;
    elMain.className = 'main main--photos d-' + S.density;
    var cats = catOrder.filter(function (c) { return S.pcat === 'all' || c === S.pcat; });
    visPhotos = [];
    var secs = [];
    cats.forEach(function (c) {
      var ps = prods.filter(function (pr) { return pr.category === c; }).map(function (pr) {
        var whole = match(norm(pr.name + ' ' + pr.slug + ' ' + c + ' ' + catTitle(c)), tk);
        return { pr: pr, items: pr.items.filter(function (ph) { return whole || match(ph.hay, tk); }) };
      }).filter(function (x) { return x.items.length; });
      if (ps.length) secs.push({ cat: c, prods: ps, n: ps.reduce(function (a, x) { return a + x.items.length; }, 0) });
      ps.forEach(function (x) { visPhotos.push.apply(visPhotos, x.items); });
    });
    if (!secs.length) return renderEmpty();
    elMain.innerHTML = secs.map(function (sec, i) {
      return '<section class="sec" id="s-' + i + '" aria-labelledby="s-' + i + '-h"><header class="sec__head"><p class="sec__eyebrow">' + esc(t('tabs').photos) + '</p>' +
        '<h2 id="s-' + i + '-h" class="sec__title">' + esc(catTitle(sec.cat)) + '</h2><span class="sec__n">' + fmt(sec.prods.length) + ' ' + plural(sec.prods.length, 'products') + ' · ' + fmt(sec.n) + ' ' + plural(sec.n, 'photos') + '</span></header><div class="prods"></div></section>';
    }).join('');
    var tasks = [];
    secs.forEach(function (sec, i) {
      var box = $('#s-' + i + ' > .prods', elMain);
      for (var j = 0; j < sec.prods.length; j += 4) (function (chunk) {
        tasks.push(function () { box.insertAdjacentHTML('beforeend', chunk.map(prodHTML).join('')); observe(box); });
      })(sec.prods.slice(j, j + 4));
    });
    renderToc(secs.map(function (sec, i) { return { id: 's-' + i, title: catTitle(sec.cat), n: sec.n }; }));
    schedule(tasks);
  }
  function prodHTML(x) {
    var pr = x.pr, vars = uniq(x.items.map(function (ph) { return ph.variant; }));
    return '<article class="prod"><header class="prod__head"><h3 class="prod__name">' + hl(pr.name) + '</h3><span class="prod__slug">' + hl(pr.slug) + '</span>' +
      '<span class="prod__n">' + fmt(x.items.length) + ' ' + plural(x.items.length, 'photos') + (vars.length > 1 ? ' · ' + vars.length + ' ' + (S.ui === 'en' ? 'variants' : ruVariants(vars.length)) : '') + '</span></header>' +
      '<div class="tiles">' + x.items.map(function (ph) {
        var alt = pr.name + (ph.variant ? ' — ' + ph.variant : '');
        return '<a class="tile" href="#photo=' + encodeURIComponent(ph.file) + '" data-ph="' + ph.i + '" aria-label="' + esc(t('open') + ': ' + alt) + '"><span class="tile__img"><img class="shot" data-src="' + esc(ph.thumb) + '" alt="' + esc(alt) + '" width="480" height="480" decoding="async" loading="lazy" draggable="false"></span>' +
          '<span class="tile__cap">' + hl(ph.variant || '—') + '</span></a>';
      }).join('') + '</div></article>';
  }
  function ruVariants(n) { var m10 = n % 10, m100 = n % 100; return m10 === 1 && m100 !== 11 ? 'вариант' : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'варианта' : 'вариантов'; }

  function renderEmpty() {
    elMain.className = 'main main--' + S.tab;
    elMain.innerHTML = '<div class="empty"><div class="empty__ic">' + icon('search') + '</div><h2>' + esc(t('empty')) + '</h2><p>' + esc(t('emptyHint')) + '</p><button type="button" class="btn" data-act="reset">' + esc(t('reset')) + '</button></div>';
    renderToc([]);
  }

  // ─── cuprinsul lateral (ecrane late) + scrollspy ─────────────────────────
  var spy = null;
  function renderToc(items) {
    if (spy) spy.disconnect();
    if (items.length < 2) { elToc.innerHTML = ''; elToc.hidden = true; doc.classList.remove('has-toc'); return; }
    elToc.hidden = false; doc.classList.add('has-toc');
    var last = null, h = '<p class="toc__lab">' + esc(t('sections')) + '</p><ol>', per = {};
    items.forEach(function (it) { if (it.group) per[it.group] = (per[it.group] || 0) + 1; });
    items.forEach(function (it) {
      if (it.group && per[it.group] === 1 && it.title === gTitle(it.group)) { if (last !== null) { h += '</ol></li>'; last = null; } h += '<li class="toc__solo"><a href="#' + it.id + '" data-toc="' + it.id + '"><span>' + esc(it.title) + '</span><span class="toc__n">' + fmt(it.n) + '</span></a></li>'; return; }
      if (it.group && it.group !== last) { if (last !== null) h += '</ol></li>'; h += '<li class="toc__g"><span>' + esc(gTitle(it.group)) + '</span><ol>'; last = it.group; }
      h += '<li><a href="#' + it.id + '" data-toc="' + it.id + '"><span>' + esc(it.title) + '</span><span class="toc__n">' + fmt(it.n) + '</span></a></li>';
    });
    if (last !== null) h += '</ol></li>';
    elToc.innerHTML = h + '</ol>';
    if (!('IntersectionObserver' in window)) return;
    spy = new IntersectionObserver(function (ents) {
      ents.forEach(function (e) {
        if (!e.isIntersecting) return;
        $$('a[data-toc]', elToc).forEach(function (a) { a.classList.toggle('is-on', a.dataset.toc === e.target.id); });
      });
    }, { rootMargin: '-' + (barH() + 10) + 'px 0px -70% 0px' });
    $$('.sec', elMain).forEach(function (s) { spy.observe(s); });
  }
  function barH() { return elBar.getBoundingClientRect().height || 0; }

  function renderMain() {
    if (!M) return;
    if (S.tab === 'photos') renderPhotos(); else renderPages();
  }

  // ─── evenimente: bara, cardurile, cuprinsul ──────────────────────────────
  function setTab(tab) {
    if (tab === S.tab) return;
    S.tab = tab; save(); renderBar(); renderMain();
    var top = elTop.offsetHeight; if (scrollY > top) scrollTo(0, top);
    replaceHash(tab === 'pages' ? '' : 'tab=' + tab);
  }
  function setDim(d, v) {
    if (S[d] === v) return;
    S[d] = v; overrides = Object.create(null); save(); syncBar(); updateAllCards();
  }
  function cycleGlobal(d) { var vs = VALS[d]; setDim(d, vs[(vs.indexOf(S[d]) + 1) % vs.length]); toast(t(d) + ': ' + (d === 'lang' ? (t('langName')[S[d]] || S[d]) : dimLabel(d, S[d]))); }

  document.addEventListener('click', function (e) {
    var b = e.target.closest('button, a');
    if (!b) return;
    if (elLb.contains(b) || elKeys.contains(b)) return; // tratate separat
    var ds = b.dataset;
    if (ds.ui) { if (S.ui !== ds.ui) { S.ui = ds.ui; save(); renderAll(); } return; }
    if (ds.tab) return setTab(ds.tab);
    if (ds.act === 'mode') { S.mode = { auto: 'light', light: 'dark', dark: 'auto' }[S.mode] || 'auto'; save(); applyMode(); renderTop(); toast(t('mode')[S.mode]); return; }
    if (ds.act === 'keys') return openKeys();
    if (ds.act === 'home') { e.preventDefault(); scrollTo({ top: 0, behavior: 'smooth' }); return; }
    if (ds.act === 'clear') { var q = $('#q'); q.value = ''; onSearch(''); q.focus(); return; }
    if (ds.act === 'reset') { S.q = ''; S.pq = ''; S.group = 'all'; S.pcat = 'all'; save(); renderBar(); renderMain(); return; }
    if (ds.k) {
      if (DIMS.indexOf(ds.k) >= 0) return setDim(ds.k, ds.v);
      if (ds.k === 'density') { S.density = ds.v; save(); syncBar(); elMain.classList.remove('d-s', 'd-m', 'd-l'); elMain.classList.add('d-' + S.density); return; }
      if (ds.k === 'group' || ds.k === 'pcat') { S[ds.k] = ds.v; save(); syncBar(); renderMain(); var top = elTop.offsetHeight; if (scrollY > top) scrollTo(0, top); return; }
    }
    if (ds.dim) {
      var i = S.dims.indexOf(ds.dim);
      if (i >= 0 && S.dims.length > 1) S.dims.splice(i, 1); else if (i < 0) S.dims.push(ds.dim);
      S.dims.sort(byOrder(DIMS)); save(); syncBar(); $$('.crow', elMain).forEach(updateRow); return;
    }
    if (ds.card) {
      var card = b.closest('.card'), p = byId[card.dataset.id];
      var o = overrides[p.id] = Object.assign({}, want(p)); o[ds.card] = ds.v;
      // dacă exact combinația nu există, păstrăm valoarea aleasă și luăm cea mai apropiată variantă
      updateCard(card); return;
    }
    if (ds.open) { if (e.metaKey || e.ctrlKey || e.shiftKey || e.button) return; e.preventDefault(); var pp = byId[ds.open]; var f = ds.file; openPage(pp, findByFile(pp, f) || resolve(pp, want(pp)), b.closest('.crow') ? S.dims.filter(function (d) { return pp[d].length > 1; })[0] : null, b); return; }
    if (ds.split) { var ps = byId[ds.split]; openPage(ps, resolve(ps, combos(ps)[0]), S.dims.filter(function (d) { return ps[d].length > 1; })[0], b); return; }
    if (ds.ph != null && b.classList.contains('tile')) { if (e.metaKey || e.ctrlKey || e.shiftKey) return; e.preventDefault(); openPhoto(photos[+ds.ph], b); return; }
    if (ds.toc) { e.preventDefault(); flush(); var sec = document.getElementById(ds.toc); if (sec) scrollTo({ top: sec.getBoundingClientRect().top + scrollY - barH() - 12, behavior: 'smooth' }); return; }
  });
  function findByFile(p, f) { for (var k in p.v) if (p.v[k].file === f) return p.v[k]; return null; }

  var qTimer = 0;
  function onSearch(v) {
    if (S.tab === 'photos') S.pq = v; else S.q = v;
    $('#search') && $('#search').classList.toggle('has-q', !!v);
    clearTimeout(qTimer);
    qTimer = setTimeout(function () { renderChips(); renderMain(); }, 120);
  }
  document.addEventListener('input', function (e) { if (e.target.id === 'q') onSearch(e.target.value); });
  document.addEventListener('toggle', function (e) { if (e.target.classList && e.target.classList.contains('howto')) { S.howto = e.target.open; save(); } }, true);

  // ─── vizualizatorul (lightbox) ───────────────────────────────────────────
  var LB = { open: false, kind: null, p: null, s: null, split: null, zoom: 'fit', ph: null, opener: null, pushed: false, ratio: 0 };
  function otherVal(p, d, s) {
    var vals = p[d]; if (vals.length < 2) return null;
    var cand = vals[(vals.indexOf(s[d]) + 1) % vals.length];
    var w = { lang: s.lang, theme: s.theme, device: s.device }; w[d] = cand;
    return p.v[vk(w)] || resolve(p, w);
  }
  function openPage(p, s, split, opener) {
    if (!p || !s) return;
    LB.kind = 'page'; LB.p = p; LB.s = s; LB.ph = null;
    LB.split = split && p[split] && p[split].length > 1 ? split : null;
    if (visible.indexOf(p) < 0) { var f = filtered(); visible = f.indexOf(p) >= 0 ? f : pages.slice(); }
    showLb(opener);
    renderLb(true);
  }
  function openPhoto(ph, opener) {
    if (!ph) return;
    LB.kind = 'photo'; LB.ph = ph; LB.p = null; LB.split = null;
    if (visPhotos.indexOf(ph) < 0) visPhotos = photos.slice();
    showLb(opener);
    renderLb(true);
  }
  function showLb(opener) {
    if (!LB.open) {
      LB.open = true; LB.opener = opener || document.activeElement; LB.zoom = 'fit';
      elLb.hidden = false; doc.classList.add('is-locked');
      requestAnimationFrame(function () { elLb.classList.add('is-open'); });
      LB.pushed = pushHash(lbHash());
    }
  }
  function closeLb(fromPop) {
    if (!LB.open) return;
    LB.open = false; elLb.classList.remove('is-open'); doc.classList.remove('is-locked');
    elLb.hidden = true; elLb.innerHTML = '';
    if (!fromPop) { if (LB.pushed) { LB.pushed = false; ignorePop = true; history.back(); } else replaceHash(S.tab === 'pages' ? '' : 'tab=' + S.tab); }
    LB.pushed = false;
    if (LB.kind === 'page' && LB.p) { // sincronizăm cardul cu ultima variantă văzută
      var card = elMain.querySelector('.card[data-id="' + cssEsc(LB.p.id) + '"]');
      if (card && LB.s) { var w = want(LB.p); if (vk(w) !== vk(LB.s)) { overrides[LB.p.id] = { lang: LB.s.lang, theme: LB.s.theme, device: LB.s.device }; updateCard(card); } }
      var target = card ? $('.stage', card) : null;
      if (target) { target.focus({ preventScroll: true }); if (!nearView(target, -60)) target.scrollIntoView({ block: 'center' }); }
      else if (LB.opener && LB.opener.focus) LB.opener.focus({ preventScroll: true });
    } else if (LB.opener && document.contains(LB.opener)) LB.opener.focus({ preventScroll: true });
  }
  function cssEsc(s) { return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&'); }
  function lbHash() {
    if (LB.kind === 'photo') return 'photo=' + encodeURIComponent(LB.ph.file);
    return hashFor(LB.p, LB.s, LB.split ? '&cmp=' + LB.split : '');
  }

  function lbBtn(act, ic, title, extra) { return '<button type="button" class="lbbtn" data-lb="' + act + '" title="' + esc(title) + '" aria-label="' + esc(title) + '"' + (extra || '') + '>' + icon(ic) + '</button>'; }
  function renderLb(first) {
    var fa = document.activeElement, fsel = null;
    if (!first && fa && elLb.contains(fa)) fsel = fa.dataset.k ? '[data-k="' + fa.dataset.k + '"][data-v="' + fa.dataset.v + '"]' : fa.dataset.lb ? '[data-lb="' + fa.dataset.lb + '"]' + (fa.dataset.i ? '[data-i="' + fa.dataset.i + '"]' : '') : null;
    var isPh = LB.kind === 'photo';
    var list = isPh ? visPhotos : visible, cur = isPh ? LB.ph : LB.p, idx = list.indexOf(cur);
    var title, eyebrow, sub, sw = '', file, dlName;
    if (isPh) {
      var ph = LB.ph;
      eyebrow = t('tabs').photos + ' · ' + catTitle(ph.category);
      title = esc(ph.product || ph.slug);
      sub = '<span>' + esc(ph.variant || '') + '</span><code>' + esc(ph.slug) + '</code>';
      file = ph.file; dlName = 'livada-' + ph.file.split('/').slice(-2).join('-');
    } else {
      var p = LB.p, s = LB.s;
      eyebrow = gTitle(p.group) + (p.section && L(p.section) !== gTitle(p.group) ? ' · ' + L(p.section) : '');
      title = esc(L(p.title)) + (p.state ? '<span class="lb__state"> — ' + esc(L(p.state)) + '</span>' : '');
      sub = '<code>' + esc(p.url || p.id) + '</code><span class="lb__dim">' + fmt(s.w || 0) + ' × ' + fmt(s.h || 0) + ' px</span>';
      file = s.file; dlName = 'livada-' + p.id + '-' + s.lang + '-' + s.theme + '-' + s.device + '.webp';
      sw = DIMS.map(function (d) {
        var vals = d === 'lang' ? p.lang : VALS[d];
        return seg('lb-' + d, vals.map(function (v) {
          var has = p[d].indexOf(v) >= 0;
          return { v: v, disabled: !has, label: dimLabel(d, v), title: (d === 'lang' ? (t('langName')[v] || v) : dimLabel(d, v)) + (has ? '' : ' — ' + t('missing')) + ' (' + d.charAt(0).toUpperCase() + ')', html: d === 'lang' ? esc(v.toUpperCase()) : dimIcon(d, v) + '<span class="seg__txt">' + esc(dimLabel(d, v)) + '</span>' };
        }), s[d], t(d), 'seg--dark' + (d === 'lang' ? ' seg--lang' : ''));
      }).join('');
      var opts = DIMS.filter(function (d) { return p[d].length > 1; });
      if (opts.length) sw += '<label class="lbsel" title="' + esc(t('splitTitle')) + '">' + icon('split') + '<span class="visually-hidden">' + esc(t('split')) + '</span><select data-lb="split"><option value="">' + esc(t('split')) + '…</option>' +
        opts.map(function (d) { var o = otherVal(p, d, s); return '<option value="' + d + '"' + (LB.split === d ? ' selected' : '') + '>' + esc(t(d)) + ': ' + esc(dimLabel(d, s[d])) + ' ↔ ' + esc(o ? dimLabel(d, o[d]) : '—') + '</option>'; }).join('') +
        (LB.split ? '<option value="off">' + esc(t('splitOff')) + '</option>' : '') + '</select></label>';
    }
    var pos = idx >= 0 ? fmt(idx + 1) + ' ' + t('pageOf') + ' ' + fmt(list.length) : '';
    var nav = function (dir) { return '<button type="button" class="lb__nav lb__nav--' + dir + '" data-lb="' + dir + '" title="' + esc(isPh ? t(dir + 'Photo') : t(dir)) + '" aria-label="' + esc(isPh ? t(dir + 'Photo') : t(dir)) + '"' + (list.length < 2 ? ' disabled' : '') + '>' + icon(dir) + '</button>'; };
    elLb.className = 'lb' + (LB.open ? ' is-open' : '') + (isPh ? ' lb--photo' : '') + (LB.split ? ' is-split' : '') + (isPh && LB.zoom !== 'fit' ? ' is-zoomed' : '');
    elLb.innerHTML =
      '<header class="lb__top">' +
        '<div class="lb__info"><p class="lb__eyebrow">' + esc(eyebrow) + '</p><h2 id="lb-title" class="lb__title">' + title + '</h2><p class="lb__sub">' + sub + '</p></div>' +
        (sw ? '<div class="lb__sw">' + sw + '</div>' : '') +
        '<div class="lb__act">' +
          lbBtn('zoom', LB.zoom === 'fit' ? 'zoom' : 'fit', LB.zoom === 'fit' ? t('zoomActual') : t('zoomFit')) +
          '<a class="lbbtn" data-lb="orig" href="' + esc(file) + '" target="_blank" rel="noopener" title="' + esc(t('original')) + '" aria-label="' + esc(t('original')) + '">' + icon('external') + '</a>' +
          '<a class="lbbtn" data-lb="dl" href="' + esc(file) + '" download="' + esc(dlName) + '" title="' + esc(t('download')) + '" aria-label="' + esc(t('download')) + '">' + icon('download') + '</a>' +
          lbBtn('copy', 'copy', t('copyPath')) + lbBtn('link', 'link', t('copyLink')) +
          '<span class="lb__sep"></span>' + lbBtn('close', 'close', t('close'), ' data-close') +
        '</div>' +
      '</header>' +
      '<div class="lb__stage">' + nav('prev') + '<div class="lb__panes" id="panes"></div>' + nav('next') + '</div>' +
      '<footer class="lb__foot"><span class="lb__pos">' + pos + '</span><span class="lb__file" title="' + esc(file) + '">' + esc(file) + '</span>' +
        '<span class="lb__keys">' + (isPh ? '<kbd>←</kbd><kbd>→</kbd> ' + esc(t('photoOf')) : '<kbd>←</kbd><kbd>→</kbd><kbd>L</kbd><kbd>T</kbd><kbd>D</kbd><kbd>C</kbd><kbd>Z</kbd>') + ' <kbd>Esc</kbd></span></footer>';
    renderPanes(first);
    var fe = (fsel && $(fsel, elLb)) || ((first || fa === document.body || (fa && !document.contains(fa))) && $('.pane__scroll', elLb));
    if (fe) fe.focus({ preventScroll: true });
  }

  function paneHTML(s, cap) {
    var ratio = s.w && s.h ? s.w + ' / ' + s.h : null;
    return '<section class="pane' + (s.theme === 'dark' ? ' is-dark' : '') + '">' + (cap ? '<p class="pane__cap">' + cap + '</p>' : '') +
      '<div class="pane__scroll" tabindex="0" aria-label="' + esc(variantLabel(s)) + '"><div class="pane__img is-loading" data-file="' + esc(s.file) + '"' + (ratio ? ' style="aspect-ratio:' + ratio + '"' : '') + '>' +
      '<img class="pane__ph" src="' + esc(s.thumb) + '" alt="" aria-hidden="true" draggable="false">' +
      '<img class="pane__full" src="' + esc(s.file) + '" alt="' + esc(L(LB.p.title) + ' — ' + variantLabel(s)) + '" draggable="false">' +
      '<span class="pane__spin" aria-hidden="true"></span></div></div></section>';
  }
  function capFor(s, d) { return DIMS.map(function (x) { return '<span class="' + (x === d ? 'on' : '') + '">' + (x === 'lang' ? '' : dimIcon(x, s[x])) + esc(dimLabel(x, s[x])) + '</span>'; }).join(''); }
  function renderPanes(first) {
    var box = $('#panes', elLb); if (!box) return;
    if (LB.kind === 'photo') {
      var ph = LB.ph, pr = prodBy[ph.slug];
      box.innerHTML = '<section class="pane pane--photo"><div class="pane__scroll" tabindex="0"><div class="pane__img pane__img--photo is-loading">' +
        '<img class="pane__ph" src="' + esc(ph.thumb) + '" alt="" aria-hidden="true"><img class="pane__full" src="' + esc(ph.file) + '" alt="' + esc((ph.product || '') + ' — ' + (ph.variant || '')) + '"><span class="pane__spin" aria-hidden="true"></span></div></div>' +
        (pr && pr.items.length > 1 ? '<div class="strip" role="group">' + pr.items.map(function (x) { return '<button type="button" class="strip__it" data-lb="ph" data-i="' + x.i + '" aria-pressed="' + (x === ph) + '" title="' + esc(x.variant || '') + '"><img src="' + esc(x.thumb) + '" alt="' + esc(x.variant || '') + '" loading="lazy"></button>'; }).join('') + '</div>' : '') + '</section>';
      wirePanes(box); return;
    }
    var s = LB.s, b = LB.split ? otherVal(LB.p, LB.split, s) : null;
    box.innerHTML = paneHTML(s, b ? capFor(s, LB.split) : '') + (b ? paneHTML(b, capFor(b, LB.split)) : '');
    wirePanes(box);
    layoutPanes();
    // păstrăm poziția relativă de derulare la schimbarea variantei „pe loc”
    if (!first && LB.ratio) $$('.pane__scroll', box).forEach(function (sc) { sc.scrollTop = LB.ratio * (sc.scrollHeight - sc.clientHeight); });
    prefetch();
  }
  function wirePanes(box) {
    $$('.pane__img', box).forEach(function (w) {
      var full = $('.pane__full', w);
      var done = function () { w.classList.remove('is-loading'); };
      if (full.complete && full.naturalWidth) done(); else { full.addEventListener('load', done); full.addEventListener('error', function () { w.classList.remove('is-loading'); w.classList.add('is-err'); w.setAttribute('data-err', t('loadErr')); }); }
    });
    var scs = $$('.pane__scroll', box), lock = false;
    scs.forEach(function (sc) {
      sc.addEventListener('scroll', function () {
        var max = sc.scrollHeight - sc.clientHeight; LB.ratio = max > 0 ? sc.scrollTop / max : 0;
        if (scs.length < 2 || lock) return;
        lock = true;
        scs.forEach(function (o) { if (o !== sc) { var m = o.scrollHeight - o.clientHeight; o.scrollTop = LB.ratio * m; var mx = sc.scrollWidth - sc.clientWidth; if (mx > 0) o.scrollLeft = sc.scrollLeft / mx * (o.scrollWidth - o.clientWidth); } });
        requestAnimationFrame(function () { lock = false; });
      }, { passive: true });
    });
  }
  function layoutPanes() {
    if (!LB.open || LB.kind !== 'page') return;
    $$('.pane', elLb).forEach(function (pane, i) {
      var s = i === 0 ? LB.s : otherVal(LB.p, LB.split, LB.s); if (!s) return;
      var w = $('.pane__img', pane), sc = $('.pane__scroll', pane);
      var avail = sc.clientWidth - (innerWidth < 720 ? 16 : 48);
      var px = LB.zoom === 'fit' ? Math.min(avail, cssW(s)) : (s.w || cssW(s));
      w.style.width = Math.max(120, Math.round(px)) + 'px';
      pane.classList.toggle('is-actual', LB.zoom !== 'fit');
    });
  }
  // preîncărcăm variantele vecine și pagina următoare → comutări instantanee
  var pre = {};
  function prefetch() {
    if (LB.kind !== 'page') return;
    var p = LB.p, s = LB.s, list = [];
    ['lang', 'theme'].forEach(function (d) { var o = otherVal(p, d, s); if (o) list.push(o.file); });
    var i = visible.indexOf(p), nx = visible[i + 1], pv = visible[i - 1];
    [nx, pv].forEach(function (q) { if (q) { var r = resolve(q, s); if (r) list.push(r.file); } });
    setTimeout(function () { list.forEach(function (f) { if (!pre[f]) { pre[f] = new Image(); pre[f].decoding = 'async'; pre[f].src = f; } }); }, 350);
  }
  function lbSetVariant(s, keepRatio) {
    if (!s || s === LB.s) return;
    var sc = $('.pane__scroll', elLb);
    if (sc && keepRatio !== false) { var max = sc.scrollHeight - sc.clientHeight; LB.ratio = max > 0 ? sc.scrollTop / max : 0; }
    // preîncărcare scurtă, ca schimbarea „pe loc” să nu clipească
    var img = new Image(), go = false;
    var apply = function () { if (go) return; go = true; LB.s = s; renderLb(false); replaceHash(lbHash()); };
    img.src = s.file;
    if (img.complete && img.naturalWidth) return apply();
    (img.decode ? img.decode() : Promise.reject()).then(apply, apply);
    setTimeout(apply, 450);
  }
  function lbFlip(d) {
    if (LB.kind !== 'page') return;
    var o = otherVal(LB.p, d, LB.s);
    if (o) { lbSetVariant(o); toast(t(d) + ': ' + (d === 'lang' ? (t('langName')[o.lang] || o.lang) : dimLabel(d, o[d]))); }
  }
  function lbCycle(dir) {
    if (LB.kind !== 'page') return;
    var p = LB.p, all = [];
    p.device.forEach(function (dv) { p.lang.forEach(function (l) { p.theme.forEach(function (th) { var s = p.v[l + '|' + th + '|' + dv]; if (s) all.push(s); }); }); });
    var i = all.indexOf(LB.s);
    var n = all[(i + dir + all.length) % all.length];
    if (n && n !== LB.s) { lbSetVariant(n); toast(variantLabel(n)); }
  }
  function lbMove(dir) {
    if (LB.kind === 'photo') {
      var i = visPhotos.indexOf(LB.ph); var n = visPhotos[(i + dir + visPhotos.length) % visPhotos.length];
      if (n) { LB.ph = n; renderLb(false); replaceHash(lbHash()); } return;
    }
    var j = visible.indexOf(LB.p); var q = visible[(j + dir + visible.length) % visible.length];
    if (!q) return;
    LB.p = q; LB.s = resolve(q, { lang: LB.s.lang, theme: LB.s.theme, device: LB.s.device }); LB.ratio = 0;
    if (LB.split && q[LB.split].length < 2) LB.split = null;
    renderLb(false); replaceHash(lbHash());
    var sc = $('.pane__scroll', elLb); if (sc) sc.focus({ preventScroll: true });
  }
  function lbZoom(ev) {
    if (LB.kind === 'photo') { elLb.classList.toggle('is-zoomed'); LB.zoom = elLb.classList.contains('is-zoomed') ? 'actual' : 'fit'; return; }
    var sc = ev ? ev.target.closest('.pane__scroll') : $('.pane__scroll', elLb);
    var w = sc ? $('.pane__img', sc) : null;
    var fx = 0.5, fy = 0, cy = 0, cx = 0;
    if (sc && w) {
      var r = w.getBoundingClientRect(), sr = sc.getBoundingClientRect();
      cx = ev ? ev.clientX - sr.left : sr.width / 2; cy = ev ? ev.clientY - sr.top : sr.height / 2;
      fx = (sr.left + cx - r.left) / r.width; fy = (sr.top + cy - r.top) / r.height;
    }
    LB.zoom = LB.zoom === 'fit' ? 'actual' : 'fit';
    layoutPanes();
    $$('.pane__scroll', elLb).forEach(function (s2) {
      var w2 = $('.pane__img', s2); if (!w2) return;
      s2.scrollTop = Math.max(0, w2.offsetTop + fy * w2.offsetHeight - cy); s2.scrollLeft = Math.max(0, w2.offsetLeft + fx * w2.offsetWidth - cx);
    });
    var zb = $('[data-lb="zoom"]', elLb);
    if (zb) { zb.innerHTML = icon(LB.zoom === 'fit' ? 'zoom' : 'fit'); zb.title = LB.zoom === 'fit' ? t('zoomActual') : t('zoomFit'); zb.setAttribute('aria-label', zb.title); }
  }

  elLb.addEventListener('click', function (e) {
    var b = e.target.closest('[data-lb]');
    if (!b) {
      if (e.target.closest('.pane__img') && !e.target.closest('.strip')) lbZoom(e);
      else if (e.target === elLb || e.target.classList.contains('lb__stage') || e.target.classList.contains('lb__panes')) closeLb();
      return;
    }
    var a = b.dataset.lb;
    if (a === 'close') return closeLb();
    if (a === 'prev') return lbMove(-1);
    if (a === 'next') return lbMove(1);
    if (a === 'zoom') return lbZoom();
    if (a === 'copy') return copy(b.closest('.lb').querySelector('[data-lb="orig"]').getAttribute('href'), t('copied'));
    if (a === 'link') return copy(location.href, t('linkCopied'));
    if (a === 'ph') { LB.ph = photos[+b.dataset.i]; renderLb(false); replaceHash(lbHash()); return; }
  });
  elLb.addEventListener('click', function (e) { var b = e.target.closest('[data-k]'); if (b && /^lb-/.test(b.dataset.k)) { var m = b.dataset.k.slice(3); if (!b.disabled && LB.s[m] !== b.dataset.v) { var w = { lang: LB.s.lang, theme: LB.s.theme, device: LB.s.device }; w[m] = b.dataset.v; lbSetVariant(LB.p.v[vk(w)] || resolve(LB.p, w)); } } });
  elLb.addEventListener('change', function (e) {
    if (e.target.dataset.lb === 'split') { var v = e.target.value; LB.split = v && v !== 'off' ? v : null; renderLb(false); replaceHash(lbHash()); }
  });
  // glisare stânga/dreapta pe telefon (doar la „vizualizare potrivită”)
  var sw0 = null;
  elLb.addEventListener('touchstart', function (e) { if (e.touches.length === 1 && LB.zoom === 'fit') sw0 = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() }; else sw0 = null; }, { passive: true });
  elLb.addEventListener('touchend', function (e) {
    if (!sw0 || e.target.closest('.lb__top, .strip')) return;
    var dx = e.changedTouches[0].clientX - sw0.x, dy = e.changedTouches[0].clientY - sw0.y;
    if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.8 && Date.now() - sw0.t < 600) lbMove(dx < 0 ? 1 : -1);
    sw0 = null;
  }, { passive: true });

  // ─── fereastra cu scurtături ─────────────────────────────────────────────
  function openKeys() {
    elKeys.innerHTML = '<div class="sheet__card"><header><h2 id="keys-title">' + icon('keyboard') + esc(t('keys')) + '</h2>' + '<button type="button" class="iconbtn" data-close aria-label="' + esc(t('close')) + '">' + icon('close') + '</button></header>' +
      '<ul class="kb kb--sheet">' + t('kb').map(function (k) { return '<li>' + k[0].split(' ').map(function (x) { return '<kbd>' + esc(x) + '</kbd>'; }).join('') + '<span>' + esc(k[1]) + '</span></li>'; }).join('') + '</ul><p class="sheet__note">' + esc(t('kbNote')) + '</p></div>';
    elKeys.hidden = false; requestAnimationFrame(function () { elKeys.classList.add('is-open'); });
    keysOpener = document.activeElement; $('[data-close]', elKeys).focus();
  }
  var keysOpener = null;
  function closeKeys() { elKeys.classList.remove('is-open'); elKeys.hidden = true; if (keysOpener && keysOpener.focus) keysOpener.focus(); }
  elKeys.addEventListener('click', function (e) { if (e.target === elKeys || e.target.closest('[data-close]')) closeKeys(); });

  // ─── tastatura ───────────────────────────────────────────────────────────
  document.addEventListener('keydown', function (e) {
    var tag = (e.target.tagName || '').toLowerCase();
    var typing = tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable;
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (!elKeys.hidden) { if (e.key === 'Escape') { e.preventDefault(); closeKeys(); } else if (e.key === 'Tab') trap(e, elKeys); return; }
    if (LB.open) {
      if (e.key === 'Escape') { e.preventDefault(); return closeLb(); }
      if (e.key === 'Tab') return trap(e, elLb);
      if (typing) return;
      if (e.key === 'ArrowLeft' && !isScrollX()) { e.preventDefault(); return lbMove(-1); }
      if (e.key === 'ArrowRight' && !isScrollX()) { e.preventDefault(); return lbMove(1); }
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown') { if (LB.kind === 'page') { e.preventDefault(); return lbCycle(e.key === 'ArrowDown' ? 1 : -1); } return; }
      if (e.code === 'KeyL') return lbFlip('lang');
      if (e.code === 'KeyT') return lbFlip('theme');
      if (e.code === 'KeyD') return lbFlip('device');
      if (e.code === 'KeyZ' || e.code === 'KeyF') return lbZoom();
      if (e.code === 'KeyC' && LB.kind === 'page') { var opts = DIMS.filter(function (d) { return LB.p[d].length > 1; }); if (!opts.length) return; LB.split = LB.split ? null : (opts.indexOf(S.dims[0]) >= 0 ? S.dims[0] : opts[0]); renderLb(false); replaceHash(lbHash()); return; }
      if (e.code === 'KeyO') { var a = $('[data-lb="orig"]', elLb); if (a) window.open(a.getAttribute('href'), '_blank', 'noopener'); return; }
      if (e.code === 'KeyP') { var a2 = $('[data-lb="orig"]', elLb); if (a2) copy(a2.getAttribute('href'), t('copied')); return; }
      if (e.code === 'KeyJ' || e.code === 'KeyK') { var sc = $('.pane__scroll', elLb); if (sc) sc.scrollBy({ top: (e.code === 'KeyJ' ? 1 : -1) * sc.clientHeight * 0.8, behavior: 'smooth' }); return; }
      if (e.key === '?') { return openKeys(); }
      return;
    }
    if (typing) { if (e.key === 'Escape' && e.target.id === 'q') { if (e.target.value) { e.target.value = ''; onSearch(''); } else e.target.blur(); } return; }
    if (e.key === '/') { e.preventDefault(); var q = $('#q'); if (q) { q.focus(); q.select(); } return; }
    if (e.key === '?') { e.preventDefault(); return openKeys(); }
    if (S.tab !== 'photos') {
      if (e.code === 'KeyL') return cycleGlobal('lang');
      if (e.code === 'KeyT') return cycleGlobal('theme');
      if (e.code === 'KeyD') return cycleGlobal('device');
    }
  });
  function isScrollX() { var sc = document.activeElement && document.activeElement.classList && document.activeElement.classList.contains('pane__scroll') ? document.activeElement : null; return sc && LB.zoom !== 'fit' && sc.scrollWidth > sc.clientWidth + 2; }
  function trap(e, root) {
    var f = $$('button:not([disabled]), a[href], select, [tabindex="0"]', root).filter(function (x) { return x.offsetParent !== null; });
    if (!f.length) return;
    var i = f.indexOf(document.activeElement);
    if (e.shiftKey && (i <= 0)) { e.preventDefault(); f[f.length - 1].focus(); }
    else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
  }

  // ─── adresa (#id=…&lang=…&theme=…&device=…) ──────────────────────────────
  var ignorePop = false;
  function pushHash(h) { try { history.pushState({ lb: 1 }, '', '#' + h); return true; } catch (e) { return false; } }
  function replaceHash(h) { try { history.replaceState(history.state, '', h ? '#' + h : location.pathname + location.search); } catch (e) { /* file:// vechi */ } }
  function parseHash() {
    var o = {}; location.hash.replace(/^#/, '').split('&').forEach(function (kv) { if (!kv) return; var i = kv.indexOf('='); o[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1)); });
    return o;
  }
  function fromHash(initial) {
    var h = parseHash();
    if (h.id && byId[h.id]) {
      var p = byId[h.id];
      if (initial && S.tab === 'photos') { S.tab = 'pages'; renderBar(); renderMain(); }
      var s = resolve(p, { lang: h.lang || S.lang, theme: h.theme || S.theme, device: h.device || S.device });
      if (LB.open && LB.kind === 'page') { LB.p = p; LB.s = s; LB.split = h.cmp && p[h.cmp] && p[h.cmp].length > 1 ? h.cmp : null; renderLb(false); }
      else { openPage(p, s, h.cmp); if (initial) LB.pushed = false; }
      return;
    }
    if (h.photo) {
      var ph = photos.find(function (x) { return x.file === h.photo; });
      if (ph) {
        if (S.tab !== 'photos') { S.tab = 'photos'; renderBar(); renderMain(); }
        if (LB.open && LB.kind === 'photo') { LB.ph = ph; renderLb(false); } else { openPhoto(ph); if (initial) LB.pushed = false; }
        return;
      }
    }
    if (LB.open) closeLb(true);
    if (h.tab && h.tab !== S.tab && (h.tab !== 'photos' || photos.length) && ['pages', 'compare', 'photos'].indexOf(h.tab) >= 0) { S.tab = h.tab; renderBar(); renderMain(); }
  }
  addEventListener('popstate', function () { if (ignorePop) { ignorePop = false; return; } fromHash(false); });
  addEventListener('hashchange', function () { fromHash(false); });

  // ─── utilitare: copiere, notificări, derulare ────────────────────────────
  function copy(text, ok) {
    var done = function () { toast(ok); }, fail = function () {
      try { var ta = document.createElement('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0'; (LB.open ? elLb : document.body).appendChild(ta); ta.select(); var r = document.execCommand('copy'); ta.remove(); toast(r ? ok : t('copyFail')); } catch (e) { toast(t('copyFail')); }
    };
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(done, fail); else fail();
  }
  var toastT = 0;
  function toast(msg) { elToast.textContent = msg; elToast.classList.add('is-on'); clearTimeout(toastT); toastT = setTimeout(function () { elToast.classList.remove('is-on'); }, 1600); }
  elTotop.addEventListener('click', function () { scrollTo({ top: 0, behavior: 'smooth' }); var q = $('#q'); if (q && innerWidth >= 720) q.focus({ preventScroll: true }); });

  var lastY = scrollY, ticking = false;
  addEventListener('scroll', function () {
    if (ticking) return; ticking = true;
    requestAnimationFrame(function () {
      ticking = false;
      var y = scrollY;
      elTotop.hidden = y < 1400;
      elBar.classList.toggle('is-stuck', y > elTop.offsetHeight - 2);
      // pe telefon bara se ascunde la derularea în jos și revine la derularea în sus
      if (innerWidth < 720 && !LB.open) {
        if (y > lastY + 6 && y > elTop.offsetHeight + 200 && document.activeElement && document.activeElement.id !== 'q') elBar.classList.add('is-hidden');
        else if (y < lastY - 6) elBar.classList.remove('is-hidden');
      } else elBar.classList.remove('is-hidden');
      lastY = y;
    });
  }, { passive: true });
  var rzT = 0;
  addEventListener('resize', function () { clearTimeout(rzT); rzT = setTimeout(function () { layoutPanes(); doc.style.setProperty('--bar-h', barH() + 'px'); }, 80); });
  if (window.matchMedia) { var mq = matchMedia('(prefers-color-scheme: dark)'); mq.addEventListener && mq.addEventListener('change', function () { if (S.mode === 'auto') renderTop(); }); }

  // ─── pornire ─────────────────────────────────────────────────────────────
  function renderAll() {
    renderTop(); renderBar(); renderMain();
    if (LB.open) renderLb(false);
    doc.style.setProperty('--bar-h', barH() + 'px');
  }
  applyMode();
  if (!M || !shots.length && !photos.length) {
    renderTop();
    elMain.innerHTML = '<div class="empty"><div class="empty__ic">' + icon('alert') + '</div><h2>' + esc(t('noManifest')) + '</h2><p>' + esc(t('noManifestHint')) + '</p></div>';
    return;
  }
  var h0 = parseHash();
  if (h0.tab && ['pages', 'compare', 'photos'].indexOf(h0.tab) >= 0 && (h0.tab !== 'photos' || photos.length)) S.tab = h0.tab;
  renderAll();
  if (h0.id || h0.photo) fromHash(true);
  if ('ResizeObserver' in window) new ResizeObserver(function () { doc.style.setProperty('--bar-h', barH() + 'px'); }).observe(elBar);
})();
