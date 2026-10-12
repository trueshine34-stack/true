// Capturi de ecran pentru toate paginile și stările magazinului, panoului de administrare și emailurilor.
//   npm run screens                                  → totul, în screenshots/ (+ manifest.json, manifest.js, galeria)
//   npm run screens -- --only=admin --lang=ru        → doar o parte (filtrele se pot combina)
//   npm run screens -- --limit=2 --products=sample   → rulare rapidă de verificare (2 pagini pe grup)
// Opțiuni: --only=store|admin|email|<grup>[,…]  --lang=ro,en,ru  --theme=light,dark  --device=desktop,mobile
//          --limit=N (pagini pe grup)  --products=all|sample  --match=<regex pe id>  --workers=N  --force
//          --out=<dosar>  --no-tiles (fără dalele reale ale hărții)  --keep-temp
// Pornește propriul server (bază de date temporară cu date demo), pe un port liber, și îl oprește la final.
// Rulările sunt reluabile: fișierele existente sunt sărite (în afară de --force); manifestul cuprinde tot ce există.
// Playwright nu este o dependență a magazinului: NODE_PATH=$(npm root -g) npm run screens (sau PLAYWRIGHT_PATH=…).
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import net from 'node:net';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const T0 = Date.now();

// ─── argumente ──────────────────────────────────────────────────────────────
const ARGS = Object.fromEntries(process.argv.slice(2).map((a) => {
  const m = a.match(/^--([\w-]+)(?:=(.*))?$/);
  return m ? [m[1], m[2] ?? true] : [a, true];
}));
const list = (v) => (typeof v === 'string' ? v.split(',').map((s) => s.trim()).filter(Boolean) : null);
const OUT = path.resolve(ROOT, typeof ARGS.out === 'string' ? ARGS.out : 'screenshots');
const FORCE = !!ARGS.force;
const LIMIT = Number(ARGS.limit) || 0;
const SAMPLE = ARGS.products === 'sample';
const WORKERS = Math.max(1, Number(ARGS.workers) || Math.min(5, Math.max(2, os.cpus().length)));
const ONLY = list(ARGS.only);
const LANGS = list(ARGS.lang);
const THEMES = list(ARGS.theme) || ['light', 'dark'];
const DEVICES = list(ARGS.device) || ['desktop', 'mobile'];
const MATCH = typeof ARGS.match === 'string' ? new RegExp(ARGS.match) : null;
const REAL_TILES = !ARGS['no-tiles'];
const RETRIES = 2;
const SHOT_TIMEOUT = 90_000;

const ADMIN_EMAIL = 'admin@livada.ro';
const ADMIN_RU_EMAIL = 'echipa@livada.ro';
const ADMIN_PASSWORD = 'Livada2026!';
const CUSTOMER = { first: 'Ioana', last: 'Marinescu', email: 'ioana.marinescu@example.com', phone: '0722 555 101', password: 'Livada-Demo-2026' };
const PENDING = { first: 'Andrei', last: 'Popescu', email: 'andrei.popescu@example.com', phone: '0744 210 330', password: 'Livada-Demo-2026' };

const DEVICE_OPTS = {
  desktop: { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 },
  mobile: { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
};
// emailurile: lățimea unui panou de citire (cardul de 600 px rămâne lizibil în miniaturi)
const EMAIL_DESKTOP = { viewport: { width: 800, height: 900 }, deviceScaleFactor: 1 };
const LOCALE = { ro: 'ro-RO', en: 'en-GB', ru: 'ru-RU' };

const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rel = (f) => path.relative(OUT, f).split(path.sep).join('/');

// ─── Playwright ─────────────────────────────────────────────────────────────
function loadPlaywright() {
  const req = createRequire(import.meta.url);
  const name = process.env.PLAYWRIGHT_PATH || 'playwright';
  try { return req(name); } catch { /* încearcă instalarea globală */ }
  const globalRoot = spawnSync('npm', ['root', '-g'], { encoding: 'utf8' }).stdout.trim();
  return createRequire(path.join(globalRoot, 'noop.js'))(name);
}

// ─── server cu bază de date temporară ───────────────────────────────────────
const freePort = () => new Promise((resolve) => { const s = net.createServer().listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); }); });

async function startServer(tmp) {
  const dir = path.join(tmp, 'data');
  fs.mkdirSync(dir, { recursive: true });
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const env = { ...process.env, LIVADA_SKIP_ENV_FILE: '1', DATA_DIR: dir, PORT: String(port), HOST: '127.0.0.1', NODE_ENV: 'development',
    PUBLIC_URL: base, ADMIN_EMAIL, ADMIN_PASSWORD, SMTP_HOST: '' };
  // echivalentul „npm run seed:demo” + un al doilea administrator (panoul în rusă, limba se ține pe cont)
  const node = (args, extra = {}) => {
    const r = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', ...args], { cwd: ROOT, env: { ...env, ...extra }, encoding: 'utf8', input: '' });
    if (r.status !== 0) throw new Error(`${args.join(' ')}: ${r.stderr || r.stdout}`);
  };
  node(['src/db/seed.js', '--demo']);
  node(['src/cli/create-admin.js', ADMIN_RU_EMAIL, '--name', 'Administrator Livada'], { ADMIN_NEW_PASSWORD: ADMIN_PASSWORD });
  const proc = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', 'src/server.js'], { cwd: ROOT, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  proc.stdout.on('data', (d) => { out += d; });
  proc.stderr.on('data', (d) => { out += d; });
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`${base}/robots.txt`)).ok) break; } catch { /* încă pornește */ }
    if (proc.exitCode !== null) throw new Error(`serverul s-a oprit:\n${out}`);
    await sleep(250);
  }
  return { base, dir, env, log: () => out, stop: () => { try { proc.kill(); } catch { /* deja oprit */ } } };
}

// ─── conversia PNG → WebP (Pillow, procese Python de lungă durată) ──────────
class Converter {
  constructor() {
    this.p = spawn('python3', [path.join(HERE, 'webp.py')], { stdio: ['pipe', 'pipe', 'inherit'] });
    this.queue = [];
    readline.createInterface({ input: this.p.stdout }).on('line', (line) => {
      const cb = this.queue.shift();
      if (cb) cb(JSON.parse(line));
    });
  }
  send(cmd) {
    return new Promise((resolve, reject) => {
      this.queue.push((r) => (r.ok ? resolve(r) : reject(new Error(`webp: ${r.error}`))));
      this.p.stdin.write(`${JSON.stringify(cmd)}\n`);
    });
  }
  close() { this.p.stdin.end(); }
}
const converters = [];
let convIdx = 0;
const convert = (cmd) => converters[convIdx++ % converters.length].send(cmd);

// ─── date din catalog ───────────────────────────────────────────────────────
const CATALOG = JSON.parse(fs.readFileSync(path.join(ROOT, 'data/catalog.json'), 'utf8'));
const PRODUCTS = CATALOG.products;
const bySlug = Object.fromEntries(PRODUCTS.map((p) => [p.slug, p]));

