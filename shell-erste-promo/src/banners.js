// Facebook feed banners for the Shell × Erste promo.
//   A — "Aranyóra" (golden hour): the Shell station at sunset over a deep Erste-navy offer panel.
//   B — "0 Ft": scroll-stopping Shell yellow, a pump display reading 25,00 L / 0 Ft and the Erste card.
// Formats: 4:5 (1080×1350, best for feed) and 1:1 (1080×1080).

const TXT = {
  kicker: 'ERSTE BANK-ÜGYFELEKNEK',
  l1: '25 LITER',
  l2: 'ÜZEMANYAG',
  l3: 'INGYEN!',
  sub: 'Erste Bank-ügyfeleknek.',
  date: '2026. október 12–20.',
  legal1: 'Az akció 2026. október 12-től 20-ig minden Erste Bank-ügyfél számára',
  legal2: 'érvényes a Shell-töltőállomásokon. A kép illusztráció.',
};
const NAVY = '#061538', NAVY2 = '#0B2466';

// Largest font size (≤ max) at which `text` fits into `maxW`.
function fit(ctx, text, weight, maxW, max, track = 0) {
  let s = max;
  for (; s > 10; s -= 1) {
    ctx.font = `${weight} ${s}px Montserrat`;
    if (trackedWidth(ctx, text, track) <= maxW) break;
  }
  return s;
}

function coverImage(ctx, img, x, y, w, h, focusX = 0.5, focusY = 0.5, zoom = 1) {
  const ir = img.width / img.height, r = w / h;
  let sw, sh;
  if (ir > r) { sh = img.height / zoom; sw = sh * r; } else { sw = img.width / zoom; sh = sw / r; }
  const sx = clamp((img.width - sw) * focusX, 0, img.width - sw), sy = clamp((img.height - sh) * focusY, 0, img.height - sh);
  ctx.drawImage(img, sx, sy, sw, sh, x, y, w, h);
}

function datePill(ctx, x, y, size, bg, fg, align = 'left') {
  ctx.save();
  ctx.font = `800 ${size}px Montserrat`;
  const lw = trackedWidth(ctx, TXT.date, 0.5);
  const ph = size * 1.9, pw = lw + size * 3.1;
  const px = align === 'center' ? x - pw / 2 : x;
  ctx.shadowColor = 'rgba(0,0,0,0.25)'; ctx.shadowBlur = size * 0.6; ctx.shadowOffsetY = size * 0.15;
  rrect(ctx, px, y, pw, ph, ph / 2); ctx.fillStyle = bg; ctx.fill();
  ctx.shadowColor = 'transparent';
  // calendar glyph
  const gx = px + size * 0.75, gy = y + ph * 0.24, gs = size;
  ctx.strokeStyle = fg; ctx.lineWidth = size * 0.1; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  rrect(ctx, gx, gy + gs * 0.12, gs, gs * 0.9, gs * 0.16); ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(gx, gy + gs * 0.42); ctx.lineTo(gx + gs, gy + gs * 0.42);
  ctx.moveTo(gx + gs * 0.27, gy - gs * 0.02); ctx.lineTo(gx + gs * 0.27, gy + gs * 0.22);
  ctx.moveTo(gx + gs * 0.73, gy - gs * 0.02); ctx.lineTo(gx + gs * 0.73, gy + gs * 0.22);
  ctx.stroke();
  ctx.fillStyle = fg;
  ctx.textBaseline = 'middle';
  fillTracked(ctx, TXT.date, px + size * 2.35, y + ph / 2 + size * 0.04, 0.5);
  ctx.restore();
  return { w: pw, h: ph };
}

function logoRow(ctx, x, y, h, shellCol, ersteCol, divCol) {
  const sw = drawShellLockup(ctx, x, y, h, shellCol);
  ctx.fillStyle = divCol; ctx.fillRect(x + sw + h * 0.45, y + h * 0.06, Math.max(2, h * 0.025), h * 0.88);
  drawErsteLogo(ctx, x + sw + h * 0.95, y + h * 0.78, h * 0.5, ersteCol);
}

