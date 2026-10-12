// Verifică traducerea în engleză a catalogului (overlay-ul din data/i18n/catalog-*.en.json).
//   node --disable-warning=ExperimentalWarning tools/catalog_i18n_check.mjs [--db=cale/livada.db | --seed]
//        [--category=iphone,mac | --devices | --accessories] [--inactive] [--json] [--quiet]
// Implicit citește baza din DATA_DIR (ca serverul), doar în citire; --seed verifică data/catalog.json.
// Pentru fiecare produs listează textele românești fără traducere (nume, tagline, descriere, highlights,
// în cutie, specificații, opțiuni, notițe, nota de lansare, grupa) și categoriile incomplete.
// Textele „neutre” (cu cifre și fără cuvinte românești: „256 GB”, „M5 · CPU 10 / GPU 10”) și numele de produs
// fără cuvinte românești nu au nevoie de traducere. Avertismente („outdated”): texte din bază diferite de
// data/catalog.json pentru care traducerea e pe poziție — traducerea poate fi învechită.
// Ieșire cu cod 1 dacă lipsesc traduceri în selecție.
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { ROOT, config } from '../src/config.js';
import { catalogOverlay, localizeProduct, localizeCategory } from '../src/services/catalog-i18n.js';

const args = process.argv.slice(2);
const opt = (k) => {
  const a = args.find((x) => x === `--${k}` || x.startsWith(`--${k}=`));
  return a === undefined ? undefined : a.includes('=') ? a.slice(a.indexOf('=') + 1) : true;
};
const parse = (s, fb) => { try { return s ? JSON.parse(s) : fb; } catch { return fb; } };

const DIAC = /[ăâîșțşţĂÂÎȘȚŞŢ]/;
const RO_WORDS = /\b(si|in|cu|din|de|la|pentru|sau|fara|pana|catre|prin|este|sunt|curea|cablu|adaptor|husa|bratara|portofel|tastatura|incarcator|carcasa|alb|negru|albastru|verde|galben|roz|mov|gri|auriu|argintiu|portocaliu|rosu|maro|bej|crem|nuclee|ore|luni|zile|bucata|bucati|marime|culoare|stocare|livrare|garantie|documentatie)\b/i;
const fold = (s) => s.normalize('NFD').replace(/[̀-ͯ]/g, '');
const romanian = (s) => DIAC.test(s) || RO_WORDS.test(fold(s));
const neutral = (where, s) => !romanian(s) && (where === 'name' || /\d/.test(s));

// ─── produsele și categoriile, în forma întoarsă de serviciul de catalog ─────────────────────────────
const seedFile = path.join(ROOT, 'data/catalog.json');
const seed = JSON.parse(fs.readFileSync(seedFile, 'utf8'));
let products;
let cats;
if (opt('seed')) {
  cats = seed.categories.map((c) => ({ ...c }));
  products = seed.products.map((p) => ({ ...p, grp: p.group, release_note: p.release, category_slug: p.category, active: true }));
} else {
  const file = typeof opt('db') === 'string' ? opt('db') : config.dbFile;
  if (!fs.existsSync(file)) { console.error(`Baza ${file} nu există (setează DATA_DIR sau --db=…, ori folosește --seed).`); process.exit(2); }
  const db = new DatabaseSync(file, { readOnly: true });
  cats = db.prepare('SELECT * FROM categories ORDER BY sort').all().map((c) => ({ ...c, groups: parse(c.groups, []) }));
  products = db.prepare(`SELECT p.*, c.slug AS category_slug, c.name AS category_name, c.title AS category_title
                         FROM products p JOIN categories c ON c.id = p.category_id ORDER BY c.sort, p.sort`).all()
    .map((p) => ({ ...p, active: !!p.active, ...Object.fromEntries(['description', 'highlights', 'specs', 'in_box', 'options'].map((k) => [k, parse(p[k], [])])) }));
  db.close();
}
if (!opt('inactive')) products = products.filter((p) => p.active);
const catFilter = typeof opt('category') === 'string' ? new Set(opt('category').split(',')) : null;
const pickP = (p) => (catFilter ? catFilter.has(p.category_slug) : opt('devices') ? p.category_slug !== 'accesorii' : opt('accessories') ? p.category_slug === 'accesorii' : true);
products = products.filter(pickP);