/** catalogul în engleză: data/i18n/catalog-*.en.json (formatul din src/services/catalog-i18n.js), combinate în ordine alfabetică */
function loadEnglish() {
  const O = { labels: {}, values: {}, products: {} };
  const dir = path.join(ROOT, 'data/i18n');
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => /^catalog-.+\.en\.json$/.test(f)).sort() : [];
  const merge = (a, b) => {
    for (const [k, v] of Object.entries(b)) a[k] = v && typeof v === 'object' && !Array.isArray(v) && a[k] && typeof a[k] === 'object' ? merge(a[k], v) : v;
    return a;
  };
  for (const f of files) {
    try { merge(O, JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'))); } catch { /* fișier invalid: ignorat */ }
  }
  const P = (slug) => O.products[slug] || {};
  return {
    name: (slug, ro) => P(slug).name || P(slug).text?.[ro] || ro,
    optionLabel: (slug, key, value, ro) => P(slug).options?.[key]?.values?.[value] || P(slug).text?.[ro] || O.values[ro] || O.labels[ro] || ro,
  };
}

const SAMPLE_PRODUCTS = ['iphone-17', 'iphone-18-pro', 'iphone-duo', 'macbook-air-13-m5', 'ipad-air-11-m4', 'apple-watch-series-12', 'airpods-pro-3',
  'homepod-mini', 'husa-din-silicon-cu-magsafe-pentru-iphone-17-17-pro-si-17-pro-max', 'adaptor-de-alimentare-usb-c-20-w'];
// produse cu o stare suplimentară: altă culoare aleasă
const COLOUR_STATES = ['iphone-17', 'iphone-18-pro', 'macbook-air-13-m5', 'ipad-air-11-m4', 'apple-watch-series-12', 'airpods-max-2'];

const CATEGORY = {
  iphone: { ru: 'iPhone', en: 'iPhone' }, mac: { ru: 'Mac', en: 'Mac' }, ipad: { ru: 'iPad', en: 'iPad' }, watch: { ru: 'Watch', en: 'Watch' },
  airpods: { ru: 'AirPods и Beats', en: 'AirPods & Beats' }, 'tv-home': { ru: 'TV и дом', en: 'TV & Home' }, accesorii: { ru: 'Аксессуары', en: 'Accessories' },
};
const GROUPS = [
  { id: 'store', title: { ru: 'Магазин', en: 'Store' } },
  { id: 'catalog', title: { ru: 'Каталог', en: 'Catalogue' } },
  { id: 'product', title: { ru: 'Товары', en: 'Products' } },
  { id: 'checkout', title: { ru: 'Корзина и оформление', en: 'Cart & checkout' } },
  { id: 'account', title: { ru: 'Личный кабинет', en: 'Customer account' } },
  { id: 'auth', title: { ru: 'Вход и регистрация', en: 'Sign-in & registration' } },
  { id: 'info', title: { ru: 'Информационные страницы', en: 'Information pages' } },
  { id: 'admin', title: { ru: 'Админка', en: 'Admin panel' } },
  { id: 'email', title: { ru: 'Письма', en: 'Emails' } },
];
const KIND = { admin: 'admin', email: 'email' }; // restul grupurilor = magazinul
const kindOf = (group) => KIND[group] || 'store';
const LANGS_OF = { store: ['ro', 'en'], admin: ['ro', 'ru'], email: ['ro', 'en'] };
const tt = (ru, en) => ({ ru, en: en ?? ru });

// ─── stări (acțiuni în pagină) ──────────────────────────────────────────────
const vis = (page, sel, timeout = 10_000) => page.locator(sel).first().waitFor({ state: 'visible', timeout });

/** răspunsul unui POST se cere o singură dată pe limbă și se refolosește (limitele de rată ale serverului) */
const postCache = new Map();
async function replayPost(page, pathRe, key) {
  await page.route((url) => pathRe.test(url.pathname), async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    if (!postCache.has(key)) {
      postCache.set(key, route.fetch({ maxRedirects: 0 }).then(async (r) => {
        const headers = { ...r.headers() };
        delete headers['set-cookie'];
        delete headers['content-encoding'];
        delete headers['content-length'];
        return { status: r.status(), headers, body: await r.body() };
      }));
    }
    try { await route.fulfill(await postCache.get(key)); } catch (e) { postCache.delete(key); throw e; }
  });
}

async function openLockers(page) {
  await page.click('[data-ship-tab=locker]');
  await page.click('.lfind__search');
  await vis(page, '[data-locker-modal]');
  // primul element vizibil dintre markere / ecranul de start (pe telefon lista e ascunsă sub hartă)
  await page.waitForFunction(() => [...document.querySelectorAll('.leaflet-marker-icon, .lstart')]
    .some((e) => e.getBoundingClientRect().width > 0 && getComputedStyle(e).visibility !== 'hidden'), null, { timeout: 15_000 });
  await waitTiles(page);
}
async function waitTiles(page) {
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
  await page.waitForFunction(() => [...document.querySelectorAll('.leaflet-tile')].every((t) => t.complete), null, { timeout: 8000 }).catch(() => {});
  await sleep(400);
}
async function lockersCluj(page, { mobile }) {
  await openLockers(page);
  await page.fill('[data-locker-search]', 'Cluj');
  await page.waitForSelector('.lsug', { timeout: 8000 });
  await page.keyboard.press('Enter');
  if (mobile && !(await page.locator('.litem').first().isVisible().catch(() => false))) {
    await page.click('[data-locker-view=list]').catch(() => {});
  }
  await page.waitForSelector('.litem', { state: 'visible', timeout: 10_000 });
  await waitTiles(page);
}

const STATES = {
  searchOverlay: async (page) => {
    await page.click('[data-open-search]');
    await vis(page, '[data-search-overlay]');
    await page.fill('[data-search-input]', 'airpods');
    await page.waitForSelector('.sresult', { timeout: 8000 });
    await page.evaluate(() => Promise.all([...document.querySelectorAll('.sresult img')].map((i) => (i.complete ? 0 : new Promise((r) => { i.onload = i.onerror = r; })))));
    await sleep(200);
  },
  mobileMenu: async (page) => { await page.click('[data-open-menu]'); await vis(page, '[data-mobile-nav]'); await sleep(350); },
  megaMenu: async (page) => {
    await page.hover('.mainnav__item:first-child .mainnav__link');
    await page.waitForFunction(() => [...document.querySelectorAll('.mainnav__item:first-child .flyout img')].every((i) => i.src && i.complete), null, { timeout: 8000 }).catch(() => {});
    await sleep(400);
  },
  cartDrawer: async (page) => {
    await page.click('.site-header [data-open-cart]');
    await page.waitForSelector('[data-cart-drawer]:not([hidden]) [data-cart-foot]:not([hidden])', { timeout: 8000 });
    await page.evaluate(() => Promise.all([...document.querySelectorAll('[data-cart-drawer] img')].map((i) => (i.complete ? 0 : new Promise((r) => { i.onload = i.onerror = r; })))));
    await sleep(300);
  },
  tabLocker: async (page) => { await page.click('[data-ship-tab=locker]'); await sleep(300); },
  tabPickup: async (page) => { await page.click('[data-ship-tab=pickup]'); await sleep(300); },
  lockerStart: async (page) => { await openLockers(page); },
  lockerCluj: async (page, env) => { await lockersCluj(page, env); },
  lockerCard: async (page, env) => {
    await lockersCluj(page, env);
    await page.locator('.litem').first().click();
    await page.waitForSelector('[data-pick]', { state: 'visible', timeout: 8000 });
    await waitTiles(page);
  },
  checkoutErrors: async (page, env) => {
    await replayPost(page, /\/checkout$/, `checkout|${env.lang}`);
    await page.click('[data-place-order]');
    await page.waitForSelector('.field.has-error, .has-error, [aria-invalid=true]', { timeout: 8000 });
    await page.evaluate(() => scrollTo(0, 0));
    await sleep(300);
  },
  loginError: async (page, env) => {
    await replayPost(page, /\/cont\/autentificare$/, `login|${env.lang}`);
    await page.fill('input[name=email]', CUSTOMER.email);
    await page.fill('input[name=password]', 'parola-gresita-1');
    await page.click('form[action$="/cont/autentificare"] button[type=submit]');
    await page.waitForSelector('[data-form-error]:not([hidden]), .form-error:not([hidden]), .has-error', { timeout: 8000 });
    await sleep(250);
  },
  otpError: async (page, env) => {
    await replayPost(page, /\/cont\/verificare$/, `otp|${env.lang}`);
    await page.locator('[data-otp-cell]').first().click();
    await page.keyboard.type('000000');
    await page.waitForSelector('.otp.is-error, [data-otp].is-error, [data-form-error]:not([hidden]), .has-error', { timeout: 8000 });
    await sleep(250);
  },
  forgotSent: async (page, env) => {
    await replayPost(page, /\/cont\/parola-uitata$/, `forgot|${env.lang}`);
    await page.fill('input[name=email]', CUSTOMER.email);
    const before = await page.locator('main').innerText();
    await page.click('form[action$="/cont/parola-uitata"] button[type=submit]');
    await page.waitForFunction((b) => document.querySelector('main')?.innerText !== b, before, { timeout: 8000 });
    await sleep(300);
  },
  adminMenu: async (page) => { await page.click('[data-adm-menu]'); await sleep(350); },
  emailTab: (i) => async (page) => {
    await page.locator('[data-tab]').nth(i).click();
    const frame = page.frameLocator('[data-tab-frame]');
    await frame.locator('body').waitFor({ timeout: 8000 });
    await sleep(600);
    await page.evaluate(() => {
      // iframe-ul primește înălțimea conținutului, ca emailul să apară întreg în captură
      const f = document.querySelector('[data-tab-frame]');
      try { f.style.height = `${f.contentDocument.documentElement.scrollHeight + 10}px`; } catch { /* alt domeniu */ }
    });
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});
    await sleep(200);
  },
};

