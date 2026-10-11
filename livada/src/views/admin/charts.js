// Grafice SVG randate pe server. Interactivitatea (crosshair + tooltip) e adăugată de admin.js.
import { html, raw, esc } from '../../lib/html.js';

const compact = (v) => {
  const a = Math.abs(v);
  if (a >= 1e6) return `${(v / 1e6).toFixed(a >= 1e7 ? 0 : 1).replace('.', ',')}M`;
  if (a >= 1e3) return `${(v / 1e3).toFixed(a >= 1e4 ? 0 : 1).replace('.', ',')}K`;
  return String(Math.round(v));
};

function niceMax(v) {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p;
}

const dayLabel = (d) => { const [, m, day] = d.split('-'); return `${Number(day)}.${m}`; };

/**
 * Grafic linie + zonă pentru o singură serie temporală.
 * @param {{day:string, value:number, label:string}[]} pts
 */
export function areaChart(pts, { id, title, unitFormat = (v) => v, height = 240 } = {}) {
  const W = 760, H = height, L = 44, R = 12, T = 12, B = 26;
  const max = niceMax(Math.max(...pts.map((p) => p.value), 1));
  const x = (i) => L + (i * (W - L - R)) / Math.max(1, pts.length - 1);
  const y = (v) => T + (H - T - B) * (1 - v / max);
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join('');
  const area = `${line}L${x(pts.length - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z`;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => max * f);
  const last = pts[pts.length - 1];
  const data = pts.map((p, i) => ({ x: +x(i).toFixed(1), y: +y(p.value).toFixed(1), d: p.day, v: p.label }));
  return html`<figure class="chart" data-chart=${id}>
    <svg viewBox=${`0 0 ${W} ${H}`} role="img" aria-label=${title} preserveAspectRatio="none" class="chart__svg">
      ${ticks.map((t) => raw(`<line class="chart__grid" x1="${L}" x2="${W - R}" y1="${y(t)}" y2="${y(t)}"/><text class="chart__tick" x="${L - 8}" y="${y(t) + 4}" text-anchor="end">${esc(compact(t / 100))}</text>`))}
      ${pts.map((p, i) => (i % 5 === 0 || i === pts.length - 1 ? raw(`<text class="chart__tick" x="${x(i)}" y="${H - 6}" text-anchor="middle">${dayLabel(p.day)}</text>`) : ''))}
      ${raw(`<path class="chart__area" d="${area}"/><path class="chart__line" d="${line}"/>`)}
      ${raw(`<circle class="chart__dot" cx="${x(pts.length - 1)}" cy="${y(last.value)}" r="4.5"/>`)}
      ${raw(`<line class="chart__cross" x1="0" x2="0" y1="${T}" y2="${H - B}" visibility="hidden"/><circle class="chart__hover" r="5" visibility="hidden"/>`)}
    </svg>
    <div class="chart__tip" hidden></div>
    <script type="application/json" class="chart__data">${raw(JSON.stringify({ W, H, pts: data }).replace(/</g, '\\u003c'))}</script>
  </figure>`;
}

/** coloane (o serie) — ex. numărul de comenzi pe zi */
export function columnChart(pts, { id, title, height = 140 } = {}) {
  const W = 760, H = height, L = 30, R = 12, T = 10, B = 22;
  const max = niceMax(Math.max(...pts.map((p) => p.value), 1));
  const band = (W - L - R) / pts.length;
  const bw = Math.min(24, Math.max(4, band - 4));
  const y = (v) => T + (H - T - B) * (1 - v / max);
  const bars = pts.map((p, i) => {
    const bx = L + i * band + (band - bw) / 2;
    const top = y(p.value);
    const h = Math.max(0, y(0) - top);
    const r = Math.min(4, h / 2, bw / 2);
    // capăt rotunjit sus, pătrat la bază
    const d = h <= 0 ? '' : `M${bx},${y(0)}V${top + r}Q${bx},${top} ${bx + r},${top}H${bx + bw - r}Q${bx + bw},${top} ${bx + bw},${top + r}V${y(0)}Z`;
    return `<path class="chart__bar" d="${d}"/><rect class="chart__hit" x="${L + i * band}" y="${T}" width="${band}" height="${H - T - B}" data-tip="${esc(dayLabel(p.day))}: ${esc(p.label)}" tabindex="-1"/>`;
  }).join('');
  return html`<figure class="chart chart--cols" data-chart=${id}>
    <svg viewBox=${`0 0 ${W} ${H}`} role="img" aria-label=${title} preserveAspectRatio="none" class="chart__svg">
      ${[0, 0.5, 1].map((f) => raw(`<line class="chart__grid" x1="${L}" x2="${W - R}" y1="${y(max * f)}" y2="${y(max * f)}"/><text class="chart__tick" x="${L - 6}" y="${y(max * f) + 4}" text-anchor="end">${compact(max * f)}</text>`))}
      ${pts.map((p, i) => (i % 5 === 0 || i === pts.length - 1 ? raw(`<text class="chart__tick" x="${L + i * band + band / 2}" y="${H - 5}" text-anchor="middle">${dayLabel(p.day)}</text>`) : ''))}
      ${raw(bars)}
    </svg>
    <div class="chart__tip" hidden></div>
  </figure>`;
}

/** bare orizontale sortate, cu valoarea la capăt */
export function hbars(items, { fmt = (v) => v } = {}) {
  const max = Math.max(...items.map((i) => i.value), 1);
  return html`<ul class="hbars">${items.map((it) => html`<li>
    <span class="hbars__label">${it.label}</span>
    <span class="hbars__track"><span class="hbars__bar" style=${`--w:${Math.max(1.5, (it.value / max) * 100).toFixed(1)}%`}></span></span>
    <span class="hbars__value">${fmt(it.value)}</span>
  </li>`)}</ul>`;
}

export function statTile({ label, value, delta, deltaLabel, upIsGood = true, spark }) {
  const dir = delta === null || delta === undefined ? null : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat';
  const good = dir === 'flat' ? null : (dir === 'up') === upIsGood;
  let sparkSvg = '';
  if (spark?.length) {
    const max = Math.max(...spark, 1);
    const pts = spark.map((v, i) => `${(i * 100) / (spark.length - 1)},${(28 - (v / max) * 26).toFixed(1)}`).join(' ');
    sparkSvg = raw(`<svg class="tile__spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true"><polyline points="${pts}"/></svg>`);
  }
  return html`<div class="stat">
    <p class="stat__label">${label}</p>
    <p class="stat__value">${value}</p>
    ${dir ? html`<p class=${`stat__delta ${good === null ? '' : good ? 'is-good' : 'is-bad'}`}>${dir === 'up' ? '↑' : dir === 'down' ? '↓' : '→'} ${Math.abs(delta).toLocaleString('ro-RO')}% <span>${deltaLabel}</span></p>` : html`<p class="stat__delta"><span>${deltaLabel || ''}</span></p>`}
    ${sparkSvg}
  </div>`;
}
