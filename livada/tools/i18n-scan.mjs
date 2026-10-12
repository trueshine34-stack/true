// Caută text rămas în română pe paginile în engleză (/en/…).
//   node tools/i18n-scan.mjs http://127.0.0.1:8080 [cale …] [--all] [--json]
// Fără căi: paginile din sitemap (în engleză) + paginile de cont și checkout care nu sunt în sitemap.
// Raportează textele vizibile și atributele (alt, aria-label, placeholder, title, meta description)
// cu diacritice românești sau cuvinte tipic românești. Numele proprii (adrese, localități) sunt ignorate.
const [, , base = 'http://127.0.0.1:8080', ...rest] = process.argv;
const flags = new Set(rest.filter((a) => a.startsWith('--')));
let paths = rest.filter((a) => !a.startsWith('--'));

const RO_WORDS = /\b(și|în|pentru|către|până|după|fără|cu|din|sau|este|sunt|prin|lei|Adaugă|Coș|Coșul|Livrare|Comandă|Comanda|Plată|Cumpără|Vezi|Toate|Alege|Caută|Gratuit|Produs|Produse|Detalii|Culoare|Stocare|Mărime|Carcasă|Retur|Garanție|Rate|Contul|Autentificare|Înregistrare|Parola|Telefon|Adresa|Județ|Localitate|Strada|Trimite|Salvează|Închide|Anulează|Continuă|Înapoi|Mâine|Azi|luni|marți|miercuri|joi|vineri|sâmbătă|duminică|ianuarie|februarie|martie|aprilie|mai|iunie|iulie|august|septembrie|octombrie|noiembrie|decembrie)\b/i;
const DIACRITICS = /[ăâîșțşţĂÂÎȘȚŞŢ]/;
// nume proprii care rămân la fel în engleză (adrese, localități, firme, rețele)
const IGNORE = [/București/, /Dorobanți/, /Polonă/, /Bucharest/, /Cluj|Brașov|Timișoara|Iași|Constanța|Craiova|Galați|Oradea|Ploiești|Sibiu|Arad|Târgu|Mureș|Bacău|Pitești|Suceava|Botoșani|Brăila|Buzău|Satu Mare|Râmnicu|Vâlcea|Neamț|Argeș|Dâmbovița|Ialomița|Călărași|Mehedinți|Bistrița|Năsăud|Sălaj|Caraș|Hunedoara|Maramureș|Covasna|Harghita|Teleorman|Giurgiu|Olt|Dolj|Gorj|Vaslui|Vrancea|Tulcea|Alba|Bihor|Prahova|Ilfov|Timiș/,
  /Livada Digital S\.R\.L\./, /Poșta Română/, /Banca Transilvania/, /S\.R\.L\./, /RO\d{2} /, /Str\.|Calea|Bulevardul|Strada|Șoseaua|Aleea/, /ANPC|SOL/, /lei\b/,
  // comutatorul de limbă e în română intenționat (numele limbii în limba ei)
  /Versiunea în română/, /\bRomână\b/];

function texts(html) {
  const out = [];
  const body = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<!--[\s\S]*?-->/g, ' ');
  for (const m of body.matchAll(/\s(?:alt|aria-label|placeholder|title|content)="([^"]{2,})"/g)) out.push(m[1]);
  for (const t of body.replace(/<[^>]+>/g, '\n').split('\n')) {
    const s = t.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/\s+/g, ' ').trim();
    if (s.length > 1) out.push(s);
  }
  return out;
}

async function get(p) {
  const r = await fetch(base + p, { redirect: 'manual', headers: { 'Accept-Language': 'en' } });
  return r.status === 200 ? r.text() : '';
}

if (!paths.length) {
  const xml = await get('/sitemap.xml');
  paths = [...xml.matchAll(/<loc>[^<]*?(\/en(?:\/[^<]*)?)<\/loc>/g)].map((m) => m[1]);
  paths.push('/en/cos', '/en/cont/autentificare', '/en/cont/inregistrare', '/en/cont/parola-uitata', '/en/cautare?q=iphone', '/en/nu-exista');
  if (!flags.has('--all')) {
    // câte un produs din fiecare categorie ajunge pentru a vedea șabloanele; --all le verifică pe toate
    const seen = new Set();
    paths = paths.filter((p) => {
      if (!p.startsWith('/en/produs/')) return true;
      const k = p.split('/')[3].split('-')[0];
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
  }
}

const found = new Map();
for (const p of paths) {
  const html = await get(p.startsWith('/en') ? p : `/en${p}`);
  if (!html) { console.error(`! ${p}: nu s-a putut încărca`); continue; }
  for (const s of texts(html)) {
    if (!(DIACRITICS.test(s) || RO_WORDS.test(s))) continue;
    if (/^(?:https?:\/\/|\/)\S*$/.test(s)) continue; // adrese (og:url, canonical): slugurile rămân în română
    const cleaned = IGNORE.reduce((x, re) => x.replace(new RegExp(re.source, 'g'), ''), s);
    if (!(DIACRITICS.test(cleaned) || RO_WORDS.test(cleaned))) continue;
    const e = found.get(s) || { n: 0, pages: new Set() };
    e.n++; e.pages.add(p); found.set(s, e);
  }
}
const rows = [...found.entries()].sort((a, b) => b[1].pages.size - a[1].pages.size);
if (flags.has('--json')) {
  console.log(JSON.stringify(rows.map(([s, e]) => ({ s, pages: [...e.pages].slice(0, 5), count: e.pages.size })), null, 1));
} else {
  for (const [s, e] of rows) console.log(`${String(e.pages.size).padStart(4)}  ${s.slice(0, 140)}   [${[...e.pages].slice(0, 2).join(', ')}]`);
  console.error(`\n${rows.length} texte suspecte pe ${paths.length} pagini`);
}