function legal(ctx, x, y, size, col, maxW) {
  ctx.save();
  ctx.fillStyle = col;
  ctx.font = `500 ${size}px Montserrat`;
  const s1 = fit(ctx, TXT.legal1, 500, maxW, size);
  const s2 = fit(ctx, TXT.legal2, 500, maxW, size);
  const s = Math.min(s1, s2);
  ctx.font = `500 ${s}px Montserrat`;
  ctx.fillText(TXT.legal1, x, y);
  ctx.fillText(TXT.legal2, x, y + s * 1.35);
  ctx.restore();
}

function card(ctx, cx, cy, w, rot) {
  ctx.save();
  ctx.translate(cx, cy); ctx.rotate(rot);
  drawErsteCard(ctx, w, 0, 0.62);
  ctx.restore();
}

// ---------------------------------------------------------------- Banner A
function bannerA(ctx, Wd, Hd, photo) {
  const sq = Hd === Wd;
  const photoH = sq ? 640 : 770;
  // photo band: sun (left) → totem → canopy pecten (right)
  coverImage(ctx, photo, 0, 0, Wd, photoH, sq ? 0.28 : 0.11, 0.0, 1.0);
  // grade: lift the warmth a touch, keep the sky clean for the logos
  ctx.save(); ctx.globalCompositeOperation = 'soft-light';
  const wg = ctx.createLinearGradient(0, 0, Wd, photoH);
  wg.addColorStop(0, 'rgba(255,170,90,0.35)'); wg.addColorStop(1, 'rgba(40,60,140,0.25)');
  ctx.fillStyle = wg; ctx.fillRect(0, 0, Wd, photoH); ctx.restore();
  const top = ctx.createLinearGradient(0, 0, 0, 240);
  top.addColorStop(0, 'rgba(6,21,56,0.62)'); top.addColorStop(1, 'rgba(6,21,56,0)');
  ctx.fillStyle = top; ctx.fillRect(0, 0, Wd, 240);
  // fade into the navy offer panel
  const fadeTop = photoH - 230;
  const fg = ctx.createLinearGradient(0, fadeTop, 0, photoH);
  fg.addColorStop(0, 'rgba(6,21,56,0)'); fg.addColorStop(0.65, 'rgba(6,21,56,0.86)'); fg.addColorStop(1, NAVY);
  ctx.fillStyle = fg; ctx.fillRect(0, fadeTop, Wd, photoH - fadeTop + 1);
  const pg = ctx.createLinearGradient(0, photoH, 0, Hd);
  pg.addColorStop(0, NAVY); pg.addColorStop(1, '#040E2A');
  ctx.fillStyle = pg; ctx.fillRect(0, photoH, Wd, Hd - photoH);
  // warm sun bloom bleeding from the photo into the panel
  ctx.save(); ctx.globalCompositeOperation = 'screen';
  glowCircle(ctx, Wd * 0.12, photoH - 110, 520, '#ff9a4a', 0.32);
  ctx.restore();

  // yellow swoosh with the red pin-line (canopy fascia echo)
  ctx.save();
  const sw = (off) => { ctx.beginPath(); ctx.moveTo(Wd * 0.52, Hd + 4); ctx.bezierCurveTo(Wd * 0.72, Hd - 36 - off, Wd * 0.88, Hd - 70 - off, Wd + 4, Hd - (sq ? 150 : 175) - off); ctx.lineTo(Wd + 4, Hd + 4); ctx.closePath(); };
  sw(14); ctx.fillStyle = C.shellR; ctx.fill();
  sw(0); const yg = ctx.createLinearGradient(Wd * 0.5, 0, Wd, 0); yg.addColorStop(0, '#FBCE07'); yg.addColorStop(1, '#FFD62E'); ctx.fillStyle = yg; ctx.fill();
  ctx.restore();

  const X = 72;
  logoRow(ctx, X, 54, sq ? 66 : 74, '#fff', '#fff', 'rgba(255,255,255,0.6)');

  // kicker chip
  ctx.save();
  const ky = photoH - (sq ? 96 : 112);
  ctx.font = `800 ${sq ? 28 : 31}px Montserrat`;
  const kw = trackedWidth(ctx, TXT.kicker, 1.8);
  const kh = sq ? 56 : 62;
  ctx.shadowColor = 'rgba(0,0,0,0.3)'; ctx.shadowBlur = 18;
  rrect(ctx, X, ky, kh + kw + 46, kh, kh / 2); ctx.fillStyle = C.ersteB; ctx.fill();
  ctx.shadowColor = 'transparent';
  drawErsteS(ctx, X + kh * 0.45, ky + kh * 0.17, kh * 0.42, '#fff');
  ctx.fillStyle = '#fff'; ctx.textBaseline = 'middle';
  fillTracked(ctx, TXT.kicker, X + kh + 18, ky + kh / 2 + 1, 1.8);
  ctx.restore();

  // headline
  ctx.save();
  ctx.textBaseline = 'alphabetic';
  ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = 24;
  const maxW = Wd - 2 * X;
  let y;
  if (!sq) {
    const s1 = fit(ctx, TXT.l2, 900, maxW, 138, -2);       // ÜZEMANYAG sets the measure
    ctx.font = `900 ${s1}px Montserrat`; ctx.fillStyle = '#fff';
    y = photoH + s1 * 0.62;
    fillTracked(ctx, TXT.l1, X - 4, y, -2);
    y += s1 * 0.98;
    fillTracked(ctx, TXT.l2, X - 4, y, -2);
    const s3 = fit(ctx, TXT.l3, 900, maxW * 0.82, 178, -2);
    ctx.font = `900 ${s3}px Montserrat`; ctx.fillStyle = C.shellY;
    y += s3 * 0.95;
    fillTracked(ctx, TXT.l3, X - 6, y, -2);
  } else {
    const s1 = fit(ctx, TXT.l1 + ' ' + TXT.l2, 900, maxW, 90, -1);
    ctx.font = `900 ${s1}px Montserrat`; ctx.fillStyle = '#fff';
    y = photoH + s1 * 0.78;
    fillTracked(ctx, TXT.l1 + ' ' + TXT.l2, X - 3, y, -1);
    var line1Base = y;
    const s3 = fit(ctx, TXT.l3, 900, maxW * 0.66, 176, -2);
    ctx.font = `900 ${s3}px Montserrat`; ctx.fillStyle = C.shellY;
    y += s3 * 0.98;
    fillTracked(ctx, TXT.l3, X - 5, y, -2);
  }
  ctx.restore();

  // the card sits on the seam between photo and panel
  if (!sq) card(ctx, Wd - 200, photoH - 36, 280, -0.14);
  else card(ctx, Wd - 190, line1Base + 26 + 112, 280, -0.12);

  // date + legal
  const dy = y + (sq ? 30 : 34);
  datePill(ctx, X, dy, sq ? 28 : 32, C.shellY, NAVY);
  legal(ctx, X, Hd - (sq ? 58 : 60), sq ? 17 : 18, 'rgba(255,255,255,0.8)', sq ? 600 : 640);
}

