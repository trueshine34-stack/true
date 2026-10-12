import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { field, checkbox, csrfInput, selectField, emptyState, pill, productGrid } from '../components.js';
import { icon } from '../icons.js';
import { lei, dateShort, dateTime, phone as fmtPhone, plural } from '../../lib/format.js';
import { COUNTIES } from '../../db/defaults.js';
import { STATUSES } from '../../services/orders.js';
import { timeline, orderDetails } from './order.js';
import { t, tHtml } from '../../lib/i18n.js';

const NAV = [['/cont', 'Prezentare', 'home'], ['/cont/comenzi', 'Comenzi', 'package'], ['/cont/favorite', 'Favorite', 'heart'],
  ['/cont/adrese', 'Adrese', 'pin'], ['/cont/profil', 'Profil și securitate', 'settings']];

function shell(ctx, active, title, body) {
  const u = ctx.user;
  const content = html`<section class="section section--page"><div class="wrap account">
    <div class="account__nav">
      <div class="account__me"><span class="avatar avatar--lg">${(u.first_name || '?')[0]}</span><div><b>${u.first_name} ${u.last_name}</b><small>${u.email}</small></div></div>
      <nav aria-label=${t('Contul meu')}><ul>
        ${NAV.map(([href, label, ic]) => html`<li><a class=${cx(active === href && 'is-active')} href=${href}>${icon(ic, { size: 18 })}${t(label)}</a></li>`)}
        ${u.role !== 'customer' ? html`<li><a href="/admin">${icon('grid', { size: 18 })}${t('Panou admin')}</a></li>` : ''}
        <li><form action="/cont/iesire" method="post">${csrfInput(ctx)}<button type="submit">${icon('logout', { size: 18 })}${t('Ieșire')}</button></form></li>
      </ul></nav>
    </div>
    <div class="account__main">${body}</div>
  </div></section>`;
  return page(ctx, { title, body: content, noindex: true, scripts: ['/js/account.js'] });
}

function orderRow(o) {
  const s = STATUSES[o.status];
  return html`<a class="orow" href=${`/cont/comenzi/${o.number}`}>
    <span class="orow__no"><b>${o.number}</b><small>${dateShort(o.created_at)}</small></span>
    <span class="orow__imgs">${(o.thumbs || []).slice(0, 3).map((src) => html`<img src=${src} alt="" width="44" height="44" loading="lazy">`)}</span>
    <span class="orow__status">${pill(t(s.label), s.tone)}</span>
    <span class="orow__total">${lei(o.total)}</span>${icon('chevron-right', { size: 18 })}
  </a>`;
}

export function dashboardPage(ctx, { orders, favoritesCount, addressesCount }) {
  const u = ctx.user;
  const last = orders[0];
  const body = html`
    <h1 class="h1">${tHtml('Bună, <em>{name}.</em>', { name: u.first_name })}</h1>
    <div class="dash-cards">
      <a class="dash-card" href="/cont/comenzi"><span>${icon('package', { size: 22 })}</span><b>${orders.length}</b><small>${t(orders.length === 1 ? 'comandă' : 'comenzi')}</small></a>
      <a class="dash-card" href="/cont/favorite"><span>${icon('heart', { size: 22 })}</span><b>${favoritesCount}</b><small>${t('favorite')}</small></a>
      <a class="dash-card" href="/cont/adrese"><span>${icon('pin', { size: 22 })}</span><b>${addressesCount}</b><small>${t(addressesCount === 1 ? 'adresă' : 'adrese')}</small></a>
    </div>
    ${last ? html`<div class="panel"><div class="panel__head"><h2 class="h4">${t('Ultima comandă')}</h2><a class="link-arrow" href=${`/cont/comenzi/${last.number}`}>${t('Detalii')} ${icon('arrow-right', { size: 14 })}</a></div>
      ${orderRow(last)}${timeline(last, last.events || [])}</div>` : html`<div class="panel">${emptyState({ iconName: 'package', title: t('Nicio comandă încă'), text: t('Comenzile tale și livrările în curs vor apărea aici.'), cta: t('Descoperă produsele'), href: '/' })}</div>`}
    ${orders.length ? '' : html`<div class="panel panel--gift"><div>${icon('gift', { size: 22 })}</div><div><b>${t('Cadou de bun venit')}</b><p>${tHtml('50 lei reducere la prima comandă de minimum 500 lei cu codul <span class="code">BUNVENIT50</span>.')}</p></div></div>`}`;
  return shell(ctx, '/cont', t('Contul meu'), body);
}

export function ordersPage(ctx, orders) {
  const body = html`<h1 class="h2">${t('Comenzile mele')}</h1>
    ${orders.length ? html`<div class="panel panel--flush">${orders.map(orderRow)}</div>` : emptyState({ iconName: 'package', title: t('Nicio comandă încă'), cta: t('Începe cumpărăturile'), href: '/' })}`;
  return shell(ctx, '/cont/comenzi', t('Comenzile mele'), body);
}

export function accountOrderPage(ctx, order, items, events) {
  const body = html`<a class="back-link" href="/cont/comenzi">${icon('arrow-left', { size: 16 })} ${t('Toate comenzile')}</a>
    <div class="panel__head"><h1 class="h2">${t('Comanda {number}', { number: order.number })}</h1>${pill(t(STATUSES[order.status].label), STATUSES[order.status].tone)}</div>
    <p class="muted">${t('Plasată pe {date}', { date: dateTime(order.created_at) })}</p>
    ${timeline(order, events)}
    ${orderDetails(order, items)}`;
  return shell(ctx, '/cont/comenzi', t('Comanda {number}', { number: order.number }), body);
}

