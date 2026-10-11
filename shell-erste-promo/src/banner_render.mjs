// Renders the FB banners to PNG + JPEG: node src/banner_render.mjs [outDir]
import { createRequire } from 'module';
import { mkdirSync, writeFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
const require = createRequire(import.meta.url);
let pw;
try { pw = require('playwright'); } catch { pw = require('/opt/node22/lib/node_modules/playwright'); }
const here = dirname(fileURLToPath(import.meta.url));
const out = process.argv[2] || join(here, '..', 'out', 'banners');
mkdirSync(out, { recursive: true });
const JOBS = [
  ['A', 1080, 1350, 'Shell_Erste_FB_A_aranyora_4x5'],
  ['A', 1080, 1080, 'Shell_Erste_FB_A_aranyora_1x1'],
  ['B', 1080, 1350, 'Shell_Erste_FB_B_0Ft_4x5'],
  ['B', 1080, 1080, 'Shell_Erste_FB_B_0Ft_1x1'],
];
const browser = await pw.chromium.launch({ args: ['--allow-file-access-from-files'] });
for (const [id, w, h, name] of JOBS) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.error('pageerror', e.message));
  page.on('console', m => { if (m.type() === 'error') console.error(m.text()); });
  await page.goto('file://' + join(here, 'banners.html'));
  await page.waitForFunction(() => window.READY === true);
  await page.evaluate(([i, ww, hh]) => window.drawBanner(i, ww, hh), [id, w, h]);
  const png = await page.locator('#c').screenshot({ type: 'png' });
  writeFileSync(join(out, `${name}.png`), png);
  const jpg = await page.locator('#c').screenshot({ type: 'jpeg', quality: 93 });
  writeFileSync(join(out, `${name}.jpg`), jpg);
  console.log('wrote', name);
  await page.close();
}
await browser.close();
