import { html, cx } from '../../lib/html.js';
import { page } from '../layout.js';
import { field, checkbox, csrfInput } from '../components.js';
import { icon, mark } from '../icons.js';

function shell(ctx, { title, sub, body, side = true, titleText }) {
  const content = html`<section class="auth">
    <div class="auth__card">
      <a class="auth__back" href="/">${icon('arrow-left', { size: 16 })} Înapoi la magazin</a>
      <h1 class="h2 auth__title">${title}</h1>
      ${sub ? html`<p class="auth__sub">${sub}</p>` : ''}
      ${body}
    </div>
    ${side ? html`<aside class="auth__side" aria-hidden="true">
      <div class="auth__pattern">${Array.from({ length: 24 }, (_, i) => html`<span class=${cx(i % 7 === 3 && 'is-leaf')}></span>`)}</div>
      <div class="auth__side-copy">
        ${mark(40)}
        <p class="auth__quote">„Comandă azi până la 16:00 și primești mâine. În easybox, FANbox sau la ușă.”</p>
        <ul>
          <li>${icon('package', { size: 18 })} Istoricul comenzilor și urmărire AWB</li>
          <li>${icon('pin', { size: 18 })} Adrese salvate, checkout în 30 de secunde</li>
          <li>${icon('gift', { size: 18 })} 50 lei cadou la prima comandă</li>
        </ul>
      </div>
    </aside>` : ''}
  </section>`;
  return page(ctx, { title: titleText || String(title.s ?? title), body: content, noindex: true, bodyClass: 'is-auth', scripts: ['/js/account.js'] });
}

