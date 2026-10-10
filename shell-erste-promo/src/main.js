// Edit decision list, transitions and finishing for the 15" spot.
// Cuts sit on the beat grid of the score (126 BPM, drop at 11.0 s).
const cv = document.getElementById('c');
const ctx = cv.getContext('2d');
const small = mkCanvas(W / 4, H / 4), sctx = small.getContext('2d');
const layer = mkCanvas(W, H), lctx = layer.getContext('2d');
const squeeze = mkCanvas(W, H), qctx = squeeze.getContext('2d');
const grain = [];

const SHOTS = [
  { id: 'A_lake', f0: 0, f1: 39, kb: [1.0, 1.06, 0, -14], sun: [975, 420, 1.0] },
  { id: 'B_suv', f0: 39, f1: 86, kb: [1.03, 1.09, -16, 10], sun: [1862, 356, 0.85] },
  { id: 'C_station', f0: 86, f1: 110, kb: [1, 1, 0, 0], sun: [214, 868, 0.75] },
  { id: 'D_station', f0: 110, f1: 134, kb: [1, 1, 0, 0], sun: [226, 972, 0.7] },
  { id: 'E_nozzle', f0: 134, f1: 181, kb: [1.0, 1.05, 10, 0] },
  { id: 'F_bg', f0: 181, f1: 228, kb: [1.1, 1.14, 0, 0] },
  { id: 'G_door', f0: 228, f1: 264, kb: [1.03, 1.08, 0, -8] },
  { id: 'H_drive', f0: 264, f1: 275, kb: [1.06, 1.16, -40, 0], sun: [1862, 356, 1.0] },
  { id: 'P_bg', f0: 275, f1: 375, kb: [1, 1, 0, 0] },
];
const shotOf = f => SHOTS.find(s => f >= s.f0 && f < s.f1) || SHOTS[SHOTS.length - 1];

const cache = new Map();
function plate(id, i) {
  const key = `${id}/${i}`;
  if (!cache.has(key)) {
    cache.set(key, new Promise((res, rej) => {
      const im = new Image();
      im.onload = () => res(im); im.onerror = rej;
      im.src = `../build/plates/${id}/${String(i).padStart(4, '0')}.jpg`;
    }));
    if (cache.size > 24) cache.delete(cache.keys().next().value);
  }
  return cache.get(key);
}

function makeGrain() {
  for (let k = 0; k < 6; k++) {
    const g = mkCanvas(512, 512), c = g.getContext('2d');
    const img = c.createImageData(512, 512);
    const R = rng(100 + k);
    for (let i = 0; i < img.data.length; i += 4) {
      const v = (R() + R() + R()) / 3 * 255;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = v; img.data[i + 3] = 255;
    }
    c.putImageData(img, 0, 0);
    grain.push(g);
  }
}

// Directional motion blur (squeeze along the axis, stretch back, average a few offsets)
function smear(dst, src, horiz, shift, len) {
  const k = Math.max(1, len / 10);
  const sw = horiz ? Math.max(8, Math.round(W / k)) : W, sh = horiz ? H : Math.max(8, Math.round(H / k));
  qctx.setTransform(1, 0, 0, 1, 0, 0); qctx.clearRect(0, 0, W, H);
  qctx.imageSmoothingQuality = 'high';
  qctx.drawImage(src, 0, 0, sw, sh);
  dst.save(); dst.imageSmoothingQuality = 'high';
  const taps = 8;
  for (let i = 0; i < taps; i++) {
    const f = i / (taps - 1) - 0.5;
    dst.globalAlpha = 1 / (i + 1);
    if (horiz) dst.drawImage(squeeze, 0, 0, sw, sh, shift + f * len * 0.5 - 40, 0, W + 80, H);
    else dst.drawImage(squeeze, 0, 0, sw, sh, 0, shift + f * len * 0.5 - 40, W, H + 80);
  }
  dst.restore();
}

function drawPlate(c, img, s, u) {
  const [z0, z1, dx, dy] = s.kb;
  const e = E.ioQ(u);
  const z = lerp(z0, z1, e);
  c.drawImage(img, W / 2 - W * z / 2 + dx * (e - 0.5) * 2, H / 2 - H * z / 2 + dy * (e - 0.5) * 2, W * z, H * z);
  return { z, ox: dx * (e - 0.5) * 2, oy: dy * (e - 0.5) * 2 };
}

const sunPos = (s, xf) => [W / 2 + (s.sun[0] - W / 2) * xf.z + xf.ox, H / 2 + (s.sun[1] - H / 2) * xf.z + xf.oy];

