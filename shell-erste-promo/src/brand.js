// Brand marks drawn as vectors. These are faithful-in-spirit stand-ins; swap in the official
// artwork from the Shell / Erste brand portals for broadcast delivery.

// Shell pecten in a unit box (width 1, height ~0.94), origin top-left.
const PECTEN = (() => {
  const hx = 0.5, hy = 0.775;      // hinge of the fan
  const a = 0.485, b = 0.64;        // fan radii (x / y)
  const ribs = 8;
  const phiS = Math.PI * (1 + 0.075), phiE = Math.PI * (2 - 0.075);
  const d = (phiE - phiS) / ribs;
  const outline = [];
  // left lower flank up to the first rib
  outline.push([0.245, 0.915], [0.235, 0.80]);
  outline.push([hx + a * Math.cos(phiS) * 0.96, hy + b * Math.sin(phiS) * 0.96 + 0.02]);
  for (let i = 0; i <= 240; i++) {
    const phi = phiS + (phiE - phiS) * i / 240;
    const k = ((phi - phiS) / d) % 1;
    const bump = 1 + 0.045 * Math.sqrt(Math.abs(Math.sin(Math.PI * k)));
    outline.push([hx + a * Math.cos(phi) * bump, hy + b * Math.sin(phi) * bump]);
  }
  outline.push([hx + a * Math.cos(phiE) * 0.96, hy + b * Math.sin(phiE) * 0.96 + 0.02]);
  outline.push([0.765, 0.80], [0.755, 0.915]);
  const grooves = [];
  for (let i = 1; i < ribs; i++) grooves.push(phiS + d * i);
  return { outline, grooves, hx, hy, a, b };
})();

function drawPecten(ctx, x, y, size, opts = {}) {
  const { alpha = 1, flat = false } = opts;
  ctx.save();
  ctx.globalAlpha *= alpha;
  ctx.translate(x, y);
  ctx.scale(size, size);
  const P = PECTEN;
  const shape = () => {
    ctx.beginPath();
    P.outline.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
    ctx.closePath();
  };
  // red outline
  shape();
  ctx.lineJoin = 'round';
  ctx.lineWidth = 0.075;
  ctx.strokeStyle = C.shellR;
  ctx.stroke();
  ctx.fillStyle = C.shellR; ctx.fill();
  // yellow body (inset)
  ctx.save();
  shape(); ctx.clip();
  ctx.beginPath();
  P.outline.forEach((p, i) => {
    const q = [P.hx + (p[0] - P.hx) * 0.93, P.hy + (p[1] - P.hy) * 0.93 + 0.004];
    i ? ctx.lineTo(q[0], q[1]) : ctx.moveTo(q[0], q[1]);
  });
  ctx.closePath();
  if (flat) ctx.fillStyle = C.shellY;
  else {
    const g = ctx.createRadialGradient(0.5, 0.35, 0.02, 0.5, 0.5, 0.65);
    g.addColorStop(0, '#FFE45C'); g.addColorStop(0.6, C.shellY); g.addColorStop(1, '#F2B800');
    ctx.fillStyle = g;
  }
  ctx.fill();
  // grooves: tapered red wedges radiating from the hinge
  ctx.fillStyle = C.shellR;
  for (const phi of P.grooves) {
    const r0 = 0.17, r1 = 0.95;
    const w0 = 0.006, w1 = 0.024;
    const cx0 = P.hx + P.a * Math.cos(phi) * r0, cy0 = P.hy + P.b * Math.sin(phi) * r0;
    const cx1 = P.hx + P.a * Math.cos(phi) * r1, cy1 = P.hy + P.b * Math.sin(phi) * r1;
    const nx = -Math.sin(phi), ny = Math.cos(phi);
    ctx.beginPath();
    ctx.moveTo(cx0 + nx * w0, cy0 + ny * w0);
    ctx.lineTo(cx1 + nx * w1, cy1 + ny * w1);
    ctx.lineTo(cx1 - nx * w1, cy1 - ny * w1);
    ctx.lineTo(cx0 - nx * w0, cy0 - ny * w0);
    ctx.closePath(); ctx.fill();
  }
  // base band
  ctx.beginPath();
  ctx.moveTo(0.215, 0.80); ctx.lineTo(0.785, 0.80); ctx.lineTo(0.79, 0.95); ctx.lineTo(0.21, 0.95); ctx.closePath();
  ctx.fill();
  ctx.restore();
  ctx.restore();
}

// Erste "S" symbol (dot over a rounded block with two offset slots), unit width, height ~1.32.
function drawErsteS(ctx, x, y, w, color = '#fff') {
  ctx.save();
  ctx.translate(x, y);
  ctx.fillStyle = color;
  ctx.beginPath(); ctx.arc(w * 0.5, w * 0.2, w * 0.2, 0, Math.PI * 2); ctx.fill();
  const top = w * 0.48, h = w * 0.84;
  ctx.beginPath();
  rrect(ctx, 0, top, w, h, w * 0.2);
  // slots cut with evenodd: upper slot open to the right, lower slot open to the left
  const sh = h * 0.15;
  ctx.rect(w * 0.26, top + h * 0.3 - sh / 2, w * 0.74 + 1, sh);
  ctx.rect(-1, top + h * 0.7 - sh / 2, w * 0.74 + 1, sh);
  ctx.fill('evenodd');
  ctx.restore();
}