// ---------------------------------------------------------------- Banner B
function pumpDisplay(ctx, x, y, w, h) {
  ctx.save();
  ctx.shadowColor = 'rgba(80,40,0,0.45)'; ctx.shadowBlur = 60; ctx.shadowOffsetY = 26;
  rrect(ctx, x, y, w, h, w * 0.045);
  const g = ctx.createLinearGradient(0, y, 0, y + h);
  g.addColorStop(0, '#1C1F27'); g.addColorStop(1, '#0B0C10');
  ctx.fillStyle = g; ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.shadowOffsetX = 0; ctx.shadowOffsetY = 0; ctx.shadowBlur = 0;
  ctx.save(); rrect(ctx, x, y, w, h, w * 0.045); ctx.clip();
  ctx.fillStyle = C.shellR; ctx.fillRect(x, y, w, h * 0.035);
  const rg = ctx.createLinearGradient(x, y, x + w * 0.7, y + h);
  rg.addColorStop(0, 'rgba(255,255,255,0.09)'); rg.addColorStop(0.5, 'rgba(255,255,255,0)');
  ctx.fillStyle = rg; ctx.fillRect(x, y, w, h);
  ctx.restore();
  ctx.strokeStyle = 'rgba(255,255,255,0.12)'; ctx.lineWidth = 2; rrect(ctx, x, y, w, h, w * 0.045); ctx.stroke();
  const u = w / 900;
  drawPecten(ctx, x + 48 * u, y + 46 * u, 56 * u);
  ctx.font = `700 ${28 * u}px Montserrat`; ctx.fillStyle = '#fff';
  ctx.fillText('Shell', x + 118 * u, y + 88 * u);
  // accepted chip
  const cw = 330 * u, ch = 50 * u;
  rrect(ctx, x + w - cw - 36 * u, y + 50 * u, cw, ch, ch / 2); ctx.fillStyle = '#1F9D55'; ctx.fill();
  ctx.font = `800 ${21 * u}px Montserrat`; ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText('ERSTE ✓ ELFOGADVA', x + w - cw / 2 - 36 * u, y + 50 * u + ch / 2 + 1);
  ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
  ctx.font = `700 ${30 * u}px Montserrat`; ctx.fillStyle = 'rgba(255,255,255,0.75)';
  fillTracked(ctx, 'LITER', x + 48 * u, y + 228 * u, 4 * u);
  fillTracked(ctx, 'FIZETENDŐ', x + 48 * u, y + 404 * u, 4 * u);
  ctx.save();
  ctx.shadowOffsetX = 0; ctx.shadowOffsetY = 0;
  ctx.shadowColor = rgba(C.shellY, 0.75); ctx.shadowBlur = 30 * u;
  seg7(ctx, '25,00', x + 300 * u, y + 166 * u, 118 * u, C.shellY, 'rgba(251,206,7,0.07)');
  ctx.shadowColor = 'rgba(255,255,255,0.55)'; ctx.shadowBlur = 16 * u;
  // 4 cells starting one comma-advance later, so both readouts end on the same edge
  seg7(ctx, '   0', x + 323.6 * u, y + 342 * u, 118 * u, '#fff', 'rgba(255,255,255,0.06)');
  ctx.restore();
  ctx.font = `700 ${44 * u}px Montserrat`; ctx.fillStyle = 'rgba(255,255,255,0.88)';
  ctx.fillText('L', x + w - 92 * u, y + 282 * u);
  ctx.fillText('Ft', x + w - 102 * u, y + 458 * u);
  ctx.restore();
}

