// Test end-to-end în browser (Playwright): parcurge magazinul ca un client real și panoul ca un administrator.
//   npm run e2e                 → pornește serverul pe un port liber, cu o bază de date temporară
//   E2E_HEADED=1 npm run e2e    → cu fereastra browserului vizibilă
// Capturile de ecran ajung în test-results/e2e/.
// Playwright nu este o dependență a magazinului: se folosește instalarea locală sau cea globală (npm i -g playwright).
import { spawn, execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'test-results/e2e');
fs.mkdirSync(OUT, { recursive: true });

async function loadPlaywright() {
  try { return await import('playwright'); } catch { /* încearcă instalarea globală */ }
  const globalRoot = execSync('npm root -g').toString().trim();
  return createRequire(path.join(globalRoot, 'noop.js'))('playwright');
}

const freePort = () => new Promise((resolve) => { const s = net.createServer().listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); }); });

async function startServer() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'livada-e2e-'));
  const port = await freePort();
  const proc = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', 'src/server.js'], {
    cwd: ROOT,
    env: { ...process.env, LIVADA_SKIP_ENV_FILE: '1', DATA_DIR: dir, PORT: String(port), HOST: '127.0.0.1', NODE_ENV: 'development',
      PUBLIC_URL: `http://127.0.0.1:${port}`, ADMIN_EMAIL: 'admin@livada.ro', ADMIN_PASSWORD: 'E2e-Admin-2026', SMTP_HOST: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  proc.stdout.on('data', (d) => { log += d; });
  proc.stderr.on('data', (d) => { log += d; });
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`${base}/robots.txt`)).ok) break; } catch { /* încă pornește */ }
    await new Promise((r) => setTimeout(r, 250));
    if (proc.exitCode !== null) throw new Error(`serverul s-a oprit:\n${log}`);
  }
  return { base, dir, log: () => log, stop: () => { proc.kill(); fs.rmSync(dir, { recursive: true, force: true }); } };
}

// ─── mini-runner ────────────────────────────────────────────────────────────
const results = [];
async function step(name, fn) {
  const t0 = Date.now();
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - t0 });
    console.log(`  ✓ ${name} (${Date.now() - t0} ms)`);
  } catch (e) {
    results.push({ name, ok: false, error: e.message });
    console.log(`  ✗ ${name}\n    ${e.message.split('\n').join('\n    ')}`);
    throw e;
  }
}
const expect = (cond, msg) => { if (!cond) throw new Error(msg); };