// "ERSTE" wordmark + S symbol. (x, y) = left baseline. Returns width.
function drawErsteLogo(ctx, x, y, capH, color = '#fff') {
  ctx.save();
  ctx.fillStyle = color;
  ctx.font = `800 ${capH * 1.39}px Montserrat`;
  ctx.textBaseline = 'alphabetic';
  const tw = fillTracked(ctx, 'ERSTE', x, y, -capH * 0.02);
  const sw = capH * 0.82;
  drawErsteS(ctx, x + tw + capH * 0.28, y - capH * 1.18, sw, color);
  ctx.restore();
  return tw + capH * 0.28 + sw;
}

function drawShellLockup(ctx, x, y, h, color = '#fff') {
  // pecten + wordmark; (x,y) top-left, h = pecten height
  drawPecten(ctx, x, y, h / 0.95);
  ctx.save();
  ctx.fillStyle = color;
  ctx.font = `700 ${h * 0.62}px Montserrat`;
  ctx.textBaseline = 'alphabetic';
  const w = fillTracked(ctx, 'Shell', x + h * 1.2, y + h * 0.74, -h * 0.01);
  ctx.restore();
  return h * 1.2 + w;
}

// Erste debit card. Drawn centred at (0,0) in a local frame of width w; caller applies transforms.
function drawErsteCard(ctx, w, t, sheen = 0) {
  const h = w * 0.63, r = w * 0.055;
  ctx.save();
  // drop shadow
  ctx.shadowColor = 'rgba(0,0,0,0.45)'; ctx.shadowBlur = w * 0.08; ctx.shadowOffsetY = w * 0.04;
  rrect(ctx, -w / 2, -h / 2, w, h, r);
  const g = ctx.createLinearGradient(-w / 2, -h / 2, w / 2, h / 2);
  g.addColorStop(0, '#3D86FF'); g.addColorStop(0.45, '#2163DA'); g.addColorStop(1, '#0B2F86');
  ctx.fillStyle = g; ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.save();
  rrect(ctx, -w / 2, -h / 2, w, h, r); ctx.clip();
  // facet shapes
  ctx.fillStyle = 'rgba(255,255,255,0.07)';
  ctx.beginPath(); ctx.moveTo(-w * 0.05, -h / 2); ctx.lineTo(w / 2, -h / 2); ctx.lineTo(w / 2, h * 0.25); ctx.closePath(); ctx.fill();
  ctx.fillStyle = 'rgba(0,10,60,0.18)';
  ctx.beginPath(); ctx.moveTo(-w / 2, h * 0.1); ctx.lineTo(w * 0.15, h / 2); ctx.lineTo(-w / 2, h / 2); ctx.closePath(); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.05)';
  ctx.beginPath(); ctx.moveTo(w * 0.1, h / 2); ctx.lineTo(w / 2, -h * 0.05); ctx.lineTo(w / 2, h / 2); ctx.closePath(); ctx.fill();
  // moving sheen
  if (sheen > 0) {
    const sx = lerp(-w * 1.1, w * 1.1, sheen);
    const sg = ctx.createLinearGradient(sx - w * 0.25, -h / 2, sx + w * 0.25, h / 2);
    sg.addColorStop(0, 'rgba(255,255,255,0)'); sg.addColorStop(0.5, 'rgba(255,255,255,0.38)'); sg.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = sg; ctx.fillRect(-w / 2, -h / 2, w, h);
  }
  ctx.restore();
  // edge highlight
  rrect(ctx, -w / 2 + 1, -h / 2 + 1, w - 2, h - 2, r);
  ctx.strokeStyle = 'rgba(255,255,255,0.25)'; ctx.lineWidth = Math.max(1, w * 0.004); ctx.stroke();
  // logo
  drawErsteLogo(ctx, -w * 0.42, -h * 0.25, w * 0.065, '#fff');
  // chip
  const cx = -w * 0.38, cy = -h * 0.04, cw = w * 0.13, ch = w * 0.1;
  const cg = ctx.createLinearGradient(cx, cy, cx + cw, cy + ch);
  cg.addColorStop(0, '#F4F1EA'); cg.addColorStop(0.5, '#BDB8AE'); cg.addColorStop(1, '#E9E4DA');
  rrect(ctx, cx, cy, cw, ch, w * 0.015); ctx.fillStyle = cg; ctx.fill();
  ctx.strokeStyle = 'rgba(80,70,60,0.5)'; ctx.lineWidth = Math.max(1, w * 0.003);
  ctx.beginPath();
  ctx.moveTo(cx + cw * 0.33, cy); ctx.lineTo(cx + cw * 0.33, cy + ch);
  ctx.moveTo(cx + cw * 0.66, cy); ctx.lineTo(cx + cw * 0.66, cy + ch);
  ctx.moveTo(cx, cy + ch * 0.5); ctx.lineTo(cx + cw * 0.33, cy + ch * 0.5);
  ctx.moveTo(cx + cw * 0.66, cy + ch * 0.5); ctx.lineTo(cx + cw, cy + ch * 0.5);
  ctx.stroke();
  // contactless mark
  ctx.strokeStyle = 'rgba(255,255,255,0.9)'; ctx.lineWidth = w * 0.009; ctx.lineCap = 'round';
  for (let i = 0; i < 4; i++) {
    ctx.beginPath(); ctx.arc(w * 0.33, -h * 0.25, w * (0.018 + i * 0.022), -0.75, 0.75); ctx.stroke();
  }
  // number + label
  ctx.fillStyle = 'rgba(255,255,255,0.85)';
  ctx.font = `600 ${w * 0.052}px Montserrat`;
  fillTracked(ctx, '•••• •••• •••• 2025', -w * 0.42, h * 0.3, w * 0.006);
  ctx.font = `700 ${w * 0.04}px Montserrat`;
  ctx.textAlign = 'right';
  ctx.fillText('DEBIT', w * 0.43, h * 0.4);
  ctx.textAlign = 'left';
  ctx.restore();
}