const ov = catalogOverlay();
const seedBySlug = new Map(seed.products.map((p) => [p.slug, { ...p, release_note: p.release }]));

// ─── verificare ─────────────────────────────────────────────────────────────────────────────────────
const report = [];
let missingTotal = 0;
let outdatedTotal = 0;
for (const p of products) {
  const missing = [];
  localizeProduct(p, { force: true, miss: (where, s) => { if (!neutral(where, s)) missing.push({ where, s }); } });
  // texte modificate față de data/catalog.json, traduse pe poziție (fără intrare în „text”)
  const outdated = [];
  const e = ov.products[p.slug];
  const ref = seedBySlug.get(p.slug);
  if (e && ref && !opt('seed')) {
    const T = e.text || {};
    const cmp = (where, cur, old, has) => { if (has && typeof cur === 'string' && cur !== old && !Object.hasOwn(T, cur)) outdated.push({ where, s: cur }); };
    cmp('tagline', p.tagline, ref.tagline, e.tagline);
    cmp('release', p.release_note, ref.release_note, e.release);
    for (const f of ['description', 'highlights', 'in_box']) (p[f] || []).forEach((s, i) => cmp(`${f}[${i}]`, s, ref[f]?.[i], e[f]?.[i]));
    (p.specs || []).forEach((g, i) => {
      cmp(`specs[${i}].group`, g.group, ref.specs?.[i]?.group, e.specs?.[i]?.group);
      (g.rows || []).forEach((r, j) => {
        cmp(`specs[${i}].rows[${j}].k`, r.k, ref.specs?.[i]?.rows?.[j]?.k, e.specs?.[i]?.rows?.[j]?.k);
        cmp(`specs[${i}].rows[${j}].v`, r.v, ref.specs?.[i]?.rows?.[j]?.v, e.specs?.[i]?.rows?.[j]?.v);
      });
    });
  }
  missingTotal += missing.length;
  outdatedTotal += outdated.length;
  report.push({ slug: p.slug, category: p.category_slug, missing, outdated });
}
const catReport = [];
for (const c of cats.filter((x) => !catFilter || catFilter.has(x.slug))) {
  const missing = [];
  localizeCategory(c, { force: true, miss: (where, s) => missing.push({ where, s }) });
  missingTotal += missing.length;
  catReport.push({ slug: c.slug, missing });
}

// chei generice definite diferit în mai multe fișiere (câștigă fișierul de mai târziu)
const conflicts = [];
for (const sec of ['labels', 'values']) {
  const seen = new Map();
  for (const { file, data } of ov.sources) {
    for (const [k, v] of Object.entries(data[sec] || {})) {
      const prev = seen.get(k);
      if (prev && prev.v !== v) conflicts.push({ sec, key: k, [prev.file]: prev.v, [file]: v });
      seen.set(k, { file, v });
    }
  }
}

if (opt('json')) {
  console.log(JSON.stringify({ products: report, categories: catReport, conflicts, missingTotal, outdatedTotal }, null, 1));
} else {
  const byCat = new Map();
  for (const r of report) {
    const x = byCat.get(r.category) || { n: 0, ok: 0 };
    x.n++; if (!r.missing.length) x.ok++;
    byCat.set(r.category, x);
    if (!r.missing.length && !r.outdated.length) continue;
    if (opt('quiet') && !r.missing.length) continue;
    console.log(`\n${r.slug} (${r.category})`);
    for (const m of r.missing) console.log(`  lipsă     ${m.where.padEnd(28)} ${m.s.slice(0, 110)}`);
    if (!opt('quiet')) for (const m of r.outdated) console.log(`  învechit? ${m.where.padEnd(28)} ${m.s.slice(0, 110)}`);
  }
  for (const c of catReport) if (c.missing.length) console.log(`\ncategoria ${c.slug}: ${c.missing.map((m) => `${m.where}=${m.s}`).join(', ')}`);
  for (const c of conflicts) console.log(`\nconflict ${c.sec} ${JSON.stringify(c)}`);
  console.log('\nProduse traduse complet, pe categorii:');
  for (const [k, x] of byCat) console.log(`  ${k.padEnd(12)} ${x.ok}/${x.n}`);
  console.log(`\n${missingTotal} texte fără traducere · ${outdatedTotal} posibil învechite · ${conflicts.length} conflicte · ${products.length} produse, ${catReport.length} categorii`);
}
process.exit(missingTotal ? 1 : 0);
