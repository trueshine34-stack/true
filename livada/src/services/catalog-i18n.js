// Catalogul în engleză: un „overlay” peste textele românești din baza de date.
// Produsele se editează în admin doar în română; pe paginile /en/… serviciul de catalog trece fiecare
// produs / categorie prin funcțiile de mai jos, care înlocuiesc textele cu traducerea din data/i18n.
// Ce lipsește din overlay rămâne în română (tools/catalog_i18n_check.mjs listează lipsurile).
//
// FIȘIERE: data/i18n/catalog-*.en.json — toate se combină (deep merge, în ordine alfabetică; la chei
// identice câștigă fișierul de mai târziu). În development se reîncarcă singure când se modifică.
//
// FORMAT (cheile sunt textele / cheile românești din catalog):
// {
//   "categories": { "<slug>": { "name": "…", "title": "…", "tagline": "…",
//                               "groups": { "<grup RO>": "<grup EN>" } } },
//   "labels": { "<RO>": "<EN>" },   // generic: etichete de opțiuni („Culoare”), titluri de grupe și etichete de specificații
//   "values": { "<RO>": "<EN>" },   // generic: valori de opțiuni (culori!) și notițele lor, valori de specificații („Da”),
//                                   //          texte libere repetate („Documentație”, garanția)
//   "products": { "<slug>": {
//     "name"?: "…", "tagline"?: "…", "release"?: "…",                      // release = nota de lansare (release_note)
//     "description"?: ["…"], "highlights"?: ["…"], "in_box"?: ["…"],      // pe poziții, ca în lista românească
//     "specs"?: [{ "group": "…", "rows": [{ "k": "…", "v": "…" }] }],      // aceeași formă ca specificațiile RO, pe poziții
//     "options"?: { "<cheia opțiunii>": { "label"?: "…", "note"?: "…",
//                   "values"?: { "<cheia valorii>": "<eticheta EN>", "<cheia valorii>@note": "<notița EN (hint)>" } } },
//     "text"?: { "<text RO exact>": "<EN>" }   // opțional: traduceri după textul exact, pentru orice text al produsului
//   } }                                       // (ex. o versiune mai veche a descrierii, rămasă în bazele existente)
// }
//
// Ordinea de căutare pentru fiecare text al unui produs:
//   products.<slug>.text["<RO>"] → intrarea specifică produsului (pe poziție / pe cheie) → labels|values["<RO>"] → RO.
// Listele (description, highlights, in_box, specs) urmează lista românească: un element RO în plus față de
// traducere cade pe values / RO, iar elementele EN în plus sunt ignorate.
// Nu se traduc: badge (cheie RO, tradusă în șabloane cu t()), slug-uri, chei de opțiuni / valori, prețuri.
// Categoriile: groups devine lista tradusă (etichetele tab-urilor), group_keys păstrează numele românești
// în aceeași ordine; listProducts({ group }) acceptă ambele forme.
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, config } from '../config.js';
import { lang } from '../lib/i18n.js';

const DIR = path.join(ROOT, 'data/i18n');
const FILE = /^catalog-.+\.en\.json$/;

/** produsul românesc din care s-a construit varianta tradusă (pentru logica ce depinde de textele RO) */
export const RO = Symbol('ro');
export const roOf = (p) => (p && p[RO]) || p;

const isObj = (x) => x !== null && typeof x === 'object' && !Array.isArray(x);
const str = (x) => (typeof x === 'string' && x !== '' ? x : undefined);
const own = (o, k) => (o && typeof k === 'string' && Object.hasOwn(o, k) ? o[k] : undefined);

function merge(a, b) {
  for (const [k, v] of Object.entries(b)) {
    if (k === '__proto__') continue;
    a[k] = isObj(v) && isObj(a[k]) ? merge(a[k], v) : v;
  }
  return a;
}

let ov = null;
let stamp = '';
let checkedAt = 0;
let version = 0;

const files = () => {
  try { return fs.readdirSync(DIR).filter((f) => FILE.test(f)).sort(); } catch { return []; }
};
const stampOf = (list) => list.map((f) => {
  try { return `${f}:${fs.statSync(path.join(DIR, f)).mtimeMs}`; } catch { return f; }
}).join('|');

