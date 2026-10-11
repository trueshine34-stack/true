import { html } from '../../lib/html.js';
import { page } from '../layout.js';
import { icon } from '../icons.js';

export function errorPage(ctx, status, message) {
  const notFound = status === 404;
  const body = html`<section class="section section--page"><div class="wrap wrap--narrow errorpage">
    <p class="errorpage__code">${status}</p>
    <h1 class="h1">${notFound ? html`Pagina asta <em>n-a mai rodit.</em>` : status === 403 ? 'Acces restricționat' : html`Ceva n-a mers <em>cum trebuia.</em>`}</h1>
    <p class="lead lead--sm">${notFound ? 'Poate a fost mutată sau produsul nu mai este în ofertă. Încearcă o căutare:' : message}</p>
    ${notFound ? html`<form class="errorpage__search" action="/cautare" method="get">${icon('search', { size: 20 })}<input name="q" placeholder="Caută un produs" aria-label="Caută"><button class="btn btn--dark btn--sm">Caută</button></form>` : ''}
    <a class="btn btn--ghost" href="/">${icon('arrow-left', { size: 16 })} Înapoi la prima pagină</a>
  </div></section>`;
  return page(ctx, { title: notFound ? 'Pagină inexistentă' : 'Eroare', body, noindex: true });
}
