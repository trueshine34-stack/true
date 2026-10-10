// Shared helpers: timing, easing, colour, seeded randomness, offscreen canvases.
const W = 1920, H = 1080, FPS = 25, DURATION = 15;

const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const lerp = (a, b, t) => a + (b - a) * t;
const inv = (a, b, v) => clamp((v - a) / (b - a));
const smooth = t => t * t * (3 - 2 * t);
const E = {
  inQ: t => t * t,
  outQ: t => 1 - (1 - t) * (1 - t),
  ioQ: t => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2),
  inC: t => t * t * t,
  outC: t => 1 - Math.pow(1 - t, 3),
  ioC: t => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  outQuint: t => 1 - Math.pow(1 - t, 5),
  ioQuint: t => (t < 0.5 ? 16 * t ** 5 : 1 - Math.pow(-2 * t + 2, 5) / 2),
  outExpo: t => (t >= 1 ? 1 : 1 - Math.pow(2, -10 * t)),
  inExpo: t => (t <= 0 ? 0 : Math.pow(2, 10 * t - 10)),
  outBack: (t, s = 1.70158) => 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2),
  outElastic: t => (t <= 0 ? 0 : t >= 1 ? 1 : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1),
};

function rng(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function mkCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w)); c.height = Math.max(1, Math.round(h));
  return c;
}

function hex2rgb(h) {
  h = h.replace('#', '');
  if (h.length === 3) h = h.split('').map(c => c + c).join('');
  const n = parseInt(h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function rgba(h, a = 1) { const [r, g, b] = hex2rgb(h); return `rgba(${r},${g},${b},${a})`; }
function mix(h1, h2, t) {
  const a = hex2rgb(h1), b = hex2rgb(h2);
  return `rgb(${Math.round(lerp(a[0], b[0], t))},${Math.round(lerp(a[1], b[1], t))},${Math.round(lerp(a[2], b[2], t))})`;
}
function mixA(h1, h2, t, a) {
  const x = hex2rgb(h1), y = hex2rgb(h2);
  return `rgba(${Math.round(lerp(x[0], y[0], t))},${Math.round(lerp(x[1], y[1], t))},${Math.round(lerp(x[2], y[2], t))},${a})`;
}

const C = {
  shellY: '#FBCE07', shellR: '#DD1D21', shellRdark: '#A8131A',
  ersteB: '#2870ED', ersteNavy: '#0A1E52', ersteDeep: '#061338',
  white: '#FFFFFF', ink: '#0B0F17',
};

function rrect(ctx, x, y, w, h, r) {
  r = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// Path from a list of points; segments that are 4-element arrays are quadratic curves [cx,cy,x,y],
// 6-element arrays are cubic curves.
function pathPts(ctx, pts, close = true) {
  ctx.beginPath();
  ctx.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) {
    const p = pts[i];
    if (p.length === 2) ctx.lineTo(p[0], p[1]);
    else if (p.length === 4) ctx.quadraticCurveTo(p[0], p[1], p[2], p[3]);
    else ctx.bezierCurveTo(p[0], p[1], p[2], p[3], p[4], p[5]);
  }
  if (close) ctx.closePath();
}

function glowCircle(ctx, x, y, r, color, a = 1) {
  const g = ctx.createRadialGradient(x, y, 0, x, y, r);
  g.addColorStop(0, rgba(color, a));
  g.addColorStop(0.25, rgba(color, a * 0.45));
  g.addColorStop(1, rgba(color, 0));
  ctx.fillStyle = g;
  ctx.fillRect(x - r, y - r, r * 2, r * 2);
}

// Text with manual tracking (letter-spacing) — returns total width.
function trackedWidth(ctx, text, tracking) {
  let w = 0;
  for (const ch of text) w += ctx.measureText(ch).width + tracking;
  return w - tracking;
}
function fillTracked(ctx, text, x, y, tracking, align = 'left') {
  const total = trackedWidth(ctx, text, tracking);
  let cx = align === 'center' ? x - total / 2 : align === 'right' ? x - total : x;
  const saved = ctx.textAlign; ctx.textAlign = 'left';
  for (const ch of text) { ctx.fillText(ch, cx, y); cx += ctx.measureText(ch).width + tracking; }
  ctx.textAlign = saved;
  return total;
}

// Starburst sparkle used for sun glints on paint and chrome.
function starburst(ctx, x, y, size, a, color = '#FFF6DA', rays = 4, rot = 0) {
  if (a <= 0.001) return;
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  glowCircle(ctx, x, y, size * 0.9, color, 0.55 * a);
  ctx.translate(x, y); ctx.rotate(rot);
  for (let i = 0; i < rays; i++) {
    const L = (i % 2 === 0 ? size * 2.4 : size * 1.3);
    const g = ctx.createLinearGradient(-L, 0, L, 0);
    g.addColorStop(0, rgba(color, 0)); g.addColorStop(0.5, rgba(color, a)); g.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(-L, 0); ctx.lineTo(0, -size * 0.06); ctx.lineTo(L, 0); ctx.lineTo(0, size * 0.06); ctx.closePath();
    ctx.fill();
    ctx.rotate(Math.PI / rays);
  }
  ctx.restore();
}
