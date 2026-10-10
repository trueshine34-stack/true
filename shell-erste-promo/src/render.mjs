// Frame renderer: drives index.html in headless Chromium and writes JPEG/PNG frames.
//   node render.mjs --out DIR [--times 0.5,3.2] [--workers 4] [--png]
import { createRequire } from 'module';
import { mkdirSync, writeFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
const require = createRequire(import.meta.url);
let pw;
try { pw = require('playwright'); } catch { pw = require('/opt/node22/lib/node_modules/playwright'); }

const here = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, arr) => {
  if (a.startsWith('--')) acc.push([a.slice(2), arr[i + 1] && !arr[i + 1].startsWith('--') ? arr[i + 1] : true]);
  return acc;
}, []));
const FPS = 25, DURATION = 15;
const out = args.out || join(here, '..', 'build', 'frames');
mkdirSync(out, { recursive: true });
const png = !!args.png;
const jobs = args.times
  ? String(args.times).split(',').map(s => ({ t: parseFloat(s), name: `still_${parseFloat(s).toFixed(2)}` }))
  : Array.from({ length: FPS * DURATION }, (_, i) => ({ t: i / FPS, name: `f_${String(i).padStart(4, '0')}` }));
const workers = Math.min(parseInt(args.workers || 4, 10), jobs.length);

const browser = await pw.chromium.launch({ args: ['--disable-web-security', '--allow-file-access-from-files'] });
const t0 = Date.now();
let done = 0;
await Promise.all(Array.from({ length: workers }, async (_, w) => {
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') console.error(`[w${w}]`, m.text()); });
  page.on('pageerror', e => console.error(`[w${w}] pageerror`, e.message));
  await page.goto('file://' + join(here, 'index.html'));
  await page.waitForFunction(() => window.READY === true, null, { timeout: 120000 });
  for (let i = w; i < jobs.length; i += workers) {
    const { t, name } = jobs[i];
    await page.evaluate(tt => window.renderFrame(tt), t);
    const buf = await page.screenshot({ type: png ? 'png' : 'jpeg', quality: png ? undefined : 96, animations: 'disabled' });
    writeFileSync(join(out, `${name}.${png ? 'png' : 'jpg'}`), buf);
    done++;
    if (done % 25 === 0) console.log(`${done}/${jobs.length} frames, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  }
  await ctx.close();
}));
await browser.close();
console.log(`rendered ${jobs.length} frames in ${((Date.now() - t0) / 1000).toFixed(1)}s -> ${out}`);