async function drawShot(c, f, t) {
  const s = shotOf(f);
  const i = f - s.f0, n = s.f1 - s.f0;
  const u = n > 1 ? i / (n - 1) : 0;
  const img = await plate(s.id, Math.min(i, n - 1));
  c.save();
  if (s.id === 'P_bg') { PACK.draw(c, img, t); c.restore(); return s; }
  // rack-focus out of the nozzle shot into the graphic
  if (s.id === 'E_nozzle') c.filter = `blur(${E.inQ(inv(6.95, 7.233, t)) * 26}px)`;
  const xf = drawPlate(c, img, s, u);
  c.filter = 'none';
  if (s.sun) {
    let k = s.sun[2];
    if (s.id === 'H_drive') k *= lerp(1, 4.5, E.inQ(u));
    const [sx, sy] = sunPos(s, xf);
    sunFlare(c, sx, sy, k, t, s.id === 'H_drive' || s.id === 'B_suv' ? 0.35 : 1);
  }
  if (s.id === 'B_suv') {
    // the sun catches the roof line and slides along the car
    const g = inv(1.75, 2.75, t);
    if (g > 0 && g < 1) {
      const gx = lerp(905, 1070, E.ioQ(g)), gy = lerp(262, 268, g);
      starburst(c, W / 2 + (gx - W / 2) * xf.z + xf.ox, H / 2 + (gy - H / 2) * xf.z + xf.oy, 52, Math.sin(Math.PI * g) * 0.9, '#FFF2D0', 6, 0.2);
    }
  }
  if (s.id === 'F_bg') {
    c.fillStyle = 'rgba(6,8,14,0.45)'; c.fillRect(0, 0, W, H);
    drawPumpGraphic(c, t);
  }
  if (s.id === 'G_door') {
    // warm late-sun wash from the left to bind the daylight plate to the golden look
    c.save(); c.globalCompositeOperation = 'soft-light';
    const g = c.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, 'rgba(255,150,60,0.75)'); g.addColorStop(1, 'rgba(60,40,90,0.4)');
    c.fillStyle = g; c.fillRect(0, 0, W, H); c.restore();
    c.save(); c.globalCompositeOperation = 'screen';
    glowCircle(c, -80, 120, 900, '#ff9a48', 0.45); c.restore();
  }
  c.restore();
  return s;
}

window.renderFrame = async function (t) {
  const f = Math.round(t * FPS);
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over'; ctx.filter = 'none';
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);

  // whip pans: B→C at 3.448 s (horizontal), D→E at 5.340 s (vertical)
  const whips = [{ at: 86, horiz: true }, { at: 134, horiz: false }];
  const wp = whips.find(w => Math.abs(f - w.at + 0.5) <= 3.5);
  lctx.setTransform(1, 0, 0, 1, 0, 0); lctx.clearRect(0, 0, W, H);
  lctx.globalAlpha = 1; lctx.globalCompositeOperation = 'source-over'; lctx.filter = 'none';
  const s = await drawShot(lctx, f, t);
  if (wp) {
    const p = (f - wp.at + 0.5) / 3.5;           // -1..1 across the cut
    const mag = 1 - Math.abs(p);
    const len = E.inQ(Math.min(1, mag * 1.1)) * (wp.horiz ? 1100 : 700);
    const shift = (p < 0 ? 1 : -1) * mag * mag * 240;
    smear(ctx, layer, wp.horiz, shift, len);
  } else {
    ctx.drawImage(layer, 0, 0);
  }

  const pack = s.id === 'P_bg';
  // supers over picture
  if (t < 3.4) superLine1(ctx, t);
  if (t >= 3.4 && t < 5.4) superErste(ctx, t);
  if (s.id === 'E_nozzle') superOffer(ctx, t);

  if (!pack) {
    // bloom
    sctx.setTransform(1, 0, 0, 1, 0, 0); sctx.clearRect(0, 0, small.width, small.height);
    sctx.filter = 'brightness(0.62) contrast(4) blur(8px)';
    sctx.drawImage(cv, 0, 0, small.width, small.height);
    sctx.filter = 'none';
    ctx.save(); ctx.globalCompositeOperation = 'screen'; ctx.globalAlpha = 0.42;
    ctx.drawImage(small, 0, 0, W, H); ctx.restore();
    const vg = ctx.createRadialGradient(W / 2, H / 2, H * 0.4, W / 2, H / 2, H * 1.05);
    vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,0.42)');
    ctx.fillStyle = vg; ctx.fillRect(0, 0, W, H);
  }

  // light leaks / flashes that carry the cuts
  const env = (c, w) => Math.max(0, 1 - Math.abs(t - c) / w);
  lightLeak(ctx, E.ioQ(env(1.555, 0.22)), 'warm', 1);
  lightLeak(ctx, E.ioQ(env(4.400, 0.16)) * 0.8, 'warm', -1);
  lightLeak(ctx, E.ioQ(env(9.125, 0.18)) * 0.9, 'warm', 1);
  // H → packshot: the sun swallows the frame
  const wo = t < 11.006 ? E.inQ(inv(10.70, 11.006, t)) : 1 - E.outQ(inv(11.006, 11.36, t));
  if (wo > 0) { ctx.fillStyle = `rgba(255,246,232,${wo})`; ctx.fillRect(0, 0, W, H); }
  // the door shuts: one-frame jolt
  if (f === 262) ctx.drawImage(cv, 0, -7);

  // letterbox on the live-action part
  if (!pack) {
    const lb = 58;
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, lb); ctx.fillRect(0, H - lb, W, lb);
  }

  // film grain
  const g = grain[f % grain.length];
  ctx.save();
  ctx.globalCompositeOperation = 'overlay';
  ctx.globalAlpha = pack ? 0.045 : 0.085;
  const ox = (f * 137) % 512, oy = (f * 251) % 512;
  for (let y = -oy; y < H; y += 512) for (let x = -ox; x < W; x += 512) ctx.drawImage(g, x, y);
  ctx.restore();
};

(async () => {
  const fams = ['500 40px Montserrat', '600 40px Montserrat', '700 40px Montserrat', '800 40px Montserrat', '900 40px Montserrat'];
  await Promise.all(fams.map(f => document.fonts.load(f, 'ÁÉÍÓÖŐÚÜŰáéíóöőúüű25–✓')));
  await document.fonts.ready;
  makeGrain();
  PACK.init();
  window.READY = true;
})();
