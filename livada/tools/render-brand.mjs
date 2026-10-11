// Randează PNG-urile de brand (logo email, iconițe, imagine Open Graph) cu Chromium (Playwright).
// Unealtă de dezvoltare: node tools/render-brand.mjs
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const B = path.join(ROOT, 'public/img/brand');
const svg = (f) => fs.readFileSync(path.join(B, f), 'utf8');
const font = (f) => `data:font/woff2;base64,${fs.readFileSync(path.join(ROOT, 'public/fonts', f)).toString('base64')}`;

const browser = await chromium.launch();
async function shot(name, w, h, body, { transparent = false, scale = 1 } = {}) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  await page.setContent(`<!doctype html><meta charset=utf-8><style>
    @font-face{font-family:Inter;src:url(${font('inter-latin.woff2')}) format('woff2');font-weight:100 900}
    @font-face{font-family:Serif;font-style:italic;src:url(${font('instrument-serif-italic-latin.woff2')}) format('woff2')}
    @font-face{font-family:Serif;font-style:italic;src:url(${font('instrument-serif-italic-latin-ext.woff2')}) format('woff2');unicode-range:U+0100-024F,U+0218-021B}
    @font-face{font-family:Inter;src:url(${font('inter-latin-ext.woff2')}) format('woff2');font-weight:100 900;unicode-range:U+0100-024F,U+0218-021B}
    html,body{margin:0;width:${w}px;height:${h}px;${transparent ? 'background:transparent' : ''}}</style>${body}`);
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(B, name), omitBackground: transparent });
  await page.close();
}

await shot('email-logo.png', 121, 30, `<div style="width:121px;height:30px">${svg('logo.svg').replace(/width="\d+" height="32"/, 'width="121" height="30"')}</div>`, { transparent: true, scale: 4 });
await shot('apple-touch-icon.png', 180, 180, svg('app-icon.svg').replace('<svg ', '<svg width="180" height="180" '));
await shot('icon-512.png', 512, 512, svg('app-icon.svg').replace('<svg ', '<svg width="512" height="512" '));
await shot('icon-192.png', 192, 192, svg('app-icon.svg').replace('<svg ', '<svg width="192" height="192" '));

// imaginea Open Graph implicită
const hero = (f) => `data:image/webp;base64,${fs.readFileSync(path.join(ROOT, 'public/img/products', f)).toString('base64')}`;
await shot('og-default.png', 1200, 630, `
<div style="position:relative;width:1200px;height:630px;background:#F4F5F1;font-family:Inter;overflow:hidden">
  <div style="position:absolute;left:72px;top:64px">${svg('logo.svg').replace(/width="\d+" height="32"/, 'width="194" height="48"')}</div>
  <div style="position:absolute;left:72px;top:200px;width:560px;font-size:68px;line-height:1.02;font-weight:700;letter-spacing:-0.035em;color:#0E1411">
    Tehnologia Apple,<br><span style="font-family:Serif;font-style:italic;font-weight:400;letter-spacing:-0.01em">culeasă cu grijă.</span></div>
  <div style="position:absolute;left:72px;top:430px;font-size:24px;color:#3A433E;line-height:1.5">iPhone · Mac · iPad · Watch · AirPods<br><b style="color:#1F7A4D">Livrare 24h în toată România</b></div>
  <img src="${hero('iphone-18-pro/burgundy-1-1000.webp')}" style="position:absolute;right:120px;top:40px;width:560px;height:560px">
  <img src="${hero('apple-watch-series-12/lightgold-1-480.webp')}" style="position:absolute;right:-40px;top:330px;width:320px;height:320px">
</div>`);
await browser.close();
console.log('ok');
