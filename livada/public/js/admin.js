// Panou de administrare: rânduri clicabile, tooltips grafice, tab-uri, meniu mobil, confirmări.
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

// rânduri de tabel clicabile (fără a strica linkurile/butoanele din ele)
document.addEventListener('click', (e) => {
  const tr = e.target.closest('tr[data-href]');
  if (!tr || e.target.closest('a, button, input, select, label, form')) return;
  if (e.metaKey || e.ctrlKey) window.open(tr.dataset.href, '_blank');
  else location.href = tr.dataset.href;
});

// meniu mobil
$('[data-adm-menu]')?.addEventListener('click', () => $('[data-adm-side]')?.classList.toggle('is-open'));

// confirmări
document.addEventListener('submit', (e) => {
  const msg = e.target.dataset?.confirm;
  if (msg && !confirm(msg === '?' ? 'Ești sigur? / Вы уверены?' : msg)) e.preventDefault();
}, true);

// print (proformă)
$$('[data-print]').forEach((b) => b.addEventListener('click', () => print()));

// previzualizare emailuri pe tab-uri
const frame = $('[data-tab-frame]');
$$('[data-tabs] [data-tab]').forEach((b) => b.addEventListener('click', () => {
  $$('[data-tabs] [data-tab]').forEach((x) => x.classList.toggle('is-active', x === b));
  frame.src = b.dataset.tab;
}));

// grafic linie: crosshair + tooltip (snap la cea mai apropiată zi)
$$('.chart[data-chart]').forEach((fig) => {
  const dataEl = $('.chart__data', fig);
  const svg = $('svg', fig);
  const tip = $('.chart__tip', fig);
  if (dataEl) {
    const { W, pts } = JSON.parse(dataEl.textContent);
    const cross = $('.chart__cross', svg);
    const dot = $('.chart__hover', svg);
    const fmtDay = (d) => new Date(`${d}T12:00:00Z`).toLocaleDateString(document.documentElement.lang === 'ru' ? 'ru-RU' : 'ro-RO', { weekday: 'short', day: 'numeric', month: 'short' });
    const show = (clientX) => {
      const r = svg.getBoundingClientRect();
      const x = ((clientX - r.left) / r.width) * W;
      let best = pts[0];
      for (const p of pts) if (Math.abs(p.x - x) < Math.abs(best.x - x)) best = p;
      cross.setAttribute('x1', best.x); cross.setAttribute('x2', best.x); cross.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', best.x); dot.setAttribute('cy', best.y); dot.setAttribute('visibility', 'visible');
      tip.hidden = false;
      tip.replaceChildren();
      const b = document.createElement('b'); b.textContent = best.v;
      const s = document.createElement('span'); s.textContent = fmtDay(best.d);
      tip.append(b, s);
      const sx = (best.x / W) * r.width;
      tip.style.left = `${Math.min(Math.max(sx, 60), r.width - 60)}px`;
      tip.style.top = `${(best.y / svg.viewBox.baseVal.height) * r.height}px`;
    };
    const hide = () => { cross.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); tip.hidden = true; };
    svg.addEventListener('pointermove', (e) => show(e.clientX));
    svg.addEventListener('pointerleave', hide);
  } else {
    // coloane: tooltip pe zona de lovire a fiecărei coloane
    $$('.chart__hit', svg).forEach((hit) => {
      hit.addEventListener('pointerenter', () => {
        const r = svg.getBoundingClientRect();
        const hr = hit.getBoundingClientRect();
        tip.hidden = false;
        tip.textContent = hit.dataset.tip;
        tip.style.left = `${hr.left - r.left + hr.width / 2}px`;
        tip.style.top = `${r.height * 0.15}px`;
        hit.previousElementSibling?.classList?.add('is-hover');
      });
      hit.addEventListener('pointerleave', () => { tip.hidden = true; hit.previousElementSibling?.classList?.remove('is-hover'); });
    });
  }
});