function load(list = files()) {
  const o = { categories: {}, labels: {}, values: {}, products: {} };
  const sources = [];
  for (const f of list) {
    try {
      const data = JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
      if (isObj(data)) { merge(o, data); sources.push({ file: f, data }); }
    } catch (e) {
      console.error(`[livada] catalog i18n: ${f} nu e JSON valid — ${e.message}`);
    }
  }
  for (const k of ['categories', 'labels', 'values', 'products']) if (!isObj(o[k])) o[k] = {};
  // grupele: RO → EN (pe categorie și global) și invers, pentru filtrul ?grup= din paginile în engleză
  o.groupEn = new Map();
  o.groupRo = new Map();
  for (const [slug, c] of Object.entries(o.categories)) {
    for (const [ro, en] of Object.entries(isObj(c?.groups) ? c.groups : {})) {
      if (!str(en)) continue;
      o.groupEn.set(`${slug}\u0000${ro}`, en);
      if (!o.groupEn.has(ro)) o.groupEn.set(ro, en);
      o.groupRo.set(`${slug}\u0000${en}`, ro);
      if (!o.groupRo.has(en)) o.groupRo.set(en, ro);
    }
  }
  Object.defineProperty(o, 'sources', { value: sources });
  ov = o;
  stamp = stampOf(list);
  version++;
}

/** overlay-ul combinat (construit o dată; în development se reconstruiește când se schimbă fișierele) */
export function catalogOverlay() {
  if (!ov) load();
  else if (!config.production && Date.now() - checkedAt > 1000) {
    checkedAt = Date.now();
    const list = files();
    if (stampOf(list) !== stamp) load(list);
  }
  return ov;
}
export const overlayVersion = () => (catalogOverlay(), version);
export const reloadCatalogOverlay = () => load();

// ─── aplicare ─────────────────────────────────────────────────────────────────
// tr(): traducerea unui text RO; miss(ce, text) e apelat doar de verificator, când nu există traducere
function tr(s, specific, text, generic, miss, where) {
  if (typeof s !== 'string' || s === '') return s;
  const v = str(own(text, s)) ?? str(specific) ?? str(own(generic, s));
  if (v === undefined && miss) miss(where, s);
  return v ?? s;
}
const list = (arr, en, text, generic, miss, where) => (Array.isArray(arr)
  ? arr.map((s, i) => tr(s, Array.isArray(en) ? en[i] : undefined, text, generic, miss, `${where}[${i}]`))
  : arr);

/**
 * Traduce un produs deja „hidratat” (description, specs, options… ca obiecte). Întoarce un obiect nou;
 * produsul primit (RO) nu se modifică. În română întoarce produsul neschimbat.
 */
