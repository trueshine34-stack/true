// Photographic overlays (sun flares, light leaks), on-screen supers and the card/pump graphic.

// ---------- optics ----------
function sunFlare(ctx, x, y, k = 1, t = 0, ghostAmt = 1) {
  if (k <= 0) return;
  ctx.save();
  ctx.globalCompositeOperation = 'screen';
  const flick = 0.92 + 0.08 * Math.sin(t * 13.0) * Math.sin(t * 7.3);
  glowCircle(ctx, x, y, 520 * k, '#ff9d4d', 0.32 * k * flick);
  glowCircle(ctx, x, y, 170 * k, '#ffe2a6', 0.55 * k * flick);
  glowCircle(ctx, x, y, 44 * k, '#fffaf0', 0.9 * k);
  // anamorphic streak
  const L = 1100 * k;
  const g = ctx.createLinearGradient(x - L, 0, x + L, 0);
  g.addColorStop(0, 'rgba(120,170,255,0)'); g.addColorStop(0.42, `rgba(170,200,255,${0.22 * k})`);
  g.addColorStop(0.5, `rgba(255,245,230,${0.75 * k})`); g.addColorStop(0.58, `rgba(170,200,255,${0.22 * k})`);
  g.addColorStop(1, 'rgba(120,170,255,0)');
  ctx.fillStyle = g;
  ctx.fillRect(x - L, y - 2.2 * k, 2 * L, 4.4 * k);
  ctx.globalAlpha = 0.5;
  ctx.fillRect(x - L * 0.6, y - 9 * k, 1.2 * L, 18 * k);
  ctx.globalAlpha = 1;
  // ghosts along the optical axis
  const ax = W / 2 - x, ay = H / 2 - y;
  const ghosts = [[0.55, 26, '#ffcf8a', 0.07], [0.9, 60, '#9cc3ff', 0.04], [1.55, 120, '#ff9f7a', 0.03], [1.85, 44, '#a8ffd6', 0.035]];
  for (const [m, r, c, a] of ghosts) {
    const gx = x + ax * m * 2, gy = y + ay * m * 2;
    const gg = ctx.createRadialGradient(gx, gy, r * 0.2, gx, gy, r * k);
    gg.addColorStop(0, rgba(c, 0)); gg.addColorStop(0.75, rgba(c, a * Math.min(k, 1.2) * ghostAmt)); gg.addColorStop(1, rgba(c, 0));
    ctx.fillStyle = gg; ctx.beginPath(); ctx.arc(gx, gy, r * k, 0, Math.PI * 2); ctx.fill();
  }
  ctx.restore();
}

function lightLeak(ctx, p, hue = 'warm', side = 1) {
  // p: 0..1..0 intensity envelope
  if (p <= 0.001) return;
  ctx.save();
  ctx.globalCompositeOperation = 'screen';
  const cols = hue === 'warm' ? ['#ff8a2a', '#ffd27a', '#ff4f6a'] : ['#7fb0ff', '#ffffff', '#ffd27a'];
  const cx = W * (0.5 + side * (0.55 - p * 0.35)), cy = H * 0.45;
  glowCircle(ctx, cx, cy, W * 0.9, cols[0], 0.75 * p);
  glowCircle(ctx, cx - side * 300, cy + 120, W * 0.5, cols[1], 0.65 * p);
  glowCircle(ctx, cx + side * 250, cy - 200, W * 0.4, cols[2], 0.35 * p);
  ctx.restore();
}

