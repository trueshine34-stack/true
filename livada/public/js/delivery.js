// Pagina „Livrare”: harta tuturor lockerelor și punctelor de ridicare (doar explorare)
import { $ } from './app.js';
import { createLockerPicker } from './lockers.js';

const modal = $('[data-locker-modal]');
const cfg = JSON.parse($('#lockers-data')?.textContent || 'null');
if (modal && cfg) {
  const picker = createLockerPicker({ modal, cfg });
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-open-lockers]');
    if (b) picker.open({ locateMe: b.hasAttribute('data-locate') });
  });
}
