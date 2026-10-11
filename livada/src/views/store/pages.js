import { html, raw } from '../../lib/html.js';
import { page } from '../layout.js';
import { field, csrfInput, carrierBadge, breadcrumbs } from '../components.js';
import { icon } from '../icons.js';
import { lei } from '../../lib/format.js';
import { getSetting } from '../../db/index.js';
import { methods, lockerStats, estimate } from '../../services/shipping.js';

function doc(ctx, { title, lead, body, slug, eyebrow }) {
  const content = html`<section class="section section--page"><div class="wrap wrap--narrow">
    ${breadcrumbs([{ label: title }])}
    <header class="doc-head">${eyebrow ? html`<p class="eyebrow">${eyebrow}</p>` : ''}<h1 class="h1">${title}</h1>${lead ? html`<p class="lead lead--sm">${lead}</p>` : ''}</header>
    <div class="doc">${body}</div>
  </div></section>`;
  return page(ctx, { title, description: lead, body: content, canonical: `/${slug}` });
}

const co = () => getSetting('company') || {};
const sh = () => getSetting('showroom') || {};
const st = () => getSetting('store') || {};

export function aboutPage(ctx) {
  return doc(ctx, {
    slug: 'despre', title: 'Despre Livada', eyebrow: 'Povestea noastră',
    lead: 'O livadă se îngrijește, rând cu rând. Așa ne îngrijim și clienții.',
    body: html`
      <p>Livada a pornit în 2023 din București, cu o idee simplă: un magazin dedicat exclusiv tehnologiei Apple, în care fiecare comandă este tratată ca și cum ar fi a noastră. Nu suntem marketplace și nu lucrăm cu vânzători terți — tot ce vezi pe site se află în depozitul nostru, este nou, sigilat și vine cu factură fiscală.</p>
      <h2>Ce ne diferențiază</h2>
      <ul class="ticks">
        <li><b>Consultanță reală.</b> Te ajutăm să alegi între configurații, la telefon sau în showroom, fără să-ți vindem ce nu-ți trebuie.</li>
        <li><b>Livrare cum îți convine.</b> La ușă cu FAN Courier, Sameday, Cargus, DPD, GLS sau Poșta Română, în peste o mie de lockere, ori în aceeași zi în București.</li>
        <li><b>Retur 30 de zile.</b> Dublu față de minimul legal, cu ridicare gratuită de la tine.</li>
        <li><b>Configurare inclusă.</b> În showroom îți mutăm datele de pe vechiul telefon și activăm noul dispozitiv.</li>
      </ul>
      <h2>Date de identificare</h2>
      <dl class="kv">
        <div><dt>Denumire</dt><dd>${co().legal_name}</dd></div><div><dt>CUI</dt><dd>${co().cui}</dd></div>
        <div><dt>Nr. Reg. Com.</dt><dd>${co().reg_com}</dd></div><div><dt>EUID</dt><dd>${co().euid}</dd></div>
        <div><dt>Sediu</dt><dd>${co().address}</dd></div><div><dt>Capital social</dt><dd>${co().capital}</dd></div>
        <div><dt>Cont bancar</dt><dd>${co().iban} · ${co().bank}</dd></div>
      </dl>`,
  });
}

export const contactSentBlock = () => html`<div class="empty"><div class="empty__icon">${icon('check', { size: 28 })}</div><h2>Mesaj trimis</h2><p>Mulțumim! Revenim în cel mult o zi lucrătoare.</p></div>`;