export function localizeProduct(p, { force = false, miss = null } = {}) {
  if (!p || (!force && lang() !== 'en')) return p;
  const o = catalogOverlay();
  const e = isObj(own(o.products, p.slug)) ? o.products[p.slug] : {};
  const T = isObj(e.text) ? e.text : null;
  const L = o.labels;
  const V = o.values;
  const out = { ...p, [RO]: p };
  if (p.name !== undefined) out.name = tr(p.name, e.name, T, null, miss, 'name');
  if (p.tagline !== undefined) out.tagline = tr(p.tagline, e.tagline, T, V, miss, 'tagline');
  // badge rămâne cheia românească („Nou”, „Precomandă”): șabloanele o traduc cu t() și aleg culoarea după ea
  if (p.warranty) out.warranty = tr(p.warranty, e.warranty ?? own(L, p.warranty), T, V, miss, 'warranty');
  if (p.release_note) out.release_note = tr(p.release_note, e.release, T, V, miss, 'release');
  if (p.grp) {
    const g = (p.category_slug && o.groupEn.get(`${p.category_slug}\u0000${p.grp}`)) || o.groupEn.get(p.grp);
    if (g) out.grp = g; else if (miss) miss('grp', p.grp);
  }
  if (p.category_slug) {
    const c = own(o.categories, p.category_slug);
    if (p.category_name !== undefined) out.category_name = str(c?.name) ?? p.category_name;
    if (p.category_title !== undefined) out.category_title = str(c?.title) ?? p.category_title;
  }
  out.description = list(p.description, e.description, T, V, miss, 'description');
  out.highlights = list(p.highlights, e.highlights, T, V, miss, 'highlights');
  out.in_box = list(p.in_box, e.in_box, T, V, miss, 'in_box');
  if (Array.isArray(p.specs)) {
    const es = Array.isArray(e.specs) ? e.specs : [];
    out.specs = p.specs.map((g, i) => {
      const eg = isObj(es[i]) ? es[i] : {};
      const er = Array.isArray(eg.rows) ? eg.rows : [];
      return {
        ...g,
        group: tr(g.group, eg.group, T, L, miss, `specs[${i}].group`),
        rows: Array.isArray(g.rows) ? g.rows.map((r, j) => ({
          ...r,
          k: tr(r.k, er[j]?.k, T, L, miss, `specs[${i}].rows[${j}].k`),
          v: tr(r.v, er[j]?.v, T, V, miss, `specs[${i}].rows[${j}].v`),
        })) : g.rows,
      };
    });
  }
  if (Array.isArray(p.options)) {
    const eo = isObj(e.options) ? e.options : {};
    out.options = p.options.map((op) => {
      const x = isObj(own(eo, op.key)) ? eo[op.key] : {};
      const xv = isObj(x.values) ? x.values : {};
      const res = { ...op, label: tr(op.label, x.label, T, L, miss, `options.${op.key}.label`) };
      if (op.note) res.note = tr(op.note, x.note, T, V, miss, `options.${op.key}.note`);
      if (Array.isArray(op.values)) {
        res.values = op.values.map((v) => {
          const rv = { ...v, label: tr(v.label, own(xv, v.value), T, V, miss, `options.${op.key}.values.${v.value}`) };
          if (v.hint) rv.hint = tr(v.hint, own(xv, `${v.value}@note`), T, V, miss, `options.${op.key}.values.${v.value}@note`);
          return rv;
        });
      }
      return res;
    });
  }
  return out;
}

/** traduce o categorie (name, title, tagline, groups); obiect nou, cel primit rămâne neschimbat */
export function localizeCategory(c, { force = false, miss = null } = {}) {
  if (!c || (!force && lang() !== 'en')) return c;
  const e = own(catalogOverlay().categories, c.slug);
  if (!isObj(e)) {
    if (miss) miss('category', c.slug);
    return c;
  }
  const g = isObj(e.groups) ? e.groups : {};
  const pickC = (k) => { const v = str(e[k]); if (v === undefined && c[k] && miss) miss(k, c[k]); return v ?? c[k]; };
  return {
    ...c,
    group_keys: c.groups,
    name: pickC('name'),
    title: pickC('title'),
    tagline: pickC('tagline'),
    groups: Array.isArray(c.groups) ? c.groups.map((x) => {
      const v = str(own(g, x));
      if (v === undefined && miss) miss('groups', x);
      return v ?? x;
    }) : c.groups,
  };
}

/** grupa din filtrul ?grup= → numele românesc din baza de date (acceptă și numele englezesc) */
export function groupKey(group, category) {
  if (!group) return group;
  const o = catalogOverlay();
  if (category && isObj(own(o.categories, category)?.groups) && Object.hasOwn(o.categories[category].groups, group)) return group;
  return (category && o.groupRo.get(`${category}\u0000${group}`)) || o.groupRo.get(group) || group;
}

/** textele unui produs tradus folosite de căutarea în engleză (ponderate ca indexul FTS românesc) */
export function searchFields(p) {
  return {
    name: p.name || '',
    tagline: p.tagline || '',
    grp: [p.grp, p.category_name].filter(Boolean).join(' '),
    body: [
      ...(p.highlights || []),
      ...(p.options || []).flatMap((o) => (o.values || []).map((v) => v.label)),
      ...(p.specs || []).flatMap((g) => (g.rows || []).map((r) => r.v)),
    ].join(' · '),
  };
}
