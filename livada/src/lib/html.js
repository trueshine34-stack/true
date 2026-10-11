// Șabloane HTML sigure: orice valoare interpolată este escapată, cu excepția celor marcate raw().

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export class Raw {
  constructor(s) { this.s = s; }
  toString() { return this.s; }
}

export const raw = (s) => new Raw(s instanceof Raw ? s.s : String(s ?? ''));
export const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

function render(v) {
  if (v === null || v === undefined || v === false || v === true) return '';
  if (v instanceof Raw) return v.s;
  if (Array.isArray(v)) return v.map(render).join('');
  return esc(v);
}

export function html(strings, ...vals) {
  let out = strings[0];
  for (let i = 0; i < vals.length; i++) out += render(vals[i]) + strings[i + 1];
  return new Raw(out);
}

/** class names condiționale: cx('btn', active && 'is-active') */
export const cx = (...xs) => xs.flat().filter(Boolean).join(' ');

/** atribut condițional: attr('checked', isChecked) */
export const attr = (name, on, value) => (on ? raw(value === undefined ? ` ${name}` : ` ${name}="${esc(value)}"`) : '');

/** JSON sigur pentru <script type="application/json"> */
export function jsonScript(id, data) {
  const s = JSON.stringify(data).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return raw(`<script type="application/json" id="${esc(id)}">${s}</script>`);
}

export function jsonLd(data) {
  const s = JSON.stringify(data).replace(/</g, '\\u003c');
  return raw(`<script type="application/ld+json">${s}</script>`);
}