// ─── definițiile paginilor ──────────────────────────────────────────────────
const STATIC_INFO = {
  despre: tt('О магазине', 'About us'), livrare: tt('Доставка и локеры', 'Delivery & lockers'), retur: tt('Возврат 30 дней', '30-day returns'),
  garantie: tt('Гарантия и сервис', 'Warranty & service'), termeni: tt('Условия использования', 'Terms & conditions'),
  confidentialitate: tt('Конфиденциальность', 'Privacy policy'), cookies: tt('Cookie-файлы', 'Cookies'), ajutor: tt('Частые вопросы', 'Help & FAQ'),
  contact: tt('Контакты', 'Contact'),
};
const EMAILS = [
  ['verify', tt('Код подтверждения email', 'Email verification code')],
  ['welcome', tt('Добро пожаловать (аккаунт подтверждён)', 'Welcome (account confirmed)')],
  ['reset', tt('Сброс пароля', 'Password reset')],
  ['order-courier', tt('Заказ оформлен — курьер, оплата картой', 'Order placed — courier, card payment')],
  ['order-locker', tt('Заказ оформлен — локер, наложенный платёж', 'Order placed — locker, cash on delivery')],
  ['order-pickup', tt('Заказ оформлен — самовывоз из шоурума', 'Order placed — showroom pickup')],
  ['order-transfer', tt('Заказ оформлен — банковский перевод', 'Order placed — bank transfer')],
  ['status-confirmed', tt('Статус: заказ подтверждён', 'Status: order confirmed')],
  ['status-processing', tt('Статус: заказ собирается', 'Status: being prepared')],
  ['status-shipped', tt('Статус: заказ отправлен (AWB)', 'Status: shipped (AWB)')],
  ['status-ready', tt('Статус: готов к выдаче', 'Status: ready for pickup')],
  ['status-delivered', tt('Статус: заказ доставлен', 'Status: delivered')],
  ['status-cancelled', tt('Статус: заказ отменён', 'Status: cancelled')],
  ['status-returned', tt('Статус: возврат оформлен', 'Status: return processed')],
  ['admin-order', tt('Уведомление админу о новом заказе', 'Admin: new order notification')],
  ['contact', tt('Сообщение с сайта получено', 'Contact message received')],
];
const ADMIN_EMAIL_TABS = [tt('Подтверждение email', 'Email verification'), tt('Добро пожаловать', 'Welcome'), tt('Заказ оформлен', 'Order placed'),
  tt('Заказ отправлен', 'Order shipped'), tt('Сброс пароля', 'Password reset'), tt('Уведомление админу', 'Admin notification')];

