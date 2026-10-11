import { html, cx, raw } from '../../lib/html.js';
import { asset } from '../../lib/static.js';
import { icon } from '../icons.js';
import { logo } from '../layout.js';
import { one } from '../../db/index.js';
import { csrfInput } from '../components.js';
import { config } from '../../config.js';

export function adminPage(ctx, { title, body, active, t, actions = '', narrow = false, scripts = [] }) {
  const u = ctx.user;
  const counts = {
    orders: one("SELECT COUNT(*) n FROM orders WHERE status = 'new'").n,
    messages: one('SELECT COUNT(*) n FROM contact_messages WHERE handled = 0').n,
  };
  const nav = [
    ['', [['/admin', 'nav.dashboard', 'chart', 'dashboard']]],
    ['nav.sales', [['/admin/comenzi', 'nav.orders', 'package', 'orders', counts.orders], ['/admin/clienti', 'nav.customers', 'users', 'customers'], ['/admin/promotii', 'nav.promos', 'tag', 'promos']]],
    ['nav.catalog', [['/admin/produse', 'nav.products', 'grid', 'products'], ['/admin/livrare', 'nav.delivery', 'truck', 'delivery']]],
    ['nav.system', [['/admin/mesaje', 'nav.messages', 'chat', 'messages', counts.messages], ['/admin/emailuri', 'nav.emails', 'mail', 'emails'], ['/admin/setari', 'nav.settings', 'settings', 'settings'], ['/admin/jurnal', 'nav.audit', 'inbox', 'audit']]],
  ];
  return html`<!doctype html>
<html lang=${t.lang}>
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · Livada Admin</title>
<meta name="robots" content="noindex, nofollow">
<link rel="icon" href="/img/brand/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href=${asset('/css/admin.css')}>
<meta name="csrf-token" content=${ctx.csrf}>
</head>
<body class="adm">
<aside class="adm-side" data-adm-side>
  <div class="adm-side__brand"><a href="/admin" aria-label="Panou">${logo()}</a><span class="adm-badge">Admin</span></div>
  <nav class="adm-nav" aria-label="Panou de administrare">
    ${nav.map(([group, items]) => html`<div class="adm-nav__group">
      ${group ? html`<p class="adm-nav__title">${t(group)}</p>` : ''}
      ${items.map(([href, key, ic, id, badge]) => html`<a class=${cx('adm-nav__link', active === id && 'is-active')} href=${href}>${icon(ic, { size: 18 })}<span>${t(key)}</span>${badge ? html`<em>${badge}</em>` : ''}</a>`)}
    </div>`)}
  </nav>
  <div class="adm-side__foot">
    <a class="adm-nav__link" href="/" target="_blank">${icon('external', { size: 18 })}<span>${t('nav.store')}</span></a>
    <form action="/admin/limba" method="post" class="adm-lang">${csrfInput(ctx)}
      ${icon('globe', { size: 16 })}<button name="lang" value="ro" class=${cx(t.lang === 'ro' && 'is-on')}>RO</button><button name="lang" value="ru" class=${cx(t.lang === 'ru' && 'is-on')}>RU</button>
    </form>
    <div class="adm-me"><span class="avatar">${(u.first_name || 'A')[0]}</span><div><b>${u.first_name} ${u.last_name}</b><small>${u.role}</small></div>
      <form action="/cont/iesire" method="post">${csrfInput(ctx)}<button class="adm-icon" aria-label=${t('nav.logout')} title=${t('nav.logout')}>${icon('logout', { size: 18 })}</button></form></div>
  </div>
</aside>
<div class="adm-main">
  <header class="adm-top">
    <button class="adm-icon adm-top__menu" type="button" aria-label="Meniu" data-adm-menu>${icon('menu', { size: 20 })}</button>
    <form class="adm-search" action="/admin/comenzi" method="get" role="search">${icon('search', { size: 18 })}<input name="q" placeholder=${t('search.orders')} aria-label=${t('search.orders')}></form>
  </header>
  ${ctx.flashMessage ? html`<div class=${`adm-flash adm-flash--${ctx.flashMessage.type}`} role="status">${icon(ctx.flashMessage.type === 'error' ? 'info' : 'check', { size: 18 })} ${ctx.flashMessage.message}</div>` : ''}
  <main class=${cx('adm-content', narrow && 'adm-content--narrow')}>
    <div class="adm-head"><h1>${title}</h1><div class="adm-head__actions">${actions}</div></div>
    ${body}
  </main>
</div>
<div class="toasts" aria-live="polite" data-toasts></div>
<script type="module" src=${asset('/js/admin.js')}></script>
${scripts.map((s) => html`<script type="module" src=${asset(s)}></script>`)}
</body></html>`;
}

export function adminLogin(ctx, t, { error, email = '', next = '' } = {}) {
  return html`<!doctype html><html lang=${t.lang}><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${t('login.title')}</title><meta name="robots" content="noindex"><link rel="icon" href="/img/brand/favicon.svg">
<link rel="stylesheet" href=${asset('/css/admin.css')}></head>
<body class="adm adm--login"><main class="adm-login">
  <div class="adm-login__card">
    <div class="adm-side__brand">${logo()}<span class="adm-badge">Admin</span></div>
    <h1>${t('login.title')}</h1><p class="muted">${t('login.sub')}</p>
    ${error ? html`<p class="adm-alert">${error}</p>` : ''}
    <form method="post" action="/admin/autentificare" class="adm-form">
      ${csrfInput(ctx)}<input type="hidden" name="next" value=${next}>
      <label>Email<input name="email" type="email" required autocomplete="username" value=${email}></label>
      <label>Parola / Пароль<input name="password" type="password" required autocomplete="current-password"></label>
      <button class="adm-btn adm-btn--dark adm-btn--block" type="submit">${t('login.btn')}</button>
    </form>
    ${!config.production && !config.admin.password ? html`<p class="adm-login__hint">${raw('Demo: <code>admin@livada.ro</code> / <code>Livada2026!</code>')}</p>` : ''}
  </div>
</main></body></html>`;
}
