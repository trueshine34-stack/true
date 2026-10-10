// End card (11.0–15.0 s): both brands, the offer, the promo dates and the legal line.

const PACK = (() => {
  const T0 = 11.006;
  let sparks = [];
  function init() {
    const R = rng(77);
    for (let i = 0; i < 70; i++) sparks.push({ a: R() * Math.PI * 2, v: 160 + R() * 520, r: 1 + R() * 3, life: 0.5 + R() * 0.7 });
  }

  function bg(ctx, plate, t) {
    const lt = t - T0;
    if (plate) {
      const s = lerp(1.12, 1.18, clamp(lt / 4));
      ctx.drawImage(plate, W / 2 - W * s / 2, H / 2 - H * s / 2, W * s, H * s);
    }
    // deep Erste navy from the left, sunset glow breathing through on the right
    const g = ctx.createLinearGradient(0, 0, W, 0);
    g.addColorStop(0, 'rgba(5,16,48,0.95)'); g.addColorStop(0.5, 'rgba(6,20,60,0.82)'); g.addColorStop(1, 'rgba(8,26,74,0.38)');
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    const v = ctx.createRadialGradient(W * 0.78, H * 0.42, 50, W * 0.78, H * 0.42, W * 0.6);
    v.addColorStop(0, 'rgba(255,170,80,0.28)'); v.addColorStop(1, 'rgba(255,170,80,0)');
    ctx.save(); ctx.globalCompositeOperation = 'screen'; ctx.fillStyle = v; ctx.fillRect(0, 0, W, H); ctx.restore();
    // fine diagonal light sweep
    const sw = inv(13.5, 14.6, t);
    if (sw > 0 && sw < 1) {
      ctx.save(); ctx.globalCompositeOperation = 'screen';
      const x = lerp(-600, W + 600, E.ioQ(sw));
      const lg = ctx.createLinearGradient(x - 300, 0, x + 300, H * 0.3);
      lg.addColorStop(0, 'rgba(255,255,255,0)'); lg.addColorStop(0.5, 'rgba(255,240,210,0.07)'); lg.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = lg; ctx.fillRect(0, 0, W, H); ctx.restore();
    }
  }

  function swoosh(ctx, t) {
    const p = E.outExpo(inv(11.55, 12.35, t));
    if (p <= 0) return;
    ctx.save();
    ctx.beginPath(); ctx.rect(0, 0, W * p + 40, H); ctx.clip();
    // red pin-line then the yellow sweep, echoing the canopy fascia
    const curve = (off) => { ctx.beginPath(); ctx.moveTo(1080, H + 10); ctx.bezierCurveTo(1380, 1040 - off, 1640, 990 - off, W + 20, 820 - off); };
    curve(26); ctx.lineTo(W + 20, H + 10); ctx.closePath();
    ctx.fillStyle = C.shellR; ctx.fill();
    curve(12); ctx.lineTo(W + 20, H + 10); ctx.closePath();
    const yg = ctx.createLinearGradient(1080, 0, W, 0);
    yg.addColorStop(0, '#F7B800'); yg.addColorStop(1, '#FFD630');
    ctx.fillStyle = yg; ctx.fill();
    ctx.restore();
  }

  function logos(ctx, t) {
    const p = E.outExpo(inv(11.05, 11.6, t));
    if (p <= 0) return;
    ctx.save();
    ctx.globalAlpha = p;
    ctx.translate(0, (1 - p) * -40);
    const x = 112, y = 74, h = 92;
    const sw = drawShellLockup(ctx, x, y, h, '#fff');
    ctx.fillStyle = 'rgba(255,255,255,0.55)'; ctx.fillRect(x + sw + 44, y + 6, 2, h - 6);
    drawErsteLogo(ctx, x + sw + 92, y + h * 0.78, h * 0.5, '#fff');
    ctx.restore();
  }

  function headline(ctx, t) {
    const x = 108;
    ctx.save();
    ctx.textBaseline = 'alphabetic';
    // "25 LITER" — the number counts up as it lands
    const p1 = inv(11.12, 11.6, t);
    if (p1 > 0) {
      const n = Math.round(25 * E.outC(inv(11.12, 11.5, t)));
      ctx.save();
      ctx.globalAlpha = Math.min(1, p1 * 2);
      ctx.translate(x, 352);
      const s = lerp(1.18, 1, E.outExpo(p1)); ctx.scale(s, s);
      ctx.font = '900 150px Montserrat'; ctx.fillStyle = '#fff';
      ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = 30;
      const nw = ctx.measureText('25').width;
      ctx.fillText(String(n).padStart(2, ' '), 0, 0);
      ctx.fillText('LITER', nw + 40, 0);
      ctx.restore();
    }
    // "ÜZEMANYAG" wipes up
    const p2 = E.outExpo(inv(11.36, 11.8, t));
    if (p2 > 0) {
      ctx.save();
      ctx.beginPath(); ctx.rect(x - 10, 382, 1300, 170); ctx.clip();
      ctx.font = '900 150px Montserrat'; ctx.fillStyle = '#fff';
      ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = 30;
      ctx.fillText('ÜZEMANYAG', x, 528 + (1 - p2) * 160);
      ctx.restore();
    }
    // "INGYEN!" stamps in, then catches a shine
    const p3 = inv(11.62, 11.98, t);
    if (p3 > 0) {
      ctx.save();
      ctx.translate(x, 712);
      const e = E.outBack(p3, 2.0);
      const s = lerp(1.55, 1, e);
      ctx.translate(0, -70); ctx.scale(s, s); ctx.translate(0, 70);
      ctx.globalAlpha = Math.min(1, p3 * 3);
      ctx.font = '900 196px Montserrat';
      ctx.shadowColor = 'rgba(0,0,0,0.4)'; ctx.shadowBlur = 36;
      ctx.fillStyle = C.shellY;
      ctx.fillText('INGYEN!', 0, 0);
      ctx.shadowBlur = 0;
      const sh = inv(12.1, 12.7, t);
      if (sh > 0 && sh < 1) {
        ctx.globalCompositeOperation = 'source-atop';
        const w = ctx.measureText('INGYEN!').width;
        const sx = lerp(-200, w + 200, E.ioQ(sh));
        const g = ctx.createLinearGradient(sx - 120, -160, sx + 120, 0);
        g.addColorStop(0, 'rgba(255,255,255,0)'); g.addColorStop(0.5, 'rgba(255,255,255,0.85)'); g.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.fillStyle = g; ctx.fillRect(-20, -180, w + 40, 220);
      }
      ctx.restore();
      // spark burst on impact
      const sp = inv(11.8, 12.6, t);
      if (sp > 0 && sp < 1) {
        ctx.save(); ctx.globalCompositeOperation = 'lighter';
        for (const k of sparks) {
          const q = clamp(sp / k.life);
          if (q >= 1) continue;
          const d = k.v * E.outC(q);
          const sx = x + 420 + Math.cos(k.a) * d, sy = 640 + Math.sin(k.a) * d * 0.6;
          ctx.fillStyle = `rgba(255,${210 + 40 * (1 - q)},120,${(1 - q) * 0.9})`;
          ctx.beginPath(); ctx.arc(sx, sy, k.r * (1 - q * 0.5), 0, Math.PI * 2); ctx.fill();
        }
        ctx.restore();
      }
    }
    // sub-line
    const p4 = E.outC(inv(12.15, 12.55, t));
    if (p4 > 0) {
      ctx.save();
      ctx.globalAlpha = p4;
      ctx.font = '600 56px Montserrat'; ctx.fillStyle = '#fff';
      fillTracked(ctx, 'Erste Bank ügyfeleknek.', x + 4, 806 + (1 - p4) * 24, 0.5);
      ctx.restore();
    }
    // date badge
    const p5 = inv(12.45, 12.85, t);
    if (p5 > 0) {
      const e = E.outBack(p5, 1.8);
      ctx.save();
      ctx.translate(x, 846);
      ctx.scale(lerp(0.6, 1, e), lerp(0.6, 1, e));
      ctx.globalAlpha = Math.min(1, p5 * 3);
      ctx.font = '800 40px Montserrat';
      const label = '2026. október 12–20.';
      const lw = trackedWidth(ctx, label, 0.5);
      const pulse = 1 + 0.025 * Math.sin(Math.PI * inv(13.2, 13.6, t));
      ctx.scale(pulse, pulse);
      rrect(ctx, 0, 0, lw + 124, 76, 38);
      ctx.fillStyle = C.shellY; ctx.fill();
      // calendar glyph
      ctx.strokeStyle = C.ersteDeep; ctx.lineWidth = 4; ctx.lineJoin = 'round';
      rrect(ctx, 30, 20, 40, 38, 6); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(30, 32); ctx.lineTo(70, 32); ctx.moveTo(40, 14); ctx.lineTo(40, 24); ctx.moveTo(60, 14); ctx.lineTo(60, 24); ctx.stroke();
      ctx.fillStyle = C.ersteDeep;
      fillTracked(ctx, label, 92, 52, 0.5);
      ctx.restore();
    }
    ctx.restore();
  }

  function card(ctx, t) {
    const p = E.outC(inv(11.85, 12.5, t));
    if (p <= 0) return;
    const fl = Math.sin((t - 12.5) * 1.6) * 8;
    ctx.save();
    ctx.translate(lerp(W + 500, 1500, p), 470 + fl);
    ctx.rotate(lerp(0.5, -0.13, p) + Math.sin((t - 12) * 1.1) * 0.012);
    // soft glow behind the card
    ctx.save(); ctx.globalCompositeOperation = 'screen';
    glowCircle(ctx, 0, 0, 520, '#ffb05a', 0.35 * p); ctx.restore();
    drawErsteCard(ctx, 600, t, inv(12.5, 13.3, t) + inv(14.0, 14.8, t) * 0);
    ctx.restore();
  }

  function tagline(ctx, t) {
    // "Shell és Erste. Több lehetőség az Ön mindennapjaira." — follows VO3
    const a1 = E.outC(inv(11.3, 11.7, t)), a2 = E.outC(inv(12.62, 13.1, t));
    if (a1 <= 0 && a2 <= 0) return;
    ctx.save();
    const x = 112, y = 978;
    ctx.font = '800 34px Montserrat';
    ctx.globalAlpha = Math.max(a1, 0) * 0 + a2;
    ctx.fillStyle = '#fff';
    const w1 = fillTracked(ctx, 'Shell és Erste.', x, y, 0.4);
    ctx.font = '500 34px Montserrat';
    ctx.globalAlpha = a2;
    fillTracked(ctx, ' Több lehetőség az Ön mindennapjaira.', x + w1 + 4, y, 0.4);
    ctx.restore();
  }

  function legal(ctx, t) {
    const a = E.outC(inv(12.75, 13.25, t));
    if (a <= 0) return;
    ctx.save();
    ctx.globalAlpha = a * 0.72;
    ctx.font = '500 18px Montserrat'; ctx.fillStyle = '#fff';
    ctx.fillText('Az ajánlat 2026. október 12. és 20. között minden Erste Bank ügyfél számára érvényes', 112, 1030);
    ctx.fillText('a Shell töltőállomásokon. A kép illusztráció.', 112, 1054);
    ctx.restore();
  }

  function draw(ctx, plate, t) {
    bg(ctx, plate, t);
    swoosh(ctx, t);
    card(ctx, t);
    logos(ctx, t);
    headline(ctx, t);
    tagline(ctx, t);
    legal(ctx, t);
  }
  return { init, draw };
})();