// ---------- supers ----------
function superLine1(ctx, t) {
  // "Minden út tartogat valami jót." word-synced to VO1 (starts at 0.25 s)
  const words = [['Minden', 0.33], ['út', 0.66], ['tartogat', 0.84], ['valami', 1.34], ['jót.', 1.70]];
  const out = 1 - E.inQ(inv(2.85, 3.25, t));
  if (out <= 0) return;
  ctx.save();
  ctx.font = '600 60px Montserrat';
  ctx.textBaseline = 'alphabetic';
  const sp = 18, track = 1.5;
  const ws = words.map(([w]) => trackedWidth(ctx, w, track));
  const total = ws.reduce((a, b) => a + b, 0) + sp * (words.length - 1);
  let x = W / 2 - total / 2;
  const y = H - 158;
  // soft shadow band for legibility
  const band = ctx.createLinearGradient(0, y - 120, 0, y + 70);
  band.addColorStop(0, 'rgba(0,0,0,0)'); band.addColorStop(0.6, `rgba(10,6,4,${0.32 * out})`); band.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = band; ctx.fillRect(0, y - 120, W, 190);
  words.forEach(([w, at], i) => {
    const p = E.outC(inv(at - 0.05, at + 0.35, t));
    if (p <= 0) { x += ws[i] + sp; return; }
    ctx.globalAlpha = p * out;
    ctx.shadowColor = 'rgba(20,8,0,0.6)'; ctx.shadowBlur = 22;
    ctx.fillStyle = '#FFFAF2';
    ctx.filter = p < 0.98 ? `blur(${(1 - p) * 10}px)` : 'none';
    fillTracked(ctx, w, x, y + (1 - p) * 22, track);
    x += ws[i] + sp;
  });
  ctx.filter = 'none'; ctx.shadowBlur = 0;
  const lp = E.ioC(inv(1.6, 2.3, t));
  ctx.globalAlpha = out;
  const lg = ctx.createLinearGradient(W / 2 - 150, 0, W / 2 + 150, 0);
  lg.addColorStop(0, 'rgba(251,206,7,0)'); lg.addColorStop(0.5, 'rgba(251,206,7,1)'); lg.addColorStop(1, 'rgba(251,206,7,0)');
  ctx.fillStyle = lg; ctx.fillRect(W / 2 - 150 * lp, y + 30, 300 * lp, 4);
  ctx.restore();
}

function superErste(ctx, t) {
  // lower third over the station shots, synced to "Erste Bank ügyfélként…"
  const a = E.outC(inv(3.6, 3.95, t)) * (1 - E.inQ(inv(5.05, 5.3, t)));
  if (a <= 0) return;
  ctx.save();
  const x = 112, y = H - 176;
  ctx.font = '700 52px Montserrat';
  const label = 'Erste Bank-ügyfeleknek';
  const tw = trackedWidth(ctx, label, 0.5);
  const full = 96 + 28 + tw + 44;
  ctx.globalAlpha = a;
  // frosted navy plate grows out of the chip
  const pw = lerp(96, full, E.outExpo(inv(3.62, 4.2, t)));
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = 30;
  ctx.fillStyle = 'rgba(6,19,56,0.72)';
  rrect(ctx, x, y - 70, pw, 96, 20); ctx.fill();
  ctx.restore();
  ctx.fillStyle = C.ersteB; rrect(ctx, x, y - 70, 96, 96, 20); ctx.fill();
  drawErsteS(ctx, x + 28, y - 60, 40, '#fff');
  ctx.save(); ctx.beginPath(); ctx.rect(x + 96, y - 70, pw - 96, 96); ctx.clip();
  const ty = (1 - E.outExpo(inv(3.72, 4.25, t))) * 70;
  ctx.fillStyle = '#fff';
  fillTracked(ctx, label, x + 96 + 28, y - 4 + ty, 0.5);
  ctx.restore();
  ctx.fillStyle = C.shellY; ctx.fillRect(x + 96 + 28, y + 6, Math.max(0, (tw * 0.4) * E.ioC(inv(3.95, 4.5, t))), 4);
  ctx.restore();
}

function superOffer(ctx, t) {
  // kinetic offer on the nozzle shot, synced to "huszonöt liter / üzemanyag / ingyen!"
  const out = 1 - E.inQ(inv(7.05, 7.25, t));
  if (out <= 0 || t < 5.25) return;
  ctx.save();
  // darken the right side for legibility
  const g = ctx.createLinearGradient(W * 0.42, 0, W, 0);
  g.addColorStop(0, 'rgba(8,6,10,0)'); g.addColorStop(1, `rgba(8,6,10,${0.62 * out})`);
  ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
  const xr = W - 120;
  ctx.textAlign = 'right';
  const items = [
    { txt: '25 LITER', at: 5.36, font: '900 168px Montserrat', col: C.shellY, y: 420 },
    { txt: 'ÜZEMANYAG', at: 5.64, font: '800 96px Montserrat', col: '#ffffff', y: 540 },
    { txt: 'INGYEN!', at: 6.56, font: '900 168px Montserrat', col: C.shellY, y: 720 },
  ];
  for (const it of items) {
    const p = inv(it.at - 0.04, it.at + 0.3, t);
    if (p <= 0) continue;
    const e = E.outBack(p, 1.6);
    ctx.save();
    ctx.globalAlpha = Math.min(1, p * 2.5) * out;
    ctx.font = it.font;
    ctx.translate(xr, it.y);
    const s = lerp(1.35, 1, E.outExpo(p));
    ctx.scale(s, s);
    ctx.shadowColor = 'rgba(0,0,0,0.55)'; ctx.shadowBlur = 30;
    ctx.fillStyle = it.col;
    ctx.fillText(it.txt, 0, (1 - e) * 30);
    ctx.restore();
  }
  ctx.restore();
}