function buildDefs(S) {
  const E = loadEnglish();
  const defs = [];
  const add = (d) => defs.push({ session: 'guest', ...d });
  const sec = {
    main: tt('Главная и навигация', 'Home & navigation'), search: tt('Поиск', 'Search'), err: tt('Ошибки', 'Errors'),
    cats: tt('Категории', 'Categories'), lists: tt('Подборки', 'Collections'), cart: tt('Корзина', 'Cart'),
    checkout: tt('Оформление заказа', 'Checkout'), lockers: tt('Карта локеров', 'Locker map'), order: tt('Оплата и заказ', 'Payment & order'),
    acc: tt('Личный кабинет', 'Account'), auth: tt('Вход', 'Sign-in'), reg: tt('Регистрация', 'Registration'), pw: tt('Восстановление пароля', 'Password recovery'),
    info: tt('Информация', 'Information'),
  };

  // magazin
  add({ id: 'store.home', group: 'store', section: sec.main, title: tt('Главная страница', 'Home page'), url: '/' });
  add({ id: 'store.home.megamenu', group: 'store', section: sec.main, title: tt('Главная страница', 'Home page'), state: tt('Мега-меню «iPhone»', 'iPhone mega menu'), url: '/', devices: ['desktop'], viewport: true, act: STATES.megaMenu });
  add({ id: 'store.home.menu', group: 'store', section: sec.main, title: tt('Главная страница', 'Home page'), state: tt('Мобильное меню открыто', 'Mobile menu open'), url: '/', devices: ['mobile'], viewport: true, act: STATES.mobileMenu });
  add({ id: 'store.home.search', group: 'store', section: sec.search, title: tt('Живой поиск', 'Live search'), state: tt('Оверлей поиска с результатами «airpods»', 'Search overlay with results for “airpods”'), url: '/', viewport: true, act: STATES.searchOverlay });
  add({ id: 'store.search', group: 'store', section: sec.search, title: tt('Результаты поиска «iphone»', 'Search results for “iphone”'), url: '/cautare?q=iphone' });
  add({ id: 'store.search.empty', group: 'store', section: sec.search, title: tt('Поиск без результатов', 'Search with no results'), url: '/cautare?q=xyzqw' });
  add({ id: 'store.404', group: 'store', section: sec.err, title: tt('Страница не найдена (404)', 'Page not found (404)'), url: '/pagina-inexistenta', status: 404 });

  // catalog
  for (const c of CATALOG.categories) add({ id: `catalog.${c.slug}`, group: 'catalog', section: sec.cats, title: tt(`Категория «${CATEGORY[c.slug]?.ru || c.name}»`, `${CATEGORY[c.slug]?.en || c.name} category`), url: `/${c.slug}` });
  add({ id: 'catalog.offers', group: 'catalog', section: sec.lists, title: tt('Акции', 'Offers'), url: '/oferte' });
  add({ id: 'catalog.new', group: 'catalog', section: sec.lists, title: tt('Новинки', 'New arrivals'), url: '/noutati' });

  // produse
  // ordinea categoriilor din catalog (o secțiune = o categorie, fără întreruperi)
  const catIdx = Object.fromEntries(CATALOG.categories.map((c, i) => [c.slug, i]));
  const prods = (SAMPLE ? PRODUCTS.filter((p) => SAMPLE_PRODUCTS.includes(p.slug)) : PRODUCTS)
    .map((p, i) => [p, i]).sort((a, b) => (catIdx[a[0].category] ?? 99) - (catIdx[b[0].category] ?? 99) || a[1] - b[1]).map(([p]) => p);
  for (const p of prods) {
    const section = CATEGORY[p.category] || tt(p.category);
    // numele englezesc (Apple) e mai ușor de citit și pentru ru; cel românesc rămâne în `ro` (căutare)
    const enName = E.name(p.slug, p.name);
    const name = { ru: enName, en: enName, ro: p.name };
    add({ id: `product.${p.slug}`, group: 'product', section, title: name, url: `/produs/${p.slug}` });
    if (COLOUR_STATES.includes(p.slug) && p.image_key) {
      const o = p.options.find((x) => x.key === p.image_key);
      const val = o.values[o.values.length - 1];
      const v = p.variants.find((x) => x.options[p.image_key] === val.value);
      if (v) {
        const en = E.optionLabel(p.slug, o.key, val.value, val.label);
        add({ id: `product.${p.slug}.colour`, group: 'product', section, title: name, state: { ru: `Выбран цвет «${en}»`, en: `${en} selected`, ro: `Culoarea ${val.label}` }, url: `/produs/${p.slug}?v=${encodeURIComponent(v.sku)}` });
      }
    }
  }

  // coș și checkout
  add({ id: 'checkout.cart.empty', group: 'checkout', section: sec.cart, title: tt('Корзина', 'Cart'), state: tt('Пустая корзина', 'Empty cart'), url: '/cos' });
  add({ id: 'checkout.cart', group: 'checkout', section: sec.cart, title: tt('Корзина', 'Cart'), state: tt('С товарами', 'With items'), url: '/cos', session: 'cart' });
  add({ id: 'checkout.cart.promo', group: 'checkout', section: sec.cart, title: tt('Корзина', 'Cart'), state: tt('Применён промокод LIVADA10', 'Promo code LIVADA10 applied'), url: '/cos', session: 'cartPromo' });
  add({ id: 'checkout.drawer', group: 'checkout', section: sec.cart, title: tt('Мини-корзина', 'Cart drawer'), state: tt('Открыта поверх главной', 'Open over the home page'), url: '/', session: 'cart', viewport: true, act: STATES.cartDrawer });
  add({ id: 'checkout.courier', group: 'checkout', section: sec.checkout, title: tt('Оформление заказа', 'Checkout'), state: tt('Вкладка «Курьер»', 'Courier tab'), url: '/checkout', session: 'cart' });
  add({ id: 'checkout.locker', group: 'checkout', section: sec.checkout, title: tt('Оформление заказа', 'Checkout'), state: tt('Вкладка «Локер»', 'Locker tab'), url: '/checkout', session: 'cart', act: STATES.tabLocker });
  add({ id: 'checkout.pickup', group: 'checkout', section: sec.checkout, title: tt('Оформление заказа', 'Checkout'), state: tt('Вкладка «Шоурум»', 'Showroom tab'), url: '/checkout', session: 'cart', act: STATES.tabPickup });
  add({ id: 'checkout.errors', group: 'checkout', section: sec.checkout, title: tt('Оформление заказа', 'Checkout'), state: tt('Ошибки валидации', 'Validation errors'), url: '/checkout', session: 'cart', act: STATES.checkoutErrors });
  add({ id: 'checkout.lockers.start', group: 'checkout', section: sec.lockers, title: tt('Карта локеров', 'Locker map'), state: tt('Начальный экран', 'Start screen'), url: '/checkout', session: 'cart', viewport: true, act: STATES.lockerStart });
  add({ id: 'checkout.lockers.search', group: 'checkout', section: sec.lockers, title: tt('Карта локеров', 'Locker map'), state: tt('Поиск «Cluj»', 'Search for “Cluj”'), url: '/checkout', session: 'cart', viewport: true, act: STATES.lockerCluj });
  add({ id: 'checkout.lockers.card', group: 'checkout', section: sec.lockers, title: tt('Карта локеров', 'Locker map'), state: tt('Карточка точки выдачи', 'Pickup point card'), url: '/checkout', session: 'cart', viewport: true, act: STATES.lockerCard });
  if (S.orders.pickup) add({ id: 'checkout.payment', group: 'checkout', section: sec.order, title: tt('Оплата картой (симуляция 3-D Secure)', 'Card payment (3-D Secure simulation)'), url: `/plata/${S.orders.pickup.token}` });
  if (S.orders.courier) add({ id: 'checkout.confirmation', group: 'checkout', section: sec.order, title: tt('Заказ оформлен', 'Order confirmation'), state: tt('Сразу после оплаты', 'Right after payment'), url: `/comanda/${S.orders.courier.token}?nou=1` });
  if (S.orders.locker) add({ id: 'checkout.order-status', group: 'checkout', section: sec.order, title: tt('Страница заказа', 'Order status page'), state: tt('Отправлен, локер, наложенный платёж', 'Shipped, locker, cash on delivery'), url: `/comanda/${S.orders.locker.token}` });

  // cont
  add({ id: 'account.dashboard', group: 'account', section: sec.acc, title: tt('Обзор кабинета', 'Account overview'), url: '/cont', session: 'customer' });
  add({ id: 'account.orders', group: 'account', section: sec.acc, title: tt('Мои заказы', 'My orders'), url: '/cont/comenzi', session: 'customer' });
  if (S.orders.locker) add({ id: 'account.order', group: 'account', section: sec.acc, title: tt('Детали заказа', 'Order details'), state: tt('Отправлен, с трекингом', 'Shipped, with tracking'), url: `/cont/comenzi/${S.orders.locker.number}`, session: 'customer' });
  add({ id: 'account.addresses', group: 'account', section: sec.acc, title: tt('Адреса доставки', 'Delivery addresses'), url: '/cont/adrese', session: 'customer' });
  add({ id: 'account.favorites', group: 'account', section: sec.acc, title: tt('Избранное', 'Favourites'), url: '/cont/favorite', session: 'customer' });
  add({ id: 'account.profile', group: 'account', section: sec.acc, title: tt('Профиль и безопасность', 'Profile & security'), url: '/cont/profil', session: 'customer' });

  // autentificare
  add({ id: 'auth.login', group: 'auth', section: sec.auth, title: tt('Вход', 'Sign in'), url: '/cont/autentificare' });
  add({ id: 'auth.login.error', group: 'auth', section: sec.auth, title: tt('Вход', 'Sign in'), state: tt('Неверный пароль', 'Wrong password'), url: '/cont/autentificare', act: STATES.loginError });
  add({ id: 'auth.register', group: 'auth', section: sec.reg, title: tt('Регистрация', 'Create account'), url: '/cont/inregistrare' });
  add({ id: 'auth.verify', group: 'auth', section: sec.reg, title: tt('Код подтверждения', 'Verification code'), url: '/cont/verificare', session: 'pending' });
  add({ id: 'auth.verify.error', group: 'auth', section: sec.reg, title: tt('Код подтверждения', 'Verification code'), state: tt('Неверный код', 'Wrong code'), url: '/cont/verificare', session: 'pending', act: STATES.otpError });
  add({ id: 'auth.forgot', group: 'auth', section: sec.pw, title: tt('Забыли пароль', 'Forgot password'), url: '/cont/parola-uitata' });
  add({ id: 'auth.forgot.sent', group: 'auth', section: sec.pw, title: tt('Забыли пароль', 'Forgot password'), state: tt('Письмо отправлено', 'Email sent'), url: '/cont/parola-uitata', act: STATES.forgotSent });
  if (S.resetToken) add({ id: 'auth.reset', group: 'auth', section: sec.pw, title: tt('Новый пароль', 'New password'), state: tt('Действующая ссылка из письма', 'Valid link from the email'), url: `/cont/parola-noua?token=${S.resetToken}` });
  add({ id: 'auth.reset.expired', group: 'auth', section: sec.pw, title: tt('Новый пароль', 'New password'), state: tt('Ссылка устарела', 'Expired link'), url: '/cont/parola-noua?token=expirat' });

  // pagini informative
  for (const [slug, title] of Object.entries(STATIC_INFO)) add({ id: `info.${slug}`, group: 'info', section: sec.info, title, url: `/${slug}` });

  // administrare
  const A = (d) => add({ group: 'admin', session: 'admin', ...d });
  const as = {
    main: tt('Главное', 'Overview'), orders: tt('Заказы', 'Orders'), products: tt('Товары', 'Products'), customers: tt('Клиенты', 'Customers'),
    marketing: tt('Промокоды', 'Promo codes'), delivery: tt('Доставка', 'Delivery'), emails: tt('Письма', 'Emails'), messages: tt('Сообщения', 'Messages'),
    system: tt('Настройки и журнал', 'Settings & audit log'),
  };
  A({ id: 'admin.login', section: as.main, title: tt('Вход в админку', 'Admin sign-in'), url: '/admin/autentificare', session: 'adminLogin' });
  A({ id: 'admin.dashboard', section: as.main, title: tt('Дашборд', 'Dashboard'), url: '/admin' });
  A({ id: 'admin.menu', section: as.main, title: tt('Дашборд', 'Dashboard'), state: tt('Боковое меню открыто', 'Side menu open'), url: '/admin', devices: ['mobile'], viewport: true, act: STATES.adminMenu });
  A({ id: 'admin.orders', section: as.orders, title: tt('Список заказов', 'Orders list'), url: '/admin/comenzi' });
  A({ id: 'admin.orders.new', section: as.orders, title: tt('Список заказов', 'Orders list'), state: tt('Фильтр «Новые»', '“New” filter'), url: '/admin/comenzi?status=new' });
  const ordTitle = { courier: tt('Курьер, карта, оплачен', 'Courier, card, paid'), locker: tt('Локер, наложенный платёж, отправлен', 'Locker, cash on delivery, shipped'), pickup: tt('Шоурум, ожидает оплаты', 'Showroom, awaiting payment') };
  for (const k of ['courier', 'locker', 'pickup']) {
    if (S.orders[k]) A({ id: `admin.order.${k}`, section: as.orders, title: tt(`Заказ ${S.orders[k].number}`, `Order ${S.orders[k].number}`), state: ordTitle[k], url: `/admin/comenzi/${S.orders[k].id}` });
  }
  if (S.orders.courier) A({ id: 'admin.invoice', section: as.orders, title: tt('Счёт-фактура (печать)', 'Invoice (print view)'), url: `/admin/comenzi/${S.orders.courier.id}/factura` });
  A({ id: 'admin.products', section: as.products, title: tt('Список товаров', 'Products list'), url: '/admin/produse' });
  A({ id: 'admin.products.low', section: as.products, title: tt('Список товаров', 'Products list'), state: tt('Фильтр «Мало на складе»', '“Low stock” filter'), url: '/admin/produse?stoc=low' });
  A({ id: 'admin.product.new', section: as.products, title: tt('Новый товар', 'New product'), url: '/admin/produse/nou' });
  if (S.productId) A({ id: 'admin.product.edit', section: as.products, title: tt('Редактирование товара — iPhone 17', 'Edit product — iPhone 17'), url: `/admin/produse/${S.productId}` });
  A({ id: 'admin.customers', section: as.customers, title: tt('Список клиентов', 'Customers list'), url: '/admin/clienti' });
  if (S.customerId) A({ id: 'admin.customer', section: as.customers, title: tt(`Клиент — ${CUSTOMER.first} ${CUSTOMER.last}`, `Customer — ${CUSTOMER.first} ${CUSTOMER.last}`), url: `/admin/clienti/${S.customerId}` });
  A({ id: 'admin.promos', section: as.marketing, title: tt('Промокоды', 'Promo codes'), url: '/admin/promotii' });
  A({ id: 'admin.delivery', section: as.delivery, title: tt('Способы доставки и локеры', 'Delivery methods & lockers'), url: '/admin/livrare' });
  A({ id: 'admin.emails', section: as.emails, title: tt('Письма', 'Emails'), state: tt('Шаблон «Подтверждение email»', 'Template “Email verification”'), url: '/admin/emailuri', act: STATES.emailTab(0) });
  ADMIN_EMAIL_TABS.forEach((name, i) => { if (i) A({ id: `admin.emails.tpl${i + 1}`, section: as.emails, title: tt('Письма', 'Emails'), state: tt(`Шаблон «${name.ru}»`, `Template “${name.en}”`), url: '/admin/emailuri', act: STATES.emailTab(i) }); });
  A({ id: 'admin.messages', section: as.messages, title: tt('Сообщения с сайта', 'Contact messages'), url: '/admin/mesaje' });
  A({ id: 'admin.settings', section: as.system, title: tt('Настройки магазина', 'Store settings'), url: '/admin/setari' });
  A({ id: 'admin.audit', section: as.system, title: tt('Журнал действий', 'Audit log'), url: '/admin/jurnal' });

  // emailuri (randate separat, RO și EN)
  for (const [key, title] of EMAILS) add({ id: `email.${key}`, group: 'email', section: tt('Письма клиентам', 'Customer emails'), title, url: null, session: 'none', email: key });
  return defs;
}

