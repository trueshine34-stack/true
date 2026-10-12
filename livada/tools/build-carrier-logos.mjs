// Logo-urile originale ale curierilor → public/img/carriers/
//   node tools/build-carrier-logos.mjs
// Sursele (tools/data/carrier-logos/) sunt fișierele publicate pe site-urile curierilor:
//   fan.svg      fancourier.ro/wp-content/uploads/2023/03/logo.svg
//   dpd.svg      dpd.com/wp-content/themes/DPD_NoLogin/images/DPD_logo_redgrad_rgb_responsive.svg
//   gls.svg      gls-group.com (logo albastru-galben)
//   sameday.svg  sameday.hu/app/themes/samedaytwo/public/images/logo/logo-sameday.svg
//   packeta.svg  packeta.ro (logo din antet)
//   posta.svg    Poșta Română, logo 2025 (Wikimedia Commons)
//   cargus.png   cargus.ro/wp-content/uploads/CG_LOGO_OGB_RGB-1.svg (Cargus îl publică doar ca imagine)
//   easybox.svg  commons.wikimedia.org/wiki/File:Sameday_easybox_logo.svg (domeniu public)
// Pentru fiecare: logo întreg (<cheie>.svg) și semn compact pentru pini și insigne (<cheie>-mark.svg),
// plus PNG-uri pentru emailuri (clienții de email nu afișează SVG).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const SRC = path.join(ROOT, 'tools/data/carrier-logos');
const OUT = path.join(ROOT, 'public/img/carriers');
fs.mkdirSync(OUT, { recursive: true });

/** curăță un SVG primit din afară: fără scripturi, handlere, referințe externe, metadate de editor */
function sanitize(svg, { viewBox, dropPatterns = false, keep } = {}) {
  let s = svg
    .replace(/<\?xml[^>]*>/g, '').replace(/<!DOCTYPE[^>]*>/gi, '').replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<foreignObject[\s\S]*?<\/foreignObject>/gi, '')
    .replace(/<metadata[\s\S]*?<\/metadata>/gi, '').replace(/<sodipodi:[\s\S]*?\/>/gi, '').replace(/<inkscape:[\s\S]*?\/>/gi, '')
    .replace(/\s(?:on\w+|sodipodi:\w+|inkscape:\w+|data-[\w-]+)(?:="[^"]*")?/gi, '')
    .replace(/\s(?:xlink:)?href="(?!#|data:image\/(?:png|jpeg);base64,)[^"]*"/gi, '');
  if (keep) {
    // semnul compact: păstrăm doar desenele numite (ex.: cutia din sigla easybox, fără literele de lângă ea)
    s = s.replace(/<path\b[^>]*\/>/g, (m) => (keep.some((id) => m.includes(`id="${id}"`)) ? m : ''));
  }
  if (dropPatterns) {
    // umbrele raster din logo-ul Sameday (sute de KB) nu se văd la dimensiunea unei insigne
    s = s.replace(/<rect[^>]*fill="url\(#pattern[^"]*\)"[^>]*\/>/g, '').replace(/<defs>[\s\S]*<\/defs>/g, '');
  }
  s = s.replace(/<svg\b([^>]*)>/, (m, attrs) => {
    let a = attrs.replace(/\s(width|height|viewBox|id|version|xml:space|style|class|aria-hidden)="[^"]*"/g, '');
    const vb = viewBox || (attrs.match(/viewBox="([^"]*)"/) || [])[1];
    const [, , w, h] = vb.split(/[\s,]+/).map(Number);
    if (!/xmlns="/.test(a)) a += ' xmlns="http://www.w3.org/2000/svg"';
    return `<svg${a} viewBox="${vb}" width="${+(w / h * 64).toFixed(2)}" height="64">`;
  });
  return s.replace(/\n\s*\n/g, '\n').trim() + '\n';
}

const read = (f) => fs.readFileSync(path.join(SRC, f), 'utf8');
const write = (f, s) => { fs.writeFileSync(path.join(OUT, f), s); return f; };

