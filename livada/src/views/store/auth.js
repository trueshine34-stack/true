import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { field, checkbox, csrfInput } from '../components.js';
import { icon, mark } from '../icons.js';
import { t, tHtml, pick } from '../../lib/i18n.js';

// „Nume” din formulare = numele de familie (în formularul de contact „Nume” e numele complet → altă traducere)
const LAST_NAME = () => pick({ ro: 'Nume', en: 'Last name' });

function shell(ctx, { title, sub, body, side = true, titleText }) {
  const content = html`<section class="auth">
    <div class="auth__card">
      <a class="auth__back" href="/">${icon('arrow-left', { size: 16 })} ${t('Înapoi la magazin')}</a>
      <h1 class="h2 auth__title">${title}</h1>
      ${sub ? html`<p class="auth__sub">${sub}</p>` : ''}
      ${body}
    </div>
    ${side ? html`<aside class="auth__side" aria-hidden="true">
      <div class="auth__pattern">${Array.from({ length: 24 }, (_, i) => html`<span class=${cx(i % 7 === 3 && 'is-leaf')}></span>`)}</div>
      <div class="auth__side-copy">
        ${mark(40)}
        <p class="auth__quote">${t('„Comandă azi până la 16:00 și primești mâine. În easybox, FANbox sau la ușă.”')}</p>
        <ul>
          <li>${icon('package', { size: 18 })} ${t('Istoricul comenzilor și urmărire AWB')}</li>
          <li>${icon('pin', { size: 18 })} ${t('Adrese salvate, checkout în 30 de secunde')}</li>
          <li>${icon('gift', { size: 18 })} ${t('50 lei cadou la prima comandă')}</li>
        </ul>
      </div>
    </aside>` : ''}
  </section>`;
  return page(ctx, { title: titleText || String(title.s ?? title), body: content, noindex: true, bodyClass: 'is-auth', scripts: ['/js/account.js'] });
}

export function loginPage(ctx, { next = '', email = '', error } = {}) {
  return shell(ctx, {
    title: t('Bine ai revenit.'), titleText: t('Autentificare'),
    sub: html`${t('Nu ai cont?')} <a href=${`/cont/inregistrare${next ? `?next=${encodeURIComponent(next)}` : ''}`}>${t('Creează unul în 1 minut')}</a>.`,
    body: html`<form class="form" action="/cont/autentificare" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="next" value=${next}>
      ${error ? html`<p class="form-error" role="alert">${error}</p>` : html`<p class="form-error" role="alert" hidden data-form-error></p>`}
      ${field({ name: 'email', label: t('Email'), type: 'email', value: email, required: true, autocomplete: 'email' })}
      ${field({ name: 'password', label: t('Parola'), type: 'password', required: true, autocomplete: 'current-password' })}
      <div class="form__row">${checkbox({ name: 'remember', label: t('Ține-mă minte'), checked: true })}<a class="link" href="/cont/parola-uitata">${t('Ai uitat parola?')}</a></div>
      <button class="btn btn--dark btn--lg btn--block" type="submit">${t('Autentificare')}</button>
    </form>`,
  });
}

export function registerPage(ctx, { next = '' } = {}) {
  return shell(ctx, {
    title: tHtml('Creează-ți contul <em>Livada.</em>'), titleText: t('Creează cont'),
    sub: html`${t('Ai deja cont?')} <a href=${`/cont/autentificare${next ? `?next=${encodeURIComponent(next)}` : ''}`}>${t('Autentifică-te')}</a>.`,
    body: html`<form class="form" action="/cont/inregistrare" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="next" value=${next}>
      <p class="form-error" role="alert" hidden data-form-error></p>
      <div class="grid2">
        ${field({ name: 'first_name', label: t('Prenume'), required: true, autocomplete: 'given-name' })}
        ${field({ name: 'last_name', label: LAST_NAME(), required: true, autocomplete: 'family-name' })}
      </div>
      ${field({ name: 'email', label: t('Email'), type: 'email', required: true, autocomplete: 'email', hint: t('Îți trimitem un cod de confirmare.') })}
      ${field({ name: 'phone', label: t('Telefon'), type: 'tel', autocomplete: 'tel', inputmode: 'tel' })}
      ${field({ name: 'password', label: t('Parolă'), type: 'password', required: true, autocomplete: 'new-password', hint: t('Minimum 8 caractere, cu cel puțin o literă și o cifră.'), attrs: 'data-password-meter' })}
      <div class="pw-meter" aria-hidden="true" data-pw-meter><span></span><span></span><span></span><span></span></div>
      ${checkbox({ name: 'terms', label: tHtml('Accept <a href="/termeni" target="_blank">Termenii</a> și <a href="/confidentialitate" target="_blank">Politica de confidențialitate</a>.'), required: true })}
      ${checkbox({ name: 'marketing', label: t('Vreau să primesc noutăți și oferte (cel mult de două ori pe lună).') })}
      <button class="btn btn--dark btn--lg btn--block" type="submit">${t('Creează contul')}</button>
    </form>`,
  });
}