// ---------- 7-segment display ----------
const SEG = { '0': 'abcdef', '1': 'bc', '2': 'abged', '3': 'abgcd', '4': 'fgbc', '5': 'afgcd', '6': 'afgedc', '7': 'abc', '8': 'abcdefg', '9': 'abcdfg', '-': 'g', ' ': '' };
function seg7(ctx, str, x, y, h, on, off) {
  const w = h * 0.52, th = h * 0.11, sk = h * 0.08;
  let cx = x;
  for (const ch of str) {
    if (ch === ',' || ch === '.') {
      const px = cx - h * 0.04, py = y + h - th * 0.3;
      ctx.fillStyle = on; ctx.beginPath(); ctx.arc(px, py, th * 0.7, 0, Math.PI * 2); ctx.fill();
      if (ch === ',') {   // decimal comma (Hungarian): dot with a short tail
        ctx.strokeStyle = on; ctx.lineWidth = th * 0.55; ctx.lineCap = 'round';
        ctx.beginPath(); ctx.moveTo(px + th * 0.35, py + th * 0.2); ctx.quadraticCurveTo(px + th * 0.2, py + th * 1.2, px - th * 0.55, py + th * 1.6); ctx.stroke();
      }
      cx += h * 0.2; continue;
    }
    const segs = SEG[ch] || '';
    const P = {
      a: [[0.1, 0], [0.9, 0]], b: [[1, 0.06], [1, 0.47]], c: [[1, 0.53], [1, 0.94]], d: [[0.1, 1], [0.9, 1]],
      e: [[0, 0.53], [0, 0.94]], f: [[0, 0.06], [0, 0.47]], g: [[0.1, 0.5], [0.9, 0.5]],
    };
    for (const k of 'abcdefg') {
      const [[x1, y1], [x2, y2]] = P[k];
      const X = v => cx + v[0] * w + (1 - v[1]) * sk, Y = v => y + v[1] * h;
      ctx.strokeStyle = segs.includes(k) ? on : off;
      ctx.lineWidth = th; ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(X([x1, y1]), Y([x1, y1])); ctx.lineTo(X([x2, y2]), Y([x2, y2])); ctx.stroke();
    }
    cx += w + h * 0.2;
  }
  return cx - x;
}