const LOGOS = {
  fan: { full: [read('fan.svg'), {}], mark: [read('fan.svg'), { viewBox: '0 0 148 61' }] },
  dpd: { full: [read('dpd.svg'), {}], mark: [read('dpd.svg'), { viewBox: '81 296 172 200' }] },
  gls: { full: [read('gls.svg'), { viewBox: '9 8 94 34' }], mark: [read('gls.svg'), { viewBox: '9 8 94 34' }] },
  sameday: { full: [read('sameday.svg'), { dropPatterns: true }], mark: [read('sameday.svg'), { viewBox: '5.7 -0.6 38.5 36.4', dropPatterns: true }] },
  packeta: { full: [read('packeta.svg'), { viewBox: '0 0.5 145 40' }], mark: [read('packeta.svg'), { viewBox: '-1 0.5 38 40' }] },
  posta: { full: [read('posta.svg'), {}], mark: [read('posta.svg'), {}] },
  // easybox: sigla de pe Wikimedia Commons („Sameday easybox logo.svg”, domeniu public — doar text și formă simplă)
  easybox: { full: [read('easybox.svg'), { viewBox: '0 0 1001 252.25' }], mark: [read('easybox.svg'), { viewBox: '818 -6 189 200', keep: ['pzjKD9vdy', 'pioNowzeZ'] }] },
};
for (const [key, { full, mark }] of Object.entries(LOGOS)) {
  write(`${key}.svg`, sanitize(...full));
  write(`${key}-mark.svg`, sanitize(...mark));
}

const browser = await chromium.launch();
const page = await browser.newPage({ deviceScaleFactor: 1 });
const b64 = (f) => `data:image/png;base64,${fs.readFileSync(f).toString('base64')}`;

// Cargus: logo întreg și steagul portocaliu, decupate din imaginea oficială (3200×2465)
const cargus = b64(path.join(SRC, 'cargus.png'));
async function crop(file, box, outW) {
  const [x, y, w, h] = box;
  const k = outW / w;
  await page.setViewportSize({ width: Math.round(w * k), height: Math.round(h * k) });
  await page.setContent(`<!doctype html><style>html,body{margin:0;background:transparent;overflow:hidden}</style>
    <div style="width:${w * k}px;height:${h * k}px;overflow:hidden;position:relative">
    <img src="${cargus}" style="position:absolute;left:${-x * k}px;top:${-y * k}px;width:${3200 * k}px;height:${2465 * k}px"></div>`);
  await page.waitForTimeout(80);
  await page.screenshot({ path: path.join(OUT, file), omitBackground: true });
}
await crop('cargus.png', [40, 28, 3124, 2410], 480);
await crop('cargus-mark.png', [800, 28, 1622, 1748], 192);

// PNG pentru emailuri: semnul (64 px) și logo-ul întreg (înălțime 48 px), la densitate 2×
await page.close();
const p2 = await browser.newPage({ deviceScaleFactor: 2 });
for (const key of [...Object.keys(LOGOS), 'cargus']) {
  const ext = key === 'cargus' ? 'png' : 'svg';
  const src = key === 'cargus' ? b64(path.join(OUT, 'cargus.png')) : `data:image/svg+xml;base64,${fs.readFileSync(path.join(OUT, `${key}.${ext}`)).toString('base64')}`;
  await p2.setViewportSize({ width: 160, height: 48 });
  await p2.setContent(`<!doctype html><style>html,body{margin:0;background:transparent}div{width:160px;height:48px;display:flex;align-items:center;justify-content:center}
    img{max-width:150px;max-height:40px}</style><div><img src="${src}"></div>`);
  await p2.waitForTimeout(80);
  const box = await p2.evaluate(() => { const r = document.querySelector('img').getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; });
  await p2.screenshot({ path: path.join(OUT, `${key}-email.png`), omitBackground: true, clip: box });
}
await browser.close();
for (const f of fs.readdirSync(OUT).sort()) console.log(f.padEnd(24), fs.statSync(path.join(OUT, f)).size);