export function contactPage(ctx, { sent = false, errors = {}, values = {} } = {}) {
  const c = co(), s = sh();
  const content = html`<section class="section section--page"><div class="wrap">
    ${breadcrumbs([{ label: 'Contact' }])}
    <div class="contact">
      <div class="contact__info">
        <p class="eyebrow">Contact</p>
        <h1 class="h1">Suntem aici <em>pentru tine.</em></h1>
        <ul class="contact__list">
          <li>${icon('phone', { size: 20 })}<div><b><a href=${`tel:${(c.phone || '').replace(/\s/g, '')}`}>${c.phone}</a></b><span>${c.phone_hours}</span></div></li>
          <li>${icon('mail', { size: 20 })}<div><b><a href=${`mailto:${c.email}`}>${c.email}</a></b><span>Răspundem în cel mult o zi lucrătoare</span></div></li>
          <li>${icon('package', { size: 20 })}<div><b><a href=${`mailto:${c.orders_email}`}>${c.orders_email}</a></b><span>Pentru comenzi, facturi și retururi</span></div></li>
        </ul>
        <div class="showroom-card" id="showroom">
          <h2 class="h4">${s.name}</h2>
          <p>${icon('pin', { size: 18 })} ${s.address}</p><p>${icon('clock', { size: 18 })} ${s.hours}</p>
          <a class="btn btn--ghost btn--sm" target="_blank" rel="noopener" href=${`https://www.openstreetmap.org/?mlat=${s.lat}&mlon=${s.lng}#map=17/${s.lat}/${s.lng}`}>Deschide harta ${icon('external', { size: 14 })}</a>
        </div>
      </div>
      <div class="panel contact__form">
        ${sent ? contactSentBlock() : html`
        <h2 class="h3">Trimite-ne un mesaj</h2>
        <form class="form" action="/contact" method="post" data-ajax novalidate>
          ${csrfInput(ctx)}<p class="form-error" role="alert" hidden data-form-error></p>
          <div class="grid2">
            ${field({ name: 'name', label: 'Nume', required: true, value: values.name, error: errors.name, autocomplete: 'name' })}
            ${field({ name: 'email', label: 'Email', type: 'email', required: true, value: values.email, error: errors.email, autocomplete: 'email' })}
            ${field({ name: 'phone', label: 'Telefon', type: 'tel', value: values.phone, autocomplete: 'tel' })}
            ${field({ name: 'topic', label: 'Subiect', value: values.topic })}
          </div>
          <div class="field field--area"><textarea id="f-message" name="message" rows="5" placeholder=" " required maxlength="2000">${values.message || ''}</textarea><label for="f-message">Mesaj</label><p class="field__msg" data-error-for="message">${errors.message || ''}</p></div>
          <input type="text" name="website" class="hp" tabindex="-1" autocomplete="off" aria-hidden="true">
          <button class="btn btn--dark btn--lg" type="submit">Trimite mesajul</button>
        </form>`}
      </div>
    </div>
  </div></section>`;
  return page(ctx, { title: 'Contact', description: `Contact Livada: ${c.phone}, ${c.email}. Showroom: ${s.address}.`, body: content });
}

export function deliveryPage(ctx) {
  const ms = methods();
  const stats = lockerStats();
  const byProv = Object.fromEntries(stats.map((s) => [s.provider, s]));
  const groups = [['courier', 'Curier la adresă'], ['express', 'Livrare în aceeași zi'], ['locker', 'Lockere (pachetomate)'], ['pickup', 'Ridicare personală']];
  return doc(ctx, {
    slug: 'livrare', title: 'Livrare și lockere', eyebrow: 'Livrare',
    lead: `Comenzile plasate până la ora ${st().cutoff_hour ?? 16}:00 în zilele lucrătoare pleacă în aceeași zi. Livrare gratuită la adresă de la 500 lei și în lockere de la 300 lei.`,
    body: html`${groups.map(([type, label]) => {
      const list = ms.filter((m) => m.type === type);
      if (!list.length) return '';
      return html`<h2>${label}</h2><div class="rate-table">${list.map((m) => html`<div class="rate-row">
        ${carrierBadge(m.carrier, { size: 36 })}
        <div class="rate-row__main"><b>${m.name}</b><span>${m.description}</span>${type === 'locker' && byProv[m.carrier] ? html`<span class="muted">${byProv[m.carrier].n} lockere în ${byProv[m.carrier].cities} localități</span>` : ''}</div>
        <div class="rate-row__eta"><span>${m.eta}</span><small>${estimate(m)}</small></div>
        <div class="rate-row__price"><b>${m.price ? lei(m.price) : 'Gratuit'}</b>${m.free_over ? html`<small>gratuit de la ${Math.round(m.free_over / 100)} lei</small>` : ''}</div>
      </div>`)}</div>`;
    })}
    <h2>Plata ramburs</h2>
    <p>Plata la livrare (numerar sau card) costă ${lei(st().cod_fee ?? 999)} și este disponibilă pentru comenzile de până la ${Math.round((st().cod_max ?? 1500000) / 100).toLocaleString('ro-RO')} lei. La easybox și FANbox plătești cu cardul direct la locker.</p>
    <h2>Despre harta lockerelor</h2>
    <p class="muted">Locațiile lockerelor provin din OpenStreetMap (© contribuitorii OpenStreetMap, licență ODbL) și sunt actualizate periodic din API-urile curierilor.</p>`,
  });
}

export function returnsPage(ctx) {
  return doc(ctx, {
    slug: 'retur', title: 'Retur 30 de zile', eyebrow: 'Retur',
    lead: 'Te-ai răzgândit? Ai 30 de zile calendaristice de la livrare să returnezi produsul, fără să ne explici de ce.',
    body: html`
      <ol class="steps-list">
        <li><b>Completează formularul</b> din contul tău sau scrie-ne la ${co().orders_email} cu numărul comenzii.</li>
        <li><b>Împachetează produsul</b> în ambalajul original, cu toate accesoriile.</li>
        <li><b>Ridicăm noi coletul</b> gratuit, de la adresa ta sau îl lași în orice easybox.</li>
        <li><b>Primești banii</b> în cel mult 14 zile de la primirea returului, prin aceeași metodă de plată.</li>
      </ol>
      <h2>Condiții</h2>
      <p>Conform OUG 34/2014, ai dreptul legal de retragere în 14 zile; Livada îl extinde la 30 de zile. Produsele trebuie să fie complete și fără urme de uzură. Produsele desigilate pot fi returnate; dacă prezintă urme de utilizare peste ce e necesar pentru a constata natura și funcționarea lor, putem reține contravaloarea diminuării valorii.</p>
      <p>Nu se pot returna produsele personalizate (gravate) și cele cu sigiliu de igienă desfăcut (căști in-ear), conform legii.</p>
      <h2>Formular-tip de retragere</h2>
      <p>Poți folosi formularul din Anexa 2 la OUG 34/2014 sau orice declarație neechivocă, trimisă la ${co().orders_email} ori la adresa ${co().address}.</p>`,
  });
}

export function warrantyPage(ctx) {
  return doc(ctx, {
    slug: 'garantie', title: 'Garanție și service', eyebrow: 'Garanție',
    lead: 'Toate produsele sunt noi, originale și beneficiază de garanția legală de conformitate de 24 de luni.',
    body: html`
      <p>Garanția legală de conformitate (Legea 449/2003 și OUG 140/2021) acoperă orice neconformitate existentă la livrare și apărută în 2 ani. Pe lângă aceasta, produsele Apple beneficiază de garanția limitată Apple de un an, valabilă în rețeaua de service autorizat Apple din România.</p>
      <h2>Cum trimiți un produs în service</h2>
      <ul class="ticks"><li>Scrie-ne la ${co().orders_email} cu numărul comenzii și descrierea problemei.</li>
      <li>Organizăm ridicarea gratuită prin curier și te ținem la curent cu fiecare etapă.</li>
      <li>Termenul legal de remediere este de maximum 15 zile calendaristice.</li></ul>
      <p>Poți aduce produsul și direct în showroom: ${sh().address}.</p>`,
  });
}

export function termsPage(ctx) {
  const c = co();
  return doc(ctx, {
    slug: 'termeni', title: 'Termeni și condiții', eyebrow: 'Legal', lead: 'Ultima actualizare: 1 octombrie 2026.',
    body: html`
      <h2>1. Cine suntem</h2><p>Site-ul livada.ro este administrat de ${c.legal_name}, CUI ${c.cui}, ${c.reg_com}, cu sediul în ${c.address}, email ${c.email}, telefon ${c.phone}.</p>
      <h2>2. Comanda și contractul</h2><p>Plasarea comenzii reprezintă o ofertă de cumpărare. Contractul se încheie la confirmarea comenzii de către Livada prin email. Ne rezervăm dreptul de a anula comenzile în caz de eroare evidentă de preț sau stoc, cu rambursarea integrală a sumelor plătite.</p>
      <h2>3. Prețuri și plată</h2><p>Prețurile sunt exprimate în lei și includ TVA (21%). Acceptăm plata cu cardul (3‑D Secure), ramburs, transfer bancar și rate fără dobândă prin cardurile partenere. Factura fiscală se emite electronic, la expediere, și se transmite prin RO e-Factura pentru persoanele juridice.</p>
      <h2>4. Livrare</h2><p>Livrăm pe teritoriul României prin curieri, lockere sau ridicare din showroom, conform paginii <a href="/livrare">Livrare</a>. Riscul trece la cumpărător la predarea produsului.</p>
      <h2>5. Dreptul de retragere</h2><p>Ai 30 de zile de la livrare să te retragi din contract, conform paginii <a href="/retur">Retur</a> și OUG 34/2014.</p>
      <h2>6. Garanție</h2><p>Conform paginii <a href="/garantie">Garanție</a>.</p>
      <h2>7. Precomenzi</h2><p>Produsele marcate „Precomandă” se livrează în ordinea plasării comenzilor, după data lansării. Poți anula precomanda oricând înainte de expediere, fără costuri.</p>
      <h2>8. Soluționarea litigiilor</h2><p>Pentru reclamații ne poți scrie la ${c.email}. Poți apela și la ANPC (anpc.ro), la procedura SAL sau la platforma europeană SOL (ec.europa.eu/consumers/odr).</p>`,
  });
}

export function privacyPage(ctx) {
  const c = co();
  return doc(ctx, {
    slug: 'confidentialitate', title: 'Politica de confidențialitate', eyebrow: 'GDPR', lead: 'Cum colectăm, folosim și protejăm datele tale personale.',
    body: html`
      <h2>Operatorul</h2><p>${c.legal_name}, ${c.address}, ${c.email}.</p>
      <h2>Ce date prelucrăm și de ce</h2>
      <ul class="ticks"><li><b>Date de cont și comandă</b> (nume, email, telefon, adrese) — pentru executarea contractului (art. 6(1)(b) GDPR).</li>
      <li><b>Date de facturare</b> — obligație legală fiscală (art. 6(1)(c)), păstrate 10 ani.</li>
      <li><b>Email marketing</b> — doar cu acordul tău (art. 6(1)(a)), retractabil oricând.</li>
      <li><b>Date tehnice</b> (IP, browser) — interes legitim pentru securitate și prevenirea fraudei.</li></ul>
      <h2>Cui transmitem datele</h2><p>Curierilor (FAN Courier, Sameday, Cargus, DPD, GLS, Poșta Română, Packeta) strict pentru livrare, procesatorului de plăți, contabilității și autorităților, când legea o cere. Nu vindem datele tale.</p>
      <h2>Drepturile tale</h2><p>Acces, rectificare, ștergere, restricționare, portabilitate și opoziție. Le poți exercita la ${c.email} sau direct din contul tău (export JSON). Ai dreptul de a depune plângere la ANSPDCP (dataprotection.ro).</p>
      <h2>Securitate</h2><p>Parolele sunt stocate cu hash scrypt, conexiunea este criptată TLS, iar datele cardului nu ajung niciodată pe serverele noastre.</p>`,
  });
}

export function cookiesPage(ctx) {
  return doc(ctx, {
    slug: 'cookies', title: 'Politica de cookie-uri', eyebrow: 'Cookie-uri', lead: 'Folosim puține cookie-uri, iar cele neesențiale doar cu acordul tău.',
    body: html`<table class="table"><thead><tr><th>Cookie</th><th>Scop</th><th>Durată</th></tr></thead><tbody>
      <tr><td>lv_vid</td><td>Identificator anonim pentru protecția formularelor (CSRF)</td><td>1 an</td></tr>
      <tr><td>lv_cart</td><td>Păstrează coșul de cumpărături</td><td>60 de zile</td></tr>
      <tr><td>lv_sid</td><td>Sesiunea de autentificare</td><td>până la 30 de zile</td></tr>
      <tr><td>lv_flash</td><td>Mesaje de confirmare între pagini</td><td>1 minut</td></tr>
      <tr><td>lv_consent (localStorage)</td><td>Alegerea ta privind cookie-urile</td><td>1 an</td></tr></tbody></table>
      <p>Nu folosim în prezent cookie-uri de publicitate. Dacă vom adăuga cookie-uri de analiză, ele se vor activa doar după ce alegi „Accept toate”.</p>
      <button class="btn btn--ghost btn--sm" type="button" data-cookie-reset>Schimbă preferințele</button>`,
  });
}

const FAQ = [
  ['Produsele sunt originale și noi?', 'Da. Vindem exclusiv produse Apple noi, sigilate, din distribuția oficială pentru Uniunea Europeană, cu factură și garanție de 24 de luni.'],
  ['Cât durează livrarea?', 'Comenzile plasate în zilele lucrătoare până la 16:00 pleacă în aceeași zi și ajung de regulă a doua zi. În București și Ilfov poți primi comanda chiar în aceeași seară.'],
  ['Cum aleg un locker?', 'La finalizarea comenzii alege „Locker”, apoi lockerul dorit pe hartă — easybox, FANbox, Ship & Go, DPD, GLS sau Packeta. Primești un cod de deschidere prin SMS.'],
  ['Pot plăti în rate?', 'Da, 3, 6 sau 12 rate fără dobândă cu cardurile de credit partenere, pentru comenzi de minimum 500 lei.'],
  ['Emiteți factură pe firmă?', 'Sigur. La checkout alege „Persoană juridică” și completează CUI-ul — validăm cifra de control automat. Factura se transmite și în RO e-Factura.'],
  ['Pot returna un produs desigilat?', 'Da, în 30 de zile, dacă este complet și fără urme de utilizare. Excepție fac căștile in-ear cu sigiliul de igienă desfăcut.'],
  ['Ce se întâmplă cu o precomandă?', 'Precomenzile se livrează în ordinea plasării, imediat după lansare. Le poți anula gratuit oricând înainte de expediere.'],
  ['Am nevoie de cont ca să comand?', 'Nu, poți comanda ca vizitator. Cu un cont confirmat îți vezi istoricul, adresele salvate și primești 50 lei la prima comandă.'],
];

export function helpPage(ctx) {
  return doc(ctx, {
    slug: 'ajutor', title: 'Întrebări frecvente', eyebrow: 'Ajutor', lead: 'Răspunsuri rapide. Pentru restul, suntem la un telefon distanță.',
    body: html`<div class="faq">${FAQ.map(([q, a]) => html`<details class="faq__item"><summary>${q}${icon('plus', { size: 18 })}</summary><p>${a}</p></details>`)}</div>
      <p><a class="btn btn--dark" href="/contact">Contactează-ne</a></p>`,
  });
}

export const STATIC_PAGES = {
  despre: aboutPage, livrare: deliveryPage, retur: returnsPage, garantie: warrantyPage, termeni: termsPage,
  confidentialitate: privacyPage, cookies: cookiesPage, ajutor: helpPage,
};
export { raw };