// ---------- shot F: tap the Erste card, the pump counts 25 litres for 0 Ft ----------
function drawPumpGraphic(ctx, t) {
  const lt = t - 7.233;
  ctx.save();
  // gentle push on the whole graphic
  const s = lerp(1.0, 1.035, E.ioQ(clamp(lt / 1.9)));
  ctx.translate(W / 2, H / 2); ctx.scale(s, s); ctx.translate(-W / 2, -H / 2);
  const intro = E.outExpo(inv(7.233, 7.6, t));

  // --- display panel
  const px = 250, py = 236, pw = 920, ph = 600;
  ctx.save();
  ctx.globalAlpha = intro;
  ctx.translate(0, (1 - intro) * 40);
  ctx.shadowColor = 'rgba(0,0,0,0.6)'; ctx.shadowBlur = 60; ctx.shadowOffsetY = 20;
  rrect(ctx, px, py, pw, ph, 36);
  const pg = ctx.createLinearGradient(0, py, 0, py + ph);
  pg.addColorStop(0, 'rgba(22,24,30,0.92)'); pg.addColorStop(1, 'rgba(10,11,14,0.94)');
  ctx.fillStyle = pg; ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.strokeStyle = 'rgba(255,255,255,0.14)'; ctx.lineWidth = 2; ctx.stroke();
  // brand stripe
  ctx.save(); rrect(ctx, px, py, pw, ph, 36); ctx.clip();
  ctx.fillStyle = C.shellY; ctx.fillRect(px, py, pw, 16);
  ctx.fillStyle = C.shellR; ctx.fillRect(px, py + 16, pw, 7);
  // glass reflection
  const rg = ctx.createLinearGradient(px, py, px + pw * 0.6, py + ph);
  rg.addColorStop(0, 'rgba(255,255,255,0.08)'); rg.addColorStop(0.5, 'rgba(255,255,255,0)');
  ctx.fillStyle = rg; ctx.fillRect(px, py, pw, ph);
  ctx.restore();
  drawPecten(ctx, px + 46, py + 52, 64);
  ctx.font = '700 30px Montserrat'; ctx.fillStyle = '#fff';
  ctx.fillText('Shell', px + 126, py + 96);
  // status chip
  const ok = inv(7.62, 7.72, t);
  const chipW = 340;
  rrect(ctx, px + pw - chipW - 44, py + 56, chipW, 52, 26);
  ctx.fillStyle = ok > 0 ? mix('#2a2d36', '#1f9d55', ok) : '#2a2d36'; ctx.fill();
  ctx.font = '700 22px Montserrat'; ctx.fillStyle = '#fff'; ctx.textAlign = 'center';
  ctx.fillText(ok > 0.5 ? 'ERSTE ✓ ELFOGADVA' : 'ÉRINTSE A KÁRTYÁT', px + pw - chipW / 2 - 44, py + 90);
  ctx.textAlign = 'left';
  // rows
  const run = E.outC(inv(7.75, 8.85, t));
  const litres = (25 * run).toFixed(2).replace('.', ',');
  const done = inv(8.82, 8.95, t);
  ctx.font = '700 26px Montserrat'; ctx.fillStyle = 'rgba(255,255,255,0.62)';
  fillTracked(ctx, 'LITER', px + 56, py + 200, 4);
  fillTracked(ctx, 'FIZETENDŐ', px + 56, py + 390, 4);
  const pad = s2 => s2.padStart(5, ' ');
  ctx.save();
  ctx.shadowColor = rgba(C.shellY, 0.7); ctx.shadowBlur = 24 + 30 * done;
  seg7(ctx, pad(litres), px + 300, py + 140, 120, C.shellY, 'rgba(251,206,7,0.07)');
  ctx.shadowColor = 'rgba(255,255,255,0.5)'; ctx.shadowBlur = 14;
  seg7(ctx, pad('0'), px + 300, py + 330, 120, '#ffffff', 'rgba(255,255,255,0.06)');
  ctx.restore();
  ctx.font = '700 46px Montserrat'; ctx.fillStyle = 'rgba(255,255,255,0.85)';
  ctx.fillText('L', px + pw - 110, py + 258);
  ctx.fillText('Ft', px + pw - 120, py + 448);
  // INGYEN badge stamps in when the counter lands on 25
  if (done > 0) {
    const e = E.outBack(done, 2.2);
    ctx.save();
    ctx.translate(px + pw - 220, py + ph - 74);
    ctx.rotate(-0.05);
    ctx.scale(lerp(1.6, 1, e), lerp(1.6, 1, e));
    ctx.globalAlpha = Math.min(1, done * 3);
    rrect(ctx, -150, -40, 300, 80, 40); ctx.fillStyle = C.shellY; ctx.fill();
    ctx.font = '900 44px Montserrat'; ctx.fillStyle = C.ersteDeep; ctx.textAlign = 'center';
    ctx.fillText('INGYEN!', 0, 16);
    ctx.restore();
  }
  ctx.font = '600 22px Montserrat'; ctx.fillStyle = 'rgba(255,255,255,0.5)';
  fillTracked(ctx, 'ERSTE BANK-ÜGYFELEKNEK', px + 56, py + ph - 62, 2);
  ctx.restore();

  // --- contactless reader target on the panel edge
  const rx = px + pw + 6, ry = py + 270;
  // --- the card: flies in, taps the reader, eases back
  const fly = E.outC(inv(7.25, 7.62, t));
  const back = E.ioC(inv(7.75, 8.3, t));
  const cx = lerp(W + 400, rx + 230, fly) + back * 170;
  const cy = lerp(ry - 260, ry + 10, fly) - back * 40;
  const rot = lerp(-0.6, -0.12, fly) + back * 0.04;
  const squash = 1 - 0.05 * Math.sin(Math.PI * inv(7.6, 7.72, t));
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(rot);
  ctx.scale(lerp(0.8, 1, fly) * squash, lerp(0.7, 1, fly));
  drawErsteCard(ctx, 540, t, inv(7.5, 8.2, t));
  ctx.restore();
  // ripple at the tap
  const rp = inv(7.6, 8.15, t);
  if (rp > 0 && rp < 1) {
    ctx.save();
    for (let i = 0; i < 3; i++) {
      const q = clamp(rp * 1.3 - i * 0.15);
      if (q <= 0) continue;
      ctx.strokeStyle = `rgba(255,255,255,${(1 - q) * 0.8})`;
      ctx.lineWidth = 4;
      ctx.beginPath(); ctx.arc(rx + 60, ry + 10, 40 + q * 260, 0, Math.PI * 2); ctx.stroke();
    }
    ctx.restore();
  }
  ctx.restore();
}