async function newContext(browser, base, { mobile = false } = {}) {
  const ctx = await browser.newContext(mobile
    ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ro-RO', reducedMotion: 'reduce' }
    : { viewport: { width: 1440, height: 900 }, locale: 'ro-RO', reducedMotion: 'reduce' }); // fără animații → capturi stabile
  await ctx.addInitScript(() => { try { localStorage.setItem('lv_consent', 'necessary'); } catch { /* */ } });
  // hărțile nu au nevoie de dale reale în test: răspundem cu o dală neutră (testul rulează și fără internet)
  // (E2E_REAL_TILES=1 lasă dalele reale, pentru capturi de prezentare)
  if (!process.env.E2E_REAL_TILES) await ctx.route(/basemaps\.cartocdn\.com|tile\.openstreetmap\.org/, (route) => route.fulfill({ status: 200, contentType: 'image/png',
    body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGN48+4FAAWMAsNXQBAmAAAAAElFTkSuQmCC', 'base64') }));
  const errors = [];
  ctx.on('page', (p) => watch(p, errors));
  return { ctx, errors, base };
}
function watch(p, errors) {
  p.on('pageerror', (e) => errors.push(`${p.url()}: ${e.message}`));
  // 4xx de la API sunt răspunsuri de validare așteptate (ex. cod greșit); 5xx sunt prinse mai jos
  p.on('console', (m) => { if (m.type() === 'error' && !/status of 4\d\d/.test(m.text())) errors.push(`${p.url()}: ${m.text()}`); });
  p.on('response', (r) => { if (r.status() >= 500) errors.push(`${r.status()} ${r.url()}`); });
}

async function latestMail(base, to) {
  for (let i = 0; i < 40; i++) {
    const m = await (await fetch(`${base}/dev/mail/latest.json?to=${encodeURIComponent(to)}`)).json();
    if (m) return m;
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error(`niciun email pentru ${to}`);
}

async function noHorizontalScroll(page) {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(over <= 1, `${page.url()} are scroll orizontal (${over}px)`);
  // și elemente care ies din ecran, chiar dacă body le ascunde (exceptând benzile cu scroll orizontal intenționat)
  const out = await page.evaluate(() => {
    const clipped = (e) => { for (let a = e.parentElement; a && a !== document.body; a = a.parentElement) if (getComputedStyle(a).overflowX !== 'visible') return true; return false; };
    return [...document.querySelectorAll('body *')].filter((e) => {
      const r = e.getBoundingClientRect();
      return r.width > 0 && r.right > innerWidth + 1 && !e.closest('[hidden]') && getComputedStyle(e).position !== 'fixed' && !clipped(e);
    }).slice(0, 4).map((e) => `<${e.tagName.toLowerCase()} class="${e.className}"> → ${Math.round(e.getBoundingClientRect().right)}px`);
  });
  expect(out.length === 0, `${page.url()}: elemente în afara ecranului:\n${out.join('\n')}`);
}

const shot = (page, name) => page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: false });

// ─── scenariu ───────────────────────────────────────────────────────────────
const { chromium } = await loadPlaywright();
const server = await startServer();
const launchOpts = { headless: !process.env.E2E_HEADED };
const browser = await chromium.launch(launchOpts);
const email = `e2e.${Date.now()}@exemplu.ro`;
const password = 'Livada-e2e-26';
let orderNumber = '';
let failed = false;

console.log(`\nLivada e2e → ${server.base}\n`);
try {
  const { ctx, errors, base } = await newContext(browser, server.base);
  const page = await ctx.newPage();

  await step('prima pagină: hero, categorii, produse, fără erori JS', async () => {
    await page.goto(base);
    expect(await page.locator('.hero, [class*="hero"]').count() > 0, 'lipsește hero');
    expect(await page.locator('.pcard, .product-card, [data-product]').count() >= 8, 'prea puține produse pe prima pagină');
    await shot(page, '01-home');
  });

  await step('căutare live fără diacritice („husa magsafe”)', async () => {
    await page.goto(`${base}/cautare?q=husa+magsafe`);
    expect(await page.locator('.pcard, .product-card').count() > 0, 'căutarea nu a găsit huse');
  });

  await step('înregistrare cont nou', async () => {
    await page.goto(`${base}/cont/inregistrare`);
    await page.fill('input[name=first_name]', 'Ioana');
    await page.fill('input[name=last_name]', 'Marinescu');
    await page.fill('input[name=email]', email);
    await page.fill('input[name=phone]', '0722 555 101');
    await page.fill('input[name=password]', password);
    await page.locator('input[name=terms]').check({ force: true });
    await shot(page, '02-register');
    await Promise.all([page.waitForURL(/\/cont\/verificare/), page.click('form button[type=submit]:has-text("Creează contul")')]);
  });

  let code = '';
  await step('emailul de confirmare conține codul din 6 cifre și linkul', async () => {
    const m = await latestMail(base, email);
    const match = m.subject.match(/(\d{3})\s?(\d{3})/);
    expect(match, `subiect fără cod: ${m.subject}`);
    code = match[1] + match[2];
    const mail = await ctx.newPage();
    await mail.goto(`${base}/dev/mail/${m.id}/raw`);
    expect(await mail.locator(`text=${match[1]}`).count() > 0 || (await mail.content()).includes(match[1]), 'codul nu apare în corpul emailului');
    expect((await mail.content()).includes('/cont/confirma?token='), 'lipsește linkul de confirmare');
    await mail.setViewportSize({ width: 720, height: 1100 });
    await mail.screenshot({ path: path.join(OUT, '03-email-verify.png'), fullPage: true });
    await mail.close();
  });

  await step('cod greșit → eroare, cod corect → cont confirmat', async () => {
    const wrong = code === '000000' ? '111111' : '000000';
    await page.locator('[data-otp-cell]').first().click();
    await page.keyboard.type(wrong);
    await page.waitForSelector('.otp.is-error, [data-otp].is-error, .alert--error, .field__msg.is-error', { timeout: 5000 });
    await shot(page, '04-otp-error');
    await page.locator('[data-otp-cell]').first().click();
    await Promise.all([page.waitForURL((u) => u.pathname === '/cont'), page.keyboard.type(code)]);
    expect(await page.locator('text=Ioana').count() > 0, 'contul nu afișează numele');
    const welcome = await latestMail(base, email);
    expect(/bun venit|activ/i.test(welcome.subject), `al doilea email ar trebui să fie de bun venit: ${welcome.subject}`);
  });

  await step('produs: configurator (culoare, stocare) și adăugare în coș', async () => {
    await page.goto(`${base}/produs/iphone-17`);
    const before = await page.locator('[data-variant-input]').inputValue();
    const picks = page.locator('[data-option] input[type=radio]:not(:checked):not(:disabled)');
    if (await picks.count()) await picks.nth(0).check({ force: true });
    await page.waitForTimeout(250);
    const after = await page.locator('[data-variant-input]').inputValue();
    expect(after && after !== '', 'varianta nu este selectată');
    expect(before !== after || (await picks.count()) === 0, 'schimbarea opțiunii nu a schimbat varianta');
    await shot(page, '05-product');
    await page.click('[data-add-btn]');
    await page.waitForSelector('[data-cart-drawer]:not([hidden]) .ci, [data-cart-drawer]:not([hidden]) [data-cart-foot]:not([hidden])', { timeout: 5000 });
    await page.waitForTimeout(400);
    await shot(page, '06-cart-drawer');
  });

  await step('coș: cod promo LIVADA10 aplicat', async () => {
    await page.goto(`${base}/cos`);
    await page.click('[data-cart-page] details.promo summary');
    await page.fill('[data-cart-page] input[name=code]', 'LIVADA10');
    await page.locator('[data-cart-page] input[name=code]').press('Enter');
    await page.waitForSelector('.promo.is-applied', { timeout: 5000 });
    await page.waitForTimeout(300);
    expect(await page.locator('text=/Reducere|Discount/i').count() > 0, 'reducerea nu apare în sumar');
    await shot(page, '07-cart');
  });

  await step('checkout: locker ales pe hartă (căutare „Cluj”)', async () => {
    await page.goto(`${base}/checkout`);
    await page.fill('input[name=phone]', '0722555101');
    await page.click('[data-ship-tab=locker]');
    await page.click('[data-open-lockers]');
    await page.waitForSelector('[data-locker-modal]:not([hidden])');
    await page.waitForSelector('.litem', { timeout: 10000 });
    expect(await page.locator('.leaflet-marker-icon').count() > 0, 'harta nu are markere');
    await page.fill('[data-locker-search]', 'Cluj');
    await page.waitForTimeout(400);
    const first = page.locator('.litem').first();
    const txt = await first.innerText();
    expect(/Cluj/i.test(txt), `primul rezultat nu este din Cluj: ${txt}`);
    await first.click();
    await page.waitForSelector('[data-pick]');
    await page.waitForTimeout(process.env.E2E_REAL_TILES ? 1500 : 300);
    await shot(page, '08-locker-map');
    await page.click('[data-pick]');
    await page.waitForSelector('[data-locker-modal]', { state: 'hidden' });
    expect(await page.locator('[data-locker-id]').inputValue(), 'lockerul nu a fost salvat în formular');
    // facturarea pornește de la localitatea lockerului; clientul completează doar strada
    expect(await page.locator('input[name=bill_city]').inputValue(), 'localitatea de facturare nu a fost precompletată');
    expect(await page.locator('select[name=bill_county]').inputValue(), 'județul de facturare nu a fost precompletat');
    await page.fill('input[name=bill_street]', 'Str. Memorandumului 12, ap. 4');
    expect(await page.locator('.locker-open').isHidden(), 'butonul „Alege lockerul” trebuie ascuns după alegere');
    expect(await page.locator('[data-locker-picked] [data-open-lockers]').isVisible(), 'lipsește „Schimbă” pe cardul lockerului');
    await shot(page, '09-checkout-locker');
  });

  await step('plasare comandă cu plata cu cardul → 3-D Secure → confirmare', async () => {
    await page.locator('input[name=payment_method][value=card]').check({ force: true });
    await page.locator('input[name=terms]').check({ force: true }).catch(() => {});
    await page.click('[data-place-order]');
    await page.waitForURL(/\/plata\//, { timeout: 8000 }).catch(async () => {
      await shot(page, '10-checkout-blocked');
      const msgs = await page.locator('.field__msg.is-error, .has-error .field__msg, .toast').allInnerTexts();
      throw new Error(`checkout-ul nu a trecut la plată: ${msgs.filter(Boolean).join(' | ') || 'fără mesaj vizibil'}`);
    });
    await shot(page, '10-payment');
    await Promise.all([page.waitForURL(/\/comanda\//), page.click('button[value=success]')]);
    const text = await page.locator('main').innerText();
    orderNumber = (text.match(/LV\d{2}-\d{5}/) || [])[0];
    expect(orderNumber, 'pagina de confirmare nu afișează numărul comenzii');
    expect(/plătit|plata a fost/i.test(text), 'plata nu apare ca efectuată');
    await shot(page, '11-order-confirmed');
    const m = await latestMail(base, email);
    expect(m.subject.includes(orderNumber), `emailul de confirmare lipsește: ${m.subject}`);
  });

  await step('contul clientului afișează comanda', async () => {
    await page.goto(`${base}/cont/comenzi`);
    expect(await page.locator(`text=${orderNumber}`).count() > 0, 'comanda nu apare în cont');
  });

  await step('cont pe mobil: toate paginile încap pe ecran', async () => {
    const cookies = await ctx.cookies();
    const m = await newContext(browser, base, { mobile: true });
    await m.ctx.addCookies(cookies);
    const mp = await m.ctx.newPage();
    for (const p of ['/cont', '/cont/comenzi', `/cont/comenzi/${orderNumber}`, '/cont/favorite', '/cont/adrese', '/cont/profil']) {
      await mp.goto(base + p);
      expect(new URL(mp.url()).pathname === p, `redirecționat de pe ${p} la ${mp.url()}`);
      await noHorizontalScroll(mp);
    }
    await shot(mp, '12-mobile-account');
    expect(m.errors.length === 0, `erori în consolă (cont mobil):\n${m.errors.join('\n')}`);
    await m.ctx.close();
  });

  expect(errors.length === 0, `erori în consolă:\n${errors.join('\n')}`);
  await ctx.close();

  // ─── administrator ───
  const admin = await newContext(browser, server.base);
  const ap = await admin.ctx.newPage();
  await step('admin: autentificare, dashboard cu grafice', async () => {
    await ap.goto(`${server.base}/admin`);
    await ap.fill('input[name=email]', 'admin@livada.ro');
    await ap.fill('input[name=password]', 'E2e-Admin-2026');
    await Promise.all([ap.waitForURL((u) => u.pathname === '/admin'), ap.click('button[type=submit]')]);
    expect(await ap.locator('svg').count() > 3, 'dashboardul nu are grafice');
    await shot(ap, '12-admin-dashboard');
  });

  await step('admin: comanda plătită (confirmată automat) → „Expediată” cu AWB + email către client', async () => {
    await ap.goto(`${server.base}/admin/comenzi?q=${orderNumber}`);
    await Promise.all([ap.waitForURL(/\/admin\/comenzi\/\d+/), ap.locator(`text=${orderNumber}`).first().click()]);
    await ap.locator('input[name=status][value=shipped]').check({ force: true });
    await ap.fill('input[name=awb]', '2345678901');
    await Promise.all([ap.waitForLoadState('load'), ap.click('form[action$="/status"] button')]);
    await ap.waitForTimeout(300);
    const m = await latestMail(server.base, email);
    expect(/expediat/i.test(m.subject) && m.subject.includes(orderNumber), `emailul de status lipsește: ${m.subject}`);
    expect(await ap.locator('text=2345678901').count() > 0, 'AWB-ul nu apare pe comandă');
    await shot(ap, '13-admin-order');
  });
  expect(admin.errors.length === 0, `erori în consolă (admin):\n${admin.errors.join('\n')}`);
  await admin.ctx.close();

  // ─── mobil ───
  const mob = await newContext(browser, server.base, { mobile: true });
  const mp = await mob.ctx.newPage();
  await step('mobil: fără scroll orizontal pe nicio pagină din sitemap', async () => {
    // toate paginile din sitemap (categorii, cele ~100 de produse, pagini statice) + paginile de cont și erori
    const sitemap = await (await fetch(`${server.base}/sitemap.xml`)).text();
    const pages = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => new URL(m[1]).pathname);
    expect(pages.length > 100, `sitemap prea scurt: ${pages.length}`);
    for (const p of [...pages, '/oferte', '/noutati', '/cos', '/cont/autentificare', '/cont/inregistrare', '/cont/parola-uitata', '/cautare?q=ipad', '/pagina-inexistenta']) {
      await mp.goto(server.base + p);
      await noHorizontalScroll(mp);
    }
  });
  await step('mobil: meniu, adăugare în coș, checkout cu harta lockerelor', async () => {
    await mp.goto(server.base);
    await mp.click('[data-open-menu]');
    await mp.waitForTimeout(350);
    await shot(mp, '14-mobile-menu');
    await mp.goto(`${server.base}/produs/airpods-pro-3`);
    await mp.click('[data-add-btn]');
    await mp.waitForTimeout(500);
    await mp.goto(`${server.base}/checkout`);
    await noHorizontalScroll(mp);
    await mp.click('[data-ship-tab=locker]');
    await mp.click('[data-open-lockers]');
    await mp.waitForSelector('.litem', { timeout: 10000 });
    await mp.waitForTimeout(400);
    await shot(mp, '15-mobile-lockers');
  });
  expect(mob.errors.length === 0, `erori în consolă (mobil):\n${mob.errors.join('\n')}`);
  await mob.ctx.close();
} catch (e) {
  failed = true;
  if (!results.some((r) => !r.ok)) console.log(`  ✗ ${e.message}`);
} finally {
  await browser.close();
  server.stop();
}

const ok = results.filter((r) => r.ok).length;
console.log(`\n${ok}/${results.length} pași reușiți${failed ? ' — EȘEC' : ''}. Capturi: ${path.relative(process.cwd(), OUT)}/\n`);
process.exit(failed ? 1 : 0);
