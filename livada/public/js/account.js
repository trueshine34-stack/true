import { $, $$, tr } from './app.js';

// ─── cod OTP pe 6 căsuțe: tastare, lipire, ștergere, trimitere automată ───
const otp = $('[data-otp]');
if (otp) {
  const cells = $$('[data-otp-cell]', otp);
  const hidden = $('[data-otp-value]', otp);
  const form = otp.closest('form');
  const sync = () => { hidden.value = cells.map((c) => c.value).join(''); return hidden.value; };
  const fill = (digits, from = 0) => {
    digits.split('').forEach((d, i) => { if (cells[from + i]) cells[from + i].value = d; });
    const next = Math.min(from + digits.length, cells.length - 1);
    cells[next].focus();
    if (sync().length === 6) form.requestSubmit();
  };
  cells.forEach((c, i) => {
    c.addEventListener('input', () => {
      const v = c.value.replace(/\D/g, '');
      c.value = '';
      if (v) fill(v.slice(0, 6 - i), i);
      otp.classList.remove('is-error', 'is-ok');
      sync();
    });
    c.addEventListener('keydown', (e) => {
      if (e.key === 'Backspace' && !c.value && i > 0) { cells[i - 1].value = ''; cells[i - 1].focus(); sync(); e.preventDefault(); }
      if (e.key === 'ArrowLeft' && i > 0) cells[i - 1].focus();
      if (e.key === 'ArrowRight' && i < 5) cells[i + 1].focus();
    });
    c.addEventListener('paste', (e) => {
      const t = (e.clipboardData?.getData('text') || '').replace(/\D/g, '');
      if (!t) return;
      e.preventDefault();
      cells.forEach((x) => { x.value = ''; });
      fill(t.slice(0, 6), 0);
    });
    c.addEventListener('focus', () => c.select());
  });
  setTimeout(() => cells[0].focus(), 50);
  form.addEventListener('ajax:error', () => { otp.classList.remove('is-error', 'is-ok'); void otp.offsetWidth; otp.classList.add('is-error'); cells.forEach((x) => { x.value = ''; }); cells[0].focus(); });
  form.addEventListener('submit', () => { if (sync().length === 6) otp.classList.add('is-ok'); });
}

// ─── retrimitere cod cu numărătoare inversă ─────────────────────────────
const resend = $('[data-resend]');
if (resend) {
  const btn = $('[data-resend-btn]', resend);
  const timer = $('[data-resend-timer]', resend);
  let left = Number(resend.dataset.cooldown) || 0;
  let iv;
  const tick = () => {
    btn.disabled = left > 0;
    timer.textContent = left > 0 ? ` (${tr('în {n} s', { n: left })})` : '';
    if (left <= 0) clearInterval(iv);
    left--;
  };
  const start = (s) => { left = s; clearInterval(iv); tick(); iv = setInterval(tick, 1000); };
  start(left);
  resend.addEventListener('ajax:done', (e) => start(e.detail.cooldown || 60));
}

// ─── indicator de putere a parolei ──────────────────────────────────────
$$('[data-password-meter]').forEach((input) => {
  const meter = input.closest('form').querySelector('[data-pw-meter]');
  input.addEventListener('input', () => {
    const v = input.value;
    let s = 0;
    if (v.length >= 8) s++;
    if (/[a-z]/i.test(v) && /\d/.test(v)) s++;
    if (v.length >= 12) s++;
    if (/[^a-z0-9]/i.test(v) && /[A-Z]/.test(v)) s++;
    meter.dataset.score = v ? Math.max(1, s) : 0;
  });
});