export function verifyPage(ctx, { email, cooldown = 0, error, expired = false } = {}) {
  return shell(ctx, {
    title: tHtml('Verifică-ți <em>emailul.</em>'), titleText: t('Confirmă adresa de email'),
    sub: tHtml('Am trimis un cod de 6 cifre la <b>{email}</b>. Introdu-l mai jos sau apasă butonul din email.', { email }),
    body: html`<form class="form" action="/cont/verificare" method="post" data-ajax data-verify-form novalidate>
      ${csrfInput(ctx)}
      ${error ? html`<p class="form-error" role="alert">${error}</p>` : html`<p class="form-error" role="alert" hidden data-form-error></p>`}
      ${expired ? html`<p class="note">${icon('info', { size: 16 })} ${t('Linkul de confirmare a expirat sau a fost deja folosit. Cere un cod nou.')}</p>` : ''}
      <div class="otp" data-otp>
        ${[0, 1, 2, 3, 4, 5].map((i) => html`<input class=${cx('otp__cell', i === 3 && 'otp__cell--gap')} inputmode="numeric" pattern="[0-9]*" maxlength="1" aria-label=${t('Cifra {n}', { n: i + 1 })} autocomplete=${i === 0 ? 'one-time-code' : 'off'} data-otp-cell>`)}
        <input type="hidden" name="code" data-otp-value>
      </div>
      <p class="field__msg" data-error-for="code"></p>
      <button class="btn btn--dark btn--lg btn--block" type="submit">${t('Confirmă adresa')}</button>
    </form>
    <form class="form form--inline" action="/cont/verificare/retrimite" method="post" data-ajax data-resend data-cooldown=${cooldown}>
      ${csrfInput(ctx)}
      <p class="muted">${t('Nu ai primit codul? Verifică și folderul Spam sau')}
      <button class="link-btn" type="submit" data-resend-btn>${t('retrimite codul')}</button><span data-resend-timer></span>.</p>
    </form>
    <p class="muted small">${t('Adresă greșită?')} <a href="/cont/iesire-si-inregistrare" data-logout-link>${t('Creează contul cu alt email')}</a>.</p>`,
  });
}

export function forgotPage(ctx, { sent = false, email = '' } = {}) {
  return shell(ctx, {
    title: sent ? tHtml('Verifică-ți <em>inboxul.</em>') : t('Ai uitat parola?'), titleText: t('Resetare parolă'),
    sub: sent ? tHtml('Dacă există un cont pentru <b>{email}</b>, ți-am trimis un link de resetare valabil 60 de minute.', { email }) : t('Îți trimitem pe email un link pentru o parolă nouă.'),
    body: sent ? html`<a class="btn btn--ghost btn--block" href="/cont/autentificare">${t('Înapoi la autentificare')}</a>` : html`<form class="form" action="/cont/parola-uitata" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<p class="form-error" role="alert" hidden data-form-error></p>
      ${field({ name: 'email', label: t('Email'), type: 'email', required: true, autocomplete: 'email' })}
      <button class="btn btn--dark btn--lg btn--block" type="submit">${t('Trimite linkul')}</button>
    </form>`,
  });
}

export function resetPage(ctx, { token, valid }) {
  return shell(ctx, {
    title: valid ? tHtml('Alege o <em>parolă nouă.</em>') : t('Link expirat'), titleText: t('Parolă nouă'),
    sub: valid ? t('După salvare vei fi autentificat automat; celelalte sesiuni se închid.') : t('Linkul de resetare a expirat sau a fost folosit deja.'),
    body: valid ? html`<form class="form" action="/cont/parola-noua" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="token" value=${token}><p class="form-error" role="alert" hidden data-form-error></p>
      ${field({ name: 'password', label: t('Parolă nouă'), type: 'password', required: true, autocomplete: 'new-password', attrs: 'data-password-meter' })}
      <div class="pw-meter" aria-hidden="true" data-pw-meter><span></span><span></span><span></span><span></span></div>
      <button class="btn btn--dark btn--lg btn--block" type="submit">${t('Salvează parola')}</button>
    </form>` : html`<a class="btn btn--dark btn--block" href="/cont/parola-uitata">${t('Cere un link nou')}</a>`,
  });
}