function bannerB(ctx, Wd, Hd) {
  const sq = Hd === Wd;
  // Shell-yellow field with a soft light core and fine rays
  const bg = ctx.createRadialGradient(Wd * 0.5, Hd * 0.48, 40, Wd * 0.5, Hd * 0.5, Hd * 0.85);
  bg.addColorStop(0, '#FFE45A'); bg.addColorStop(0.55, '#FDD10F'); bg.addColorStop(1, '#F2B600');
  ctx.fillStyle = bg; ctx.fillRect(0, 0, Wd, Hd);
  ctx.save();
  ctx.translate(Wd * 0.5, Hd * (sq ? 0.62 : 0.58));
  for (let i = 0; i < 28; i++) {
    ctx.rotate(Math.PI * 2 / 28);
    ctx.fillStyle = i % 2 ? 'rgba(255,255,255,0.10)' : 'rgba(255,255,255,0.0)';
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(1400, -110); ctx.lineTo(1400, 110); ctx.closePath(); ctx.fill();
  }
  ctx.restore();
  // red band at the foot (brand anchor) with a navy legal strip
  const footH = sq ? 92 : 104;
  ctx.fillStyle = C.shellR; ctx.fillRect(0, Hd - footH - 10, Wd, 10);
  ctx.fillStyle = NAVY; ctx.fillRect(0, Hd - footH, Wd, footH);

  const X = 72;
  logoRow(ctx, X, 50, sq ? 64 : 72, C.shellR, C.ersteB, 'rgba(6,21,56,0.35)');

  // headline
  ctx.save();
  ctx.textBaseline = 'alphabetic';
  const maxW = Wd - 2 * X;
  const s1 = fit(ctx, TXT.l1 + ' ' + TXT.l2, 900, maxW, sq ? 76 : 86, -1);
  ctx.font = `900 ${s1}px Montserrat`; ctx.fillStyle = NAVY;
  let y = (sq ? 176 : 200) + s1 * 0.75;
  fillTracked(ctx, TXT.l1 + ' ' + TXT.l2, X - 2, y, -1);
  const s3 = fit(ctx, TXT.l3, 900, maxW, sq ? 196 : 240, -3);
  ctx.font = `900 ${s3}px Montserrat`;
  y += s3 * 0.86;
  ctx.shadowColor = 'rgba(130,40,0,0.25)'; ctx.shadowBlur = 0; ctx.shadowOffsetY = 8;
  ctx.fillStyle = C.shellR;
  fillTracked(ctx, TXT.l3, X - 8, y, -3);
  ctx.restore();

  // hero: pump display + Erste card
  const dw = sq ? 570 : 800, dh = dw * 0.63;
  const dx = sq ? X : X + 2, dyy = y + (sq ? 38 : 56);
  ctx.save();
  ctx.translate(dx + dw / 2, dyy + dh / 2); ctx.rotate(-0.035); ctx.translate(-(dx + dw / 2), -(dyy + dh / 2));
  pumpDisplay(ctx, dx, dyy, dw, dh);
  ctx.restore();
  const cw = sq ? 305 : 345;
  const cx = sq ? Wd - 215 : Wd - 235, cy = sq ? dyy + dh * 0.98 : dyy + dh + 26;
  card(ctx, cx, cy, cw, 0.12);

  // sub + date
  let by = dyy + dh + (sq ? 34 : 48);
  ctx.save();
  ctx.font = `700 ${sq ? 36 : 44}px Montserrat`; ctx.fillStyle = NAVY;
  fillTracked(ctx, TXT.sub, X, by + (sq ? 30 : 36), 0);
  ctx.restore();
  datePill(ctx, X, by + (sq ? 60 : 74), sq ? 27 : 32, NAVY, C.shellY);
  legal(ctx, X, Hd - footH + (sq ? 38 : 44), sq ? 18 : 20, 'rgba(255,255,255,0.78)', Wd - 2 * X);
}

window.drawBanner = async function (id, Wd, Hd) {
  const cv = document.getElementById('c');
  cv.width = Wd; cv.height = Hd;
  cv.style.width = Wd + 'px'; cv.style.height = Hd + 'px';
  const ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, Wd, Hd);
  if (id === 'A') {
    const img = await new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = rej; im.src = '../assets/kv/station_a_x4.jpg'; });
    bannerA(ctx, Wd, Hd, img);
  } else {
    bannerB(ctx, Wd, Hd);
  }
};

(async () => {
  const fams = ['500 40px Montserrat', '600 40px Montserrat', '700 40px Montserrat', '800 40px Montserrat', '900 40px Montserrat'];
  await Promise.all(fams.map(f => document.fonts.load(f, 'ÁÉÍÓÖŐÚÜŰáéíóöőúüű25–✓')));
  await document.fonts.ready;
  window.READY = true;
})();