export function favoritesPage(ctx, products) {
  const body = html`<h1 class="h2">${t('Favorite')}</h1>
    ${products.length ? productGrid(products, { size: 'sm', heading: 2 }) : emptyState({ iconName: 'heart', title: t('Lista de favorite e goală'), text: t('Apasă inimioara de pe pagina unui produs ca să-l salvezi aici.'), cta: t('Explorează'), href: '/' })}`;
  return shell(ctx, '/cont/favorite', t('Favorite'), body);
}

export function addressesPage(ctx, addresses, { errors = {}, values = {} } = {}) {
  const body = html`<h1 class="h2">${t('Adrese de livrare')}</h1>
    <div class="addr-grid">
      ${addresses.map((a) => html`<div class=${cx('addr', a.is_default && 'is-default')}>
        ${a.is_default ? html`<span class="spill spill--leaf">${t('Implicită')}</span>` : ''}
        <p><b>${a.first_name} ${a.last_name}</b></p><p>${a.street}</p><p>${t('{city}, jud. {county}', { city: a.city, county: a.county })}${a.postcode ? `, ${a.postcode}` : ''}</p><p class="muted">${fmtPhone(a.phone)}</p>
        <div class="addr__actions">
          ${!a.is_default ? html`<form action=${`/cont/adrese/${a.id}/implicita`} method="post">${csrfInput(ctx)}<button class="link-btn" type="submit">${t('Setează implicită')}</button></form>` : ''}
          <form action=${`/cont/adrese/${a.id}/sterge`} method="post" data-confirm=${t('Ștergi această adresă?')}>${csrfInput(ctx)}<button class="link-btn link-btn--danger" type="submit">${t('Șterge')}</button></form>
        </div>
      </div>`)}
    </div>
    <div class="panel"><h2 class="h4">${t('Adaugă o adresă')}</h2>
      <form class="form" action="/cont/adrese" method="post" novalidate>
        ${csrfInput(ctx)}
        <div class="grid2">
          ${field({ name: 'first_name', label: t('Prenume'), value: values.first_name ?? ctx.user.first_name, error: errors.first_name, required: true })}
          ${field({ name: 'last_name', label: t('Nume'), value: values.last_name ?? ctx.user.last_name, error: errors.last_name, required: true })}
          ${field({ name: 'phone', label: t('Telefon'), type: 'tel', value: values.phone ?? ctx.user.phone, error: errors.phone, required: true })}
          ${selectField({ name: 'county', label: t('Județ'), options: COUNTIES, value: values.county, error: errors.county, required: true })}
          ${field({ name: 'city', label: t('Localitate'), value: values.city, error: errors.city, required: true })}
          ${field({ name: 'postcode', label: t('Cod poștal'), value: values.postcode, error: errors.postcode, inputmode: 'numeric', maxlength: 6 })}
          ${field({ name: 'street', label: t('Strada, număr, bloc, apartament'), value: values.street, error: errors.street, required: true, cls: 'span2' })}
        </div>
        ${checkbox({ name: 'is_default', label: t('Folosește ca adresă implicită') })}
        <button class="btn btn--dark" type="submit">${t('Salvează adresa')}</button>
      </form></div>`;
  return shell(ctx, '/cont/adrese', t('Adrese'), body);
}

export function profilePage(ctx, { errors = {}, saved } = {}) {
  const u = ctx.user;
  const body = html`<h1 class="h2">${t('Profil și securitate')}</h1>
    <div class="panel"><h2 class="h4">${t('Date personale')}</h2>
      <form class="form" action="/cont/profil" method="post" novalidate>
        ${csrfInput(ctx)}
        <div class="grid2">
          ${field({ name: 'first_name', label: t('Prenume'), value: u.first_name, error: errors.first_name, required: true })}
          ${field({ name: 'last_name', label: t('Nume'), value: u.last_name, error: errors.last_name, required: true })}
          ${field({ name: 'email', label: t('Email'), type: 'email', value: u.email, attrs: 'disabled', hint: t(u.email_verified_at ? '✓ Confirmat' : 'Neconfirmat') })}
          ${field({ name: 'phone', label: t('Telefon'), type: 'tel', value: u.phone, error: errors.phone })}
        </div>
        ${checkbox({ name: 'marketing', label: t('Vreau să primesc noutăți și oferte pe email.'), checked: !!u.marketing_opt_in })}
        <button class="btn btn--dark" type="submit">${t('Salvează')}</button>
      </form></div>
    <div class="panel"><h2 class="h4">${t('Schimbă parola')}</h2>
      <form class="form" action="/cont/parola" method="post" novalidate>
        ${csrfInput(ctx)}
        <div class="grid2">
          ${field({ name: 'current_password', label: t('Parola actuală'), type: 'password', error: errors.current_password, required: true, autocomplete: 'current-password' })}
          ${field({ name: 'new_password', label: t('Parola nouă'), type: 'password', error: errors.new_password, required: true, autocomplete: 'new-password' })}
        </div>
        <button class="btn btn--ghost" type="submit">${t('Actualizează parola')}</button>
      </form></div>
    <div class="panel panel--muted"><h2 class="h4">${t('Datele tale (GDPR)')}</h2>
      <p>${tHtml('Poți descărca toate datele pe care le avem despre tine sau ne poți cere ștergerea contului la <a href="mailto:contact@livada.ro">contact@livada.ro</a>.')}</p>
      <a class="btn btn--ghost btn--sm" href="/cont/export">${icon('download', { size: 16 })} ${t('Descarcă datele mele (JSON)')}</a></div>`;
  return shell(ctx, '/cont/profil', t('Profil'), body);
}

export { plural };