// ─── pregătirea datelor (cont client, comenzi, coșuri, sesiuni) ─────────────
async function latestMail(base, to, { not } = {}) {
  for (let i = 0; i < 60; i++) {
    const m = await (await fetch(`${base}/dev/mail/latest.json?to=${encodeURIComponent(to)}`)).json();
    if (m && m.id !== not) return m;
    await sleep(150);
  }
  throw new Error(`niciun email pentru ${to}`);
}
const cookieVal = async (ctx, name) => (await ctx.cookies()).find((c) => c.name === name)?.value;
const post = (page, url, data, headers = {}) => page.evaluate(async ({ url, data, headers }) => {
  const r = await fetch(url, { method: 'POST', credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-Token': document.querySelector('meta[name="csrf-token"]')?.content || '', ...headers },
    body: new URLSearchParams(data) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`${url}: ${r.status} ${j.error || ''}`);
  return j;
}, { url, data, headers });

async function addToCart(page, base, slug, sku) {
  await page.goto(`${base}/produs/${slug}${sku ? `?v=${encodeURIComponent(sku)}` : ''}`);
  const variantId = await page.locator('[data-variant-input]').inputValue();
  await post(page, '/cos/adauga', { variant_id: variantId, qty: '1' });
}
const skuWith = (slug, opts) => bySlug[slug]?.variants.find((v) => Object.entries(opts).every(([k, x]) => v.options[k] === x))?.sku;

async function register(page, base, u) {
  await page.goto(`${base}/cont/inregistrare`);
  await page.fill('input[name=first_name]', u.first);
  await page.fill('input[name=last_name]', u.last);
  await page.fill('input[name=email]', u.email);
  await page.fill('input[name=phone]', u.phone);
  await page.fill('input[name=password]', u.password);
  await page.locator('input[name=terms]').check({ force: true });
  await Promise.all([page.waitForURL(/\/cont\/verificare/), page.click('form[action$="/cont/inregistrare"] button[type=submit]')]);
}

async function placeOrder(page, base, kind) {
  await page.goto(`${base}/checkout`);
  if (kind === 'locker') {
    await page.click('[data-ship-tab=locker]');
    await page.click('.lfind__search');
    await page.waitForSelector('[data-locker-modal]:not([hidden])');
    await page.fill('[data-locker-search]', 'Cluj');
    await page.waitForSelector('.lsug', { timeout: 8000 });
    await page.keyboard.press('Enter');
    await page.waitForSelector('.litem', { timeout: 10_000 });
    await page.locator('.litem').first().click();
    await page.click('[data-pick]');
    await page.waitForSelector('[data-locker-modal]', { state: 'hidden' });
    if (await page.locator('input[name=bill_street]').isVisible()) await page.fill('input[name=bill_street]', 'Str. Memorandumului 12, ap. 4');
    await page.locator('input[name=payment_method][value=cod]').check({ force: true });
  } else if (kind === 'pickup') {
    await page.click('[data-ship-tab=pickup]');
    await page.locator('[data-ship-pane=pickup] input[name=delivery_method]').check({ force: true });
    if (await page.locator('input[name=bill_street]').isVisible()) await page.fill('input[name=bill_street]', 'Str. Florilor 8');
    await page.locator('input[name=payment_method][value=card]').check({ force: true });
  } else {
    await page.locator('input[name=payment_method][value=card]').check({ force: true });
  }
  await page.locator('input[name=terms]').check({ force: true }).catch(() => {});
  await sleep(300);
  await Promise.all([page.waitForURL(/\/(plata|comanda)\//, { timeout: 15_000 }), page.click('[data-place-order]')]);
  return page.url();
}

async function setup(browser, base) {
  const S = { orders: {}, sessions: {} };
  const mk = async () => {
    const ctx = await browser.newContext({ ...DEVICE_OPTS.desktop, locale: 'ro-RO', reducedMotion: 'reduce' });
    await ctx.addInitScript(() => { try { localStorage.setItem('lv_consent', 'essential'); } catch { /* */ } });
    return ctx;
  };
  // 1. client confirmat, cu adresă, favorite și trei comenzi
  const c = await mk();
  const p = await c.newPage();
  await register(p, base, CUSTOMER);
  const m = await latestMail(base, CUSTOMER.email);
  const code = (m.subject.match(/(\d{3})\s?(\d{3})/) || []).slice(1).join('');
  if (!code) throw new Error(`cod de confirmare negăsit: ${m.subject}`);
  await p.locator('[data-otp-cell]').first().click();
  await Promise.all([p.waitForURL((u) => u.pathname === '/cont', { timeout: 15_000 }), p.keyboard.type(code)]);
  await post(p, '/cont/adrese', { first_name: CUSTOMER.first, last_name: CUSTOMER.last, phone: CUSTOMER.phone, county: 'Cluj', city: 'Cluj-Napoca',
    street: 'Str. Memorandumului 12, ap. 4', postcode: '400114', is_default: 'on' });
  await post(p, '/cont/adrese', { first_name: CUSTOMER.first, last_name: CUSTOMER.last, phone: CUSTOMER.phone, county: 'București', city: 'București',
    street: 'Bd. Unirii 45, bl. E3, sc. 2, ap. 31', postcode: '030823' });
  for (const slug of ['iphone-17', 'airpods-pro-3', 'macbook-air-13-m5', 'apple-watch-series-12']) {
    await p.goto(`${base}/produs/${slug}`);
    const id = await p.locator('[data-favorite]').first().getAttribute('data-favorite');
    await post(p, `/api/favorites/${id}`, {});
  }
  // comanda A: curier + card (plătită)
  await addToCart(p, base, 'iphone-17', skuWith('iphone-17', { color: 'lavender' }));
  await addToCart(p, base, 'husa-din-silicon-cu-magsafe-pentru-iphone-17-17-pro-si-17-pro-max');
  let url = await placeOrder(p, base, 'courier');
  if (url.includes('/plata/')) await Promise.all([p.waitForURL(/\/comanda\//), p.click('button[value=success]')]);
  // comanda B: locker + ramburs (o expediem din admin)
  await addToCart(p, base, 'airpods-pro-3');
  await placeOrder(p, base, 'locker');
  const numberB = ((await p.locator('main').innerText()).match(/[A-Z]{2,4}\d{2}-\d{5}/) || [])[0];
  // comanda C: ridicare din showroom + card, neplătită (pagina de plată)
  await addToCart(p, base, 'apple-watch-series-12');
  url = await placeOrder(p, base, 'pickup');
  S.sessions.customer = { lv_sid: await cookieVal(c, 'lv_sid') };
  await c.close();

  // 2. administratorii (RO și RU) + expedierea comenzii B
  for (const [key, email] of [['admin', ADMIN_EMAIL], ['adminRu', ADMIN_RU_EMAIL]]) {
    const a = await mk();
    const ap = await a.newPage();
    await ap.goto(`${base}/admin/autentificare`);
    await ap.fill('input[name=email]', email);
    await ap.fill('input[name=password]', ADMIN_PASSWORD);
    await Promise.all([ap.waitForURL((u) => u.pathname === '/admin'), ap.click('button[type=submit]')]);
    if (key === 'adminRu') {
      await Promise.all([ap.waitForLoadState('load'), ap.click('.adm-lang button[value=ru]')]);
    } else if (numberB) {
      await ap.goto(`${base}/admin/comenzi?q=${numberB}`);
      if (/\/admin\/comenzi\/\d+/.test(ap.url())) {
        // nou → confirmată → expediată (cu AWB), ca în fluxul real
        for (const st of ['confirmed', 'shipped']) {
          const radio = ap.locator(`input[name=status][value=${st}]`);
          if (!(await radio.count())) continue;
          await radio.check({ force: true });
          if (st === 'shipped' && await ap.locator('input[name=awb]').count()) await ap.fill('input[name=awb]', '2345678901');
          await Promise.all([ap.waitForURL(/\/admin\/comenzi\/\d+/), ap.waitForLoadState('load'), ap.click('form[action$="/status"] button')]);
        }
      }
    }
    S.sessions[key] = { lv_sid: await cookieVal(a, 'lv_sid'), ...(key === 'adminRu' ? { lv_admin_lang: 'ru' } : {}) };
    await a.close();
  }

  // 3. cont neconfirmat (pagina cu codul) + link de resetare a parolei
  const u = await mk();
  const up = await u.newPage();
  await register(up, base, PENDING);
  S.sessions.pending = { lv_sid: await cookieVal(u, 'lv_sid') };
  await u.close();
  const g = await mk();
  const gp = await g.newPage();
  const before = await latestMail(base, CUSTOMER.email);
  await gp.goto(`${base}/cont/parola-uitata`);
  await gp.fill('input[name=email]', CUSTOMER.email);
  await gp.click('form[action$="/cont/parola-uitata"] button[type=submit]');
  const rm = await latestMail(base, CUSTOMER.email, { not: before.id });
  const raw = await (await fetch(`${base}/dev/mail/${rm.id}/raw`)).text();
  S.resetToken = (raw.match(/parola-noua\?token=([\w-]+)/) || [])[1] || null;
  await g.close();

  // 4. coșuri de vizitator: cu produse, și cu codul LIVADA10
  const cart = await mk();
  const cp = await cart.newPage();
  await addToCart(cp, base, 'iphone-17', skuWith('iphone-17', { color: 'mistblue' }));
  await addToCart(cp, base, 'husa-transparenta-cu-magsafe-pentru-iphone-17-17-pro-si-17-pro-max');
  await addToCart(cp, base, 'airpods-pro-3');
  S.sessions.cart = { lv_cart: await cookieVal(cart, 'lv_cart') };
  await cart.close();
  const cart2 = await mk();
  const cp2 = await cart2.newPage();
  await addToCart(cp2, base, 'macbook-air-13-m5');
  await addToCart(cp2, base, 'adaptor-de-alimentare-usb-c-70-w');
  await cp2.goto(`${base}/cos`);
  await post(cp2, '/cos/promo', { code: 'LIVADA10' });
  S.sessions.cartPromo = { lv_cart: await cookieVal(cart2, 'lv_cart') };
  await cart2.close();
  return S;
}

// ─── date din baza temporară + emailurile randate (proces separat) ──────────
const PROBE = `
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
const R = process.env.LV_ROOT, OUTDIR = process.env.LV_OUT, IN = JSON.parse(process.env.LV_IN);
const imp = (p) => import(pathToFileURL(path.join(R, p)).href);
const db = await imp('src/db/index.js');
db.openDb();
const { one, all } = db;
const res = { orders: {}, emails: {}, errors: [] };
const mine = all('SELECT id, number, public_token token, delivery_method dm FROM orders WHERE email = ? ORDER BY id', IN.email);
for (const [k, pre] of [['courier', 'courier'], ['locker', 'locker'], ['pickup', 'pickup']]) {
  const o = mine.find((x) => x.dm.startsWith(pre));
  if (o) res.orders[k] = { id: o.id, number: o.number, token: o.token };
}
res.customerId = one('SELECT id FROM users WHERE email = ?', IN.email)?.id || null;
res.productId = one("SELECT id FROM products WHERE slug = 'iphone-17'")?.id || null;
let T, O, I18N;
try {
  T = await imp('src/services/mail-templates.js');
  O = await imp('src/services/orders.js');
  I18N = await imp('src/lib/i18n.js');
} catch (e) { res.errors.push('import: ' + e.message); console.log(JSON.stringify(res)); process.exit(0); }
const user = one('SELECT * FROM users WHERE email = ?', IN.email) || { first_name: 'Ioana', last_name: 'Marinescu', email: IN.email };
const pick = (where) => one('SELECT id FROM orders WHERE ' + where + ' ORDER BY id DESC LIMIT 1')?.id;
const ids = {
  courier: res.orders.courier?.id ?? pick("delivery_method LIKE 'courier%'"),
  locker: res.orders.locker?.id ?? pick("delivery_method LIKE 'locker%'"),
  pickup: res.orders.pickup?.id ?? pick("delivery_method LIKE 'pickup%'"),
  transfer: pick("payment_method = 'transfer' AND delivery_method LIKE 'courier%'") ?? pick("payment_method = 'transfer'"),
};
const load = (id) => { const o = O.orderById(id); return { o, items: O.orderItems(id), link: O.orderLink(o) }; };
const pub = process.env.PUBLIC_URL;
const meta = (lang) => ({ when: new Date().toLocaleString(lang === 'en' ? 'en-GB' : 'ro-RO', { timeZone: 'Europe/Bucharest' }), device: 'Safari pe iPhone', ip: '86.120.14.•••' });
const status = (id, st) => { const { o, items, link } = load(id); return T.orderStatus({ order: { ...o, status: st, awb: st === 'shipped' ? (o.awb || '2345678901') : o.awb }, items, link }); };
const placed = (id) => { const { o, items, link } = load(id); return T.orderConfirmation({ order: { ...o, status: 'new' }, items, link }); };
const MAILS = {
  verify: (l) => T.verifyEmail({ user, code: '482913', link: pub + '/cont/confirma?token=exemplu', meta: meta(l) }),
  welcome: () => T.welcome({ user }),
  reset: (l) => T.passwordReset({ user, link: pub + '/cont/parola-noua?token=exemplu', meta: meta(l) }),
  'order-courier': () => placed(ids.courier), 'order-locker': () => placed(ids.locker), 'order-pickup': () => placed(ids.pickup), 'order-transfer': () => placed(ids.transfer),
  'status-confirmed': () => status(ids.courier, 'confirmed'), 'status-processing': () => status(ids.courier, 'processing'),
  'status-shipped': () => status(ids.locker, 'shipped'), 'status-ready': () => status(ids.pickup, 'ready'),
  'status-delivered': () => status(ids.courier, 'delivered'), 'status-cancelled': () => status(ids.courier, 'cancelled'), 'status-returned': () => status(ids.courier, 'returned'),
  'admin-order': () => { const { o, items } = load(ids.courier); return T.adminNewOrder({ order: o, items, link: pub + '/admin/comenzi/' + o.id }); },
  contact: () => T.contactReceived({ name: user.first_name }),
};
for (const lang of ['ro', 'en']) {
  fs.mkdirSync(path.join(OUTDIR, lang), { recursive: true });
  for (const [key, fn] of Object.entries(MAILS)) {
    try {
      const m = I18N.withLang ? I18N.withLang(lang, () => fn(lang)) : fn(lang);
      const file = path.join(OUTDIR, lang, key + '.html');
      fs.writeFileSync(file, m.html.replaceAll('cid:livada-logo', pub + '/img/brand/email-logo.png'));
      (res.emails[key] ||= {})[lang] = { file, subject: m.subject };
    } catch (e) { res.errors.push(lang + '/' + key + ': ' + e.message); }
  }
}
console.log(JSON.stringify(res));
`;
function probe(server, tmp) {
  const r = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', '--input-type=module', '-e', PROBE], {
    cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    env: { ...server.env, LV_ROOT: ROOT, LV_OUT: path.join(tmp, 'emails'), LV_IN: JSON.stringify({ email: CUSTOMER.email }) },
  });
  const line = (r.stdout || '').trim().split('\n').pop();
  try { return JSON.parse(line); } catch { throw new Error(`probe: ${r.stderr || r.stdout}`); }
}

// ─── capturile ──────────────────────────────────────────────────────────────
const variantKey = (j) => `${j.lang}-${j.theme}-${j.device}`;
const localUrl = (j) => (j.kind === 'store' && j.lang === 'en' ? (j.def.url === '/' ? '/en' : `/en${j.def.url}`) : j.def.url);

function expand(defs, { filtered }) {
  const jobs = [];
  for (const def of defs) {
    const kind = kindOf(def.group);
    for (const lang of LANGS_OF[kind]) {
      for (const theme of ['light', 'dark']) {
        for (const device of ['desktop', 'mobile']) {
          if (def.devices && !def.devices.includes(device)) continue;
          const j = { def, kind, lang, theme, device };
          j.file = path.join(OUT, 'img', variantKey(j), `${def.id}.webp`);
          j.thumb = path.join(OUT, 'thumb', variantKey(j), `${def.id}.webp`);
          if (filtered) {
            if (ONLY && !ONLY.includes(kind) && !ONLY.includes(def.group)) continue;
            if (LANGS && !LANGS.includes(lang)) continue;
            if (!THEMES.includes(theme) || !DEVICES.includes(device)) continue;
            if (MATCH && !MATCH.test(def.id)) continue;
          }
          jobs.push(j);
        }
      }
    }
  }
  return jobs;
}

let S = null;
let SERVER = null;
let TMP = null;
const results = new Map(); // fișier → rezultat conversie
const failures = [];
let done = 0;
let total = 0;

async function makeContext(browser, j) {
  const dev = j.kind === 'email' && j.device === 'desktop' ? EMAIL_DESKTOP : DEVICE_OPTS[j.device];
  const ctx = await browser.newContext({ ...dev, colorScheme: j.theme, reducedMotion: 'reduce', locale: LOCALE[j.lang], timezoneId: 'Europe/Bucharest' });
  await ctx.addInitScript(() => { try { localStorage.setItem('lv_consent', 'essential'); } catch { /* */ } });
  const session = j.def.session === 'admin' && j.lang === 'ru' ? 'adminRu' : j.def.session;
  const cookies = { ...(S.sessions[session] || {}) };
  if (j.kind === 'store') cookies.lv_lang = j.lang;
  if (session === 'adminLogin' && j.lang === 'ru') cookies.lv_admin_lang = 'ru';
  const url = new URL(SERVER.base);
  await ctx.addCookies(Object.entries(cookies).filter(([, v]) => v).map(([name, value]) => ({ name, value, domain: url.hostname, path: '/', httpOnly: true, sameSite: 'Lax' })));
  if (!REAL_TILES) {
    await ctx.route(/basemaps\.cartocdn\.com|tile\.openstreetmap\.org/, (route) => route.fulfill({ status: 200, contentType: 'image/png',
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGN48+4FAAWMAsNXQBAmAAAAAElFTkSuQmCC', 'base64') }));
  }
  return ctx;
}

async function settle(page, { scroll = true } = {}) {
  await page.evaluate(async (doScroll) => {
    const wait = (ms) => new Promise((r) => setTimeout(r, ms));
    document.querySelectorAll('img[loading="lazy"]').forEach((i) => { i.loading = 'eager'; });
    if (doScroll) {
      // derulăm toată pagina: imagini „lazy”, animații la scroll
      const step = Math.max(400, innerHeight * 0.9);
      let y = 0;
      for (; y < document.documentElement.scrollHeight; y += step) { scrollTo(0, y); await wait(25); }
      // înapoi tot în pași: IntersectionObserver trebuie să vadă elementele trecând prin ecran (ex. bara de cumpărare)
      for (; y > 0; y -= step) { scrollTo(0, y); await wait(15); }
      scrollTo(0, 0);
      await wait(60);
    }
    await document.fonts.ready;
    const pending = [...document.images].filter((i) => (i.currentSrc || i.getAttribute('src')) && !i.complete);
    await Promise.race([Promise.all(pending.map((i) => new Promise((r) => { i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }); }))), wait(10_000)]);
    // și decodate, ca să nu apară goale în captură
    await Promise.race([Promise.all([...document.images].filter((i) => i.complete && i.naturalWidth).map((i) => i.decode().catch(() => {}))), wait(5000)]);
    if (doScroll) scrollTo(0, 0);
  }, scroll);
  await page.waitForLoadState('networkidle', { timeout: 6000 }).catch(() => {});
  await sleep(150);
}

async function shoot(ctx, j) {
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  try {
    if (j.kind === 'email') {
      const e = S.emails[j.def.email]?.[j.lang];
      if (!e) throw new Error(`emailul ${j.def.email}/${j.lang} nu a putut fi randat`);
      await page.goto(pathToFileURL(e.file).href, { waitUntil: 'load', timeout: 30_000 });
    } else {
      const target = SERVER.base + localUrl(j);
      const res = await page.goto(target, { waitUntil: 'load', timeout: 30_000 });
      const status = res?.status() ?? 0;
      if (status !== (j.def.status || 200)) throw new Error(`HTTP ${status} pentru ${localUrl(j)}`);
      const want = new URL(target).pathname;
      const got = new URL(page.url()).pathname;
      if (got !== want) throw new Error(`redirecționat de pe ${want} la ${got}`);
    }
    await settle(page, { scroll: !j.def.viewport });
    if (j.def.act) {
      await j.def.act(page, { lang: j.lang, device: j.device, mobile: j.device === 'mobile' });
      await settle(page, { scroll: false });
    }
    const png = path.join(TMP, 'png', `${process.hrtime.bigint()}.png`);
    await page.screenshot({ path: png, fullPage: !j.def.viewport, animations: 'disabled', caret: 'hide', timeout: 60_000 });
    const r = await convert({ op: 'convert', src: png, out: j.file, thumb: j.thumb, q: 72, tq: 70, removeSrc: true });
    results.set(j.file, r);
    return r;
  } catch (e) {
    if (errors.length) e.message += ` [JS: ${errors.slice(0, 2).join(' | ')}]`;
    throw e;
  } finally {
    await page.close().catch(() => {});
  }
}

const withTimeout = (p, ms, what) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(`timeout ${ms / 1000}s: ${what}`)), ms))]);

async function runJobs(browser, jobs) {
  total = jobs.length;
  let next = 0;
  let lastLog = 0;
  const worker = async () => {
    let cur = null;
    while (next < jobs.length) {
      const j = jobs[next++];
      const key = `${j.kind}|${variantKey(j)}|${j.def.session}`;
      for (let attempt = 0; attempt <= RETRIES; attempt++) {
        try {
          if (!cur || cur.key !== key) {
            if (cur) await cur.ctx.close().catch(() => {});
            cur = { key, ctx: await makeContext(browser, j) };
          }
          await withTimeout(shoot(cur.ctx, j), SHOT_TIMEOUT, `${j.def.id} ${variantKey(j)}`);
          break;
        } catch (e) {
          if (cur) { await cur.ctx.close().catch(() => {}); cur = null; }
          if (attempt === RETRIES) failures.push({ id: j.def.id, variant: variantKey(j), error: e.message.split('\n')[0] });
        }
      }
      done++;
      if (Date.now() - lastLog > 5000 || done === total) {
        lastLog = Date.now();
        const el = (Date.now() - T0) / 1000;
        log(`  ${done}/${total} capturi · ${failures.length} erori · ${Math.round(el)} s`);
      }
    }
    if (cur) await cur.ctx.close().catch(() => {});
  };
  await Promise.all(Array.from({ length: Math.min(WORKERS, jobs.length) }, worker));
}

// ─── manifest, fotografii, galerie ──────────────────────────────────────────
async function writeManifest(allJobs, photos) {
  const shots = [];
  const needDims = [];
  for (const j of allJobs) {
    if (!fs.existsSync(j.file)) continue;
    const file = rel(j.file);
    const d = j.def;
    const s = { id: d.id, group: d.group, section: d.section, title: d.title, ...(d.state ? { state: d.state } : {}), url: d.url,
      lang: j.lang, theme: j.theme, device: j.device, file, thumb: rel(j.thumb) };
    const r = results.get(j.file);
    if (r) {
      Object.assign(s, { w: r.w, h: r.h });
      if (r.parts) Object.assign(s, { scaled: r.scaled, fullW: r.fullW, fullH: r.fullH, parts: r.parts.map((p) => ({ file: rel(p.path), w: p.w, h: p.h })) });
    } else {
      // captură din altă rulare: dimensiunile (și bucățile la rezoluție completă) se citesc de pe disc
      const parts = [];
      for (let n = 1; fs.existsSync(j.file.replace(/\.webp$/, `.p${n}.webp`)); n++) parts.push(j.file.replace(/\.webp$/, `.p${n}.webp`));
      needDims.push([s, j.file, parts]);
    }
    shots.push(s);
  }
  if (needDims.length) {
    const r = await convert({ op: 'dims', files: needDims.flatMap(([, f, parts]) => [f, ...parts]) });
    for (const [s, f, parts] of needDims) {
      [s.w, s.h] = r.dims[f] || [0, 0];
      if (parts.length) {
        s.parts = parts.map((p) => ({ file: rel(p), w: r.dims[p]?.[0] || 0, h: r.dims[p]?.[1] || 0 }));
        s.fullW = s.parts[0].w;
        s.fullH = s.parts.reduce((a, p) => a + p.h, 0);
        s.scaled = Math.round((s.w / s.fullW) * 1e4) / 1e4;
      }
    }
  }
  const manifest = {
    generated: new Date().toISOString(), site: 'Livada',
    counts: { shots: shots.length, pages: new Set(shots.map((s) => s.id)).size, photos: photos.length },
    groups: GROUPS,
    photoCategories: CATALOG.categories.map((c) => ({ id: c.slug, title: CATEGORY[c.slug] || tt(c.name) })),
    variants: { store: LANGS_OF.store, admin: LANGS_OF.admin, email: LANGS_OF.email, themes: ['light', 'dark'], devices: ['desktop', 'mobile'] },
    shots, photos,
  };
  fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
  fs.writeFileSync(path.join(OUT, 'manifest.js'), `window.MANIFEST = ${JSON.stringify(manifest)};\n`);
  return manifest;
}

function copyPhotos() {
  const E = loadEnglish();
  const src = path.join(ROOT, 'public/img/products');
  const dst = path.join(OUT, 'photos');
  const photos = [];
  const seen = new Set();
  const cp = (from, to) => {
    const a = path.join(ROOT, 'public', from);
    const b = path.join(dst, to);
    if (!fs.existsSync(a)) return false;
    fs.mkdirSync(path.dirname(b), { recursive: true });
    if (FORCE || !fs.existsSync(b) || fs.statSync(b).size !== fs.statSync(a).size) fs.copyFileSync(a, b);
    return true;
  };
  for (const p of PRODUCTS) {
    const opt = p.options.find((o) => o.key === p.image_key);
    for (const [key, imgs] of Object.entries(p.images || {})) {
      const val = opt?.values.find((v) => v.value === key);
      const ro = val?.label || '';
      const en = val ? E.optionLabel(p.slug, opt.key, key, ro) : '';
      imgs.forEach((im, i) => {
        const big = im['1000'];
        const small = im['480'];
        if (!big || !small || !big.startsWith('/img/products/')) return;
        const relBig = big.replace('/img/products/', '');
        const relSmall = small.replace('/img/products/', '');
        if (!cp(big, relBig) || !cp(small, relSmall)) return;
        seen.add(relBig);
        photos.push({ slug: p.slug, product: p.name, product_en: E.name(p.slug, p.name), category: p.category, variant: ro && en && en !== ro ? `${ro} / ${en}` : ro,
          variant_ro: ro, variant_en: en, n: i + 1, file: `photos/${relBig}`, thumb: `photos/${relSmall}` });
      });
    }
  }
  // fotografii rămase fără produs în catalog (ex. produse retrase)
  if (fs.existsSync(src)) {
    for (const dir of fs.readdirSync(src)) {
      const full = path.join(src, dir);
      if (!fs.statSync(full).isDirectory()) continue;
      for (const f of fs.readdirSync(full).filter((x) => x.endsWith('-1000.webp'))) {
        const relBig = `${dir}/${f}`;
        if (seen.has(relBig)) continue;
        const relSmall = relBig.replace(/-1000\.webp$/, '-480.webp');
        if (!cp(`/img/products/${relBig}`, relBig) || !cp(`/img/products/${relSmall}`, relSmall)) continue;
        photos.push({ slug: dir, product: dir, category: '', variant: f.replace(/-\d+-1000\.webp$/, ''), file: `photos/${relBig}`, thumb: `photos/${relSmall}` });
      }
    }
  }
  return photos;
}

/** galeria (tools/screenshots/gallery): index.html la rădăcină, restul în assets/ */
function copyGallery() {
  const g = path.join(HERE, 'gallery');
  if (!fs.existsSync(path.join(g, 'index.html'))) return false;
  const assets = path.join(OUT, 'assets');
  fs.mkdirSync(assets, { recursive: true });
  let index = fs.readFileSync(path.join(g, 'index.html'), 'utf8');
  const walk = (dir, prefix = '') => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name), `${prefix}${e.name}/`) : [`${prefix}${e.name}`]));
  for (const f of walk(g)) {
    if (f === 'index.html') continue;
    if (f.startsWith('assets/')) {
      // galeria își are deja fișierele în assets/ — le copiem la fel
      fs.mkdirSync(path.dirname(path.join(OUT, f)), { recursive: true });
      fs.copyFileSync(path.join(g, f), path.join(OUT, f));
      continue;
    }
    fs.mkdirSync(path.dirname(path.join(assets, f)), { recursive: true });
    fs.copyFileSync(path.join(g, f), path.join(assets, f));
    const esc = f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    index = index.replace(new RegExp(`((?:href|src)=["'])(?:\\./)?${esc}(["'?#])`, 'g'), `$1assets/${f}$2`);
  }
  fs.writeFileSync(path.join(OUT, 'index.html'), index);
  // fonturile cerute de CSS-ul galeriei (url('fonts/…')) vin din public/fonts, dacă galeria nu le are
  for (const css of walk(assets).filter((f) => f.endsWith('.css'))) {
    const text = fs.readFileSync(path.join(assets, css), 'utf8');
    for (const [, ref] of text.matchAll(/url\(['"]?(fonts\/[\w.-]+\.woff2)['"]?\)/g)) {
      const to = path.join(assets, path.dirname(css), ref);
      const from = path.join(ROOT, 'public', ref);
      if (!fs.existsSync(to) && fs.existsSync(from)) { fs.mkdirSync(path.dirname(to), { recursive: true }); fs.copyFileSync(from, to); }
    }
  }
  return true;
}

const dirSize = (d) => (fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).reduce((s, e) => s + (e.isDirectory() ? dirSize(path.join(d, e.name)) : fs.statSync(path.join(d, e.name)).size), 0) : 0);
const mb = (b) => `${(b / 1024 / 1024).toFixed(1)} MB`;

// ─── main ───────────────────────────────────────────────────────────────────
async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'livada-shots-'));
  fs.mkdirSync(path.join(TMP, 'png'), { recursive: true });
  const { chromium } = loadPlaywright();
  log(`\nLivada screenshots → ${OUT}`);
  SERVER = await startServer(TMP);
  log(`  server: ${SERVER.base} (date demo, bază temporară)`);
  // proxy-ul din mediu (dacă există) doar pentru dalele hărții; serverul local rămâne direct
  const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
  const browser = await chromium.launch({ args: proxy ? [`--proxy-server=${proxy}`, '--proxy-bypass-list=127.0.0.1;localhost'] : [] });
  for (let i = 0; i < Math.max(2, Math.ceil(WORKERS / 2)); i++) converters.push(new Converter());
  let exitCode = 0;
  try {
    log('  pregătire: cont client, comenzi, coșuri, sesiuni…');
    S = await setup(browser, SERVER.base);
    const pr = probe(SERVER, TMP);
    Object.assign(S, { orders: pr.orders, customerId: pr.customerId, productId: pr.productId, emails: pr.emails });
    if (pr.errors.length) log(`  ! emailuri: ${pr.errors.join(' | ')}`);
    log(`  pregătire gata în ${Math.round((Date.now() - T0) / 1000)} s (comenzi: ${Object.values(S.orders).map((o) => o.number).join(', ')})`);

    const defs = buildDefs(S);
    let jobs = expand(defs, { filtered: true });
    if (LIMIT) {
      const per = {};
      const keep = new Set();
      for (const d of defs) {
        if (!jobs.some((j) => j.def === d)) continue;
        per[d.group] = (per[d.group] || 0) + 1;
        if (per[d.group] <= LIMIT) keep.add(d);
      }
      jobs = jobs.filter((j) => keep.has(j.def));
    }
    const skipped = FORCE ? 0 : jobs.filter((j) => fs.existsSync(j.file) && fs.existsSync(j.thumb)).length;
    if (!FORCE) jobs = jobs.filter((j) => !(fs.existsSync(j.file) && fs.existsSync(j.thumb)));
    // aceeași combinație (limbă/temă/dispozitiv/sesiune) una după alta → contextele se refolosesc
    jobs.sort((a, b) => `${a.kind}|${variantKey(a)}|${a.def.session}`.localeCompare(`${b.kind}|${variantKey(b)}|${b.def.session}`));
    log(`  ${jobs.length} capturi de făcut${skipped ? `, ${skipped} existente sărite` : ''} · ${WORKERS} în paralel`);
    await runJobs(browser, jobs);

    log('  fotografii, manifest, galerie…');
    const photos = copyPhotos();
    const manifest = await writeManifest(expand(defs, { filtered: false }), photos);
    const gallery = copyGallery();
    const sec = Math.round((Date.now() - T0) / 1000);
    log(`\nGata în ${Math.floor(sec / 60)} min ${sec % 60} s: ${results.size} capturi noi, ${manifest.counts.shots} în manifest (${manifest.counts.pages} pagini/stări), ${photos.length} fotografii.`);
    log(`Dimensiune: img ${mb(dirSize(path.join(OUT, 'img')))}, thumb ${mb(dirSize(path.join(OUT, 'thumb')))}, photos ${mb(dirSize(path.join(OUT, 'photos')))}, total ${mb(dirSize(OUT))}.`);
    if (!gallery) log('! galeria (tools/screenshots/gallery/index.html) lipsește — doar manifestul a fost scris.');
    if (failures.length) {
      exitCode = 1;
      log(`\n${failures.length} capturi eșuate:`);
      for (const f of failures) log(`  ✗ ${f.id} [${f.variant}] ${f.error}`);
    }
  } catch (e) {
    exitCode = 1;
    console.error(`\n✗ ${e.stack || e.message}\n--- log server ---\n${SERVER?.log().slice(-3000)}`);
  } finally {
    await browser.close().catch(() => {});
    converters.forEach((c) => c.close());
    SERVER.stop();
    if (!ARGS['keep-temp']) fs.rmSync(TMP, { recursive: true, force: true });
    else log(`  temp: ${TMP}`);
  }
  process.exit(exitCode);
}

await main();