export function loginPage(ctx, { next = '', email = '', error } = {}) {
  return shell(ctx, {
    title: html`Bine ai revenit.`, titleText: 'Autentificare',
    sub: html`Nu ai cont? <a href=${`/cont/inregistrare${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Creează unul în 1 minut</a>.`,
    body: html`<form class="form" action="/cont/autentificare" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="next" value=${next}>
      ${error ? html`<p class="form-error" role="alert">${error}</p>` : html`<p class="form-error" role="alert" hidden data-form-error></p>`}
      ${field({ name: 'email', label: 'Email', type: 'email', value: email, required: true, autocomplete: 'email' })}
      ${field({ name: 'password', label: 'Parola', type: 'password', required: true, autocomplete: 'current-password' })}
      <div class="form__row">${checkbox({ name: 'remember', label: 'Ține-mă minte', checked: true })}<a class="link" href="/cont/parola-uitata">Ai uitat parola?</a></div>
      <button class="btn btn--dark btn--lg btn--block" type="submit">Autentificare</button>
    </form>`,
  });
}

export function registerPage(ctx, { next = '' } = {}) {
  return shell(ctx, {
    title: html`Creează-ți contul <em>Livada.</em>`, titleText: 'Creează cont',
    sub: html`Ai deja cont? <a href=${`/cont/autentificare${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Autentifică-te</a>.`,
    body: html`<form class="form" action="/cont/inregistrare" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="next" value=${next}>
      <p class="form-error" role="alert" hidden data-form-error></p>
      <div class="grid2">
        ${field({ name: 'first_name', label: 'Prenume', required: true, autocomplete: 'given-name' })}
        ${field({ name: 'last_name', label: 'Nume', required: true, autocomplete: 'family-name' })}
      </div>
      ${field({ name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'email', hint: 'Îți trimitem un cod de confirmare.' })}
      ${field({ name: 'phone', label: 'Telefon', type: 'tel', autocomplete: 'tel', inputmode: 'tel' })}
      ${field({ name: 'password', label: 'Parolă', type: 'password', required: true, autocomplete: 'new-password', hint: 'Minimum 8 caractere, cu cel puțin o literă și o cifră.', attrs: 'data-password-meter' })}
      <div class="pw-meter" aria-hidden="true" data-pw-meter><span></span><span></span><span></span><span></span></div>
      ${checkbox({ name: 'terms', label: html`Accept <a href="/termeni" target="_blank">Termenii</a> și <a href="/confidentialitate" target="_blank">Politica de confidențialitate</a>.`, required: true })}
      ${checkbox({ name: 'marketing', label: 'Vreau să primesc noutăți și oferte (cel mult de două ori pe lună).' })}
      <button class="btn btn--dark btn--lg btn--block" type="submit">Creează contul</button>
    </form>`,
  });
}

export function verifyPage(ctx, { email, cooldown = 0, error, expired = false } = {}) {
  return shell(ctx, {
    title: html`Verifică-ți <em>emailul.</em>`, titleText: 'Confirmă adresa de email',
    sub: html`Am trimis un cod de 6 cifre la <b>${email}</b>. Introdu-l mai jos sau apasă butonul din email.`,
    body: html`<form class="form" action="/cont/verificare" method="post" data-ajax data-verify-form novalidate>
      ${csrfInput(ctx)}
      ${error ? html`<p class="form-error" role="alert">${error}</p>` : html`<p class="form-error" role="alert" hidden data-form-error></p>`}
      ${expired ? html`<p class="note">${icon('info', { size: 16 })} Linkul de confirmare a expirat sau a fost deja folosit. Cere un cod nou.</p>` : ''}
      <div class="otp" data-otp>
        ${[0, 1, 2, 3, 4, 5].map((i) => html`<input class=${cx('otp__cell', i === 3 && 'otp__cell--gap')} inputmode="numeric" pattern="[0-9]*" maxlength="1" aria-label=${`Cifra ${i + 1}`} autocomplete=${i === 0 ? 'one-time-code' : 'off'} data-otp-cell>`)}
        <input type="hidden" name="code" data-otp-value>
      </div>
      <p class="field__msg" data-error-for="code"></p>
      <button class="btn btn--dark btn--lg btn--block" type="submit">Confirmă adresa</button>
    </form>
    <form class="form form--inline" action="/cont/verificare/retrimite" method="post" data-ajax data-resend data-cooldown=${cooldown}>
      ${csrfInput(ctx)}
      <p class="muted">Nu ai primit codul? Verifică și folderul Spam sau
      <button class="link-btn" type="submit" data-resend-btn>retrimite codul</button><span data-resend-timer></span>.</p>
    </form>
    <p class="muted small">Adresă greșită? <a href="/cont/iesire-si-inregistrare" data-logout-link>Creează contul cu alt email</a>.</p>`,
  });
}

export function forgotPage(ctx, { sent = false, email = '' } = {}) {
  return shell(ctx, {
    title: sent ? html`Verifică-ți <em>inboxul.</em>` : html`Ai uitat parola?`, titleText: 'Resetare parolă',
    sub: sent ? html`Dacă există un cont pentru <b>${email}</b>, ți-am trimis un link de resetare valabil 60 de minute.` : 'Îți trimitem pe email un link pentru o parolă nouă.',
    body: sent ? html`<a class="btn btn--ghost btn--block" href="/cont/autentificare">Înapoi la autentificare</a>` : html`<form class="form" action="/cont/parola-uitata" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<p class="form-error" role="alert" hidden data-form-error></p>
      ${field({ name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'email' })}
      <button class="btn btn--dark btn--lg btn--block" type="submit">Trimite linkul</button>
    </form>`,
  });
}

export function resetPage(ctx, { token, valid }) {
  return shell(ctx, {
    title: valid ? html`Alege o <em>parolă nouă.</em>` : 'Link expirat', titleText: 'Parolă nouă',
    sub: valid ? 'După salvare vei fi autentificat automat; celelalte sesiuni se închid.' : 'Linkul de resetare a expirat sau a fost folosit deja.',
    body: valid ? html`<form class="form" action="/cont/parola-noua" method="post" data-ajax novalidate>
      ${csrfInput(ctx)}<input type="hidden" name="token" value=${token}><p class="form-error" role="alert" hidden data-form-error></p>
      ${field({ name: 'password', label: 'Parolă nouă', type: 'password', required: true, autocomplete: 'new-password', attrs: 'data-password-meter' })}
      <div class="pw-meter" aria-hidden="true" data-pw-meter><span></span><span></span><span></span><span></span></div>
      <button class="btn btn--dark btn--lg btn--block" type="submit">Salvează parola</button>
    </form>` : html`<a class="btn btn--dark btn--block" href="/cont/parola-uitata">Cere un link nou</a>`,
  });
}
