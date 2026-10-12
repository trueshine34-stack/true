import { html, raw, jsonScript } from '../../lib/html.js';
import { page } from '../layout.js';
import { field, csrfInput, carrierBadge, carrierLogo, breadcrumbs } from '../components.js';
import { icon } from '../icons.js';
import { lei } from '../../lib/format.js';
import { t, tHtml, pick, locale } from '../../lib/i18n.js';
import { getSetting } from '../../db/index.js';
import { methods, estimate } from '../../services/shipping.js';
import { lockerNetworks, lockerConfig, lockerModal } from './lockers.js';

function doc(ctx, { title, lead, body, slug, eyebrow, after = '', scripts }) {
  const content = html`<section class="section section--page"><div class="wrap wrap--narrow">
    ${breadcrumbs([{ label: title }])}
    <header class="doc-head">${eyebrow ? html`<p class="eyebrow">${eyebrow}</p>` : ''}<h1 class="h1">${title}</h1>${lead ? html`<p class="lead lead--sm">${lead}</p>` : ''}</header>
    <div class="doc">${body}</div>
  </div></section>${after}`;
  return page(ctx, { title, description: lead, body: content, canonical: `/${slug}`, scripts });
}

const co = () => getSetting('company') || {};
const sh = () => getSetting('showroom') || {};
const st = () => getSetting('store') || {};

export function aboutPage(ctx) {
  return doc(ctx, {
    slug: 'despre', title: t('Despre Livada'), eyebrow: t('Povestea noastră'),
    lead: t('O livadă se îngrijește, rând cu rând. Așa ne îngrijim și clienții.'),
    body: html`${pick({ ro: html`
      <p>Livada a pornit în 2023 din București, cu o idee simplă: un magazin dedicat exclusiv tehnologiei Apple, în care fiecare comandă este tratată ca și cum ar fi a noastră. Nu suntem marketplace și nu lucrăm cu vânzători terți — tot ce vezi pe site se află în depozitul nostru, este nou, sigilat și vine cu factură fiscală.</p>
      <h2>Ce ne diferențiază</h2>
      <ul class="ticks">
        <li><b>Consultanță reală.</b> Te ajutăm să alegi între configurații, la telefon sau în showroom, fără să-ți vindem ce nu-ți trebuie.</li>
        <li><b>Livrare cum îți convine.</b> La ușă cu FAN Courier, Sameday, Cargus, DPD, GLS sau Poșta Română, în peste o mie de lockere, ori în aceeași zi în București.</li>
        <li><b>Retur 30 de zile.</b> Dublu față de minimul legal, cu ridicare gratuită de la tine.</li>
        <li><b>Configurare inclusă.</b> În showroom îți mutăm datele de pe vechiul telefon și activăm noul dispozitiv.</li>
      </ul>`, en: html`
      <p>Livada started in Bucharest in 2023 with a simple idea: a shop dedicated exclusively to Apple technology, where every order is treated as if it were our own. We're not a marketplace and we don't work with third-party sellers — everything you see on the site is in our warehouse, brand new, sealed and comes with a VAT invoice.</p>
      <h2>What sets us apart</h2>
      <ul class="ticks">
        <li><b>Real advice.</b> We help you choose between configurations, over the phone or in our showroom, without selling you anything you don't need.</li>
        <li><b>Delivery that suits you.</b> To your door with FAN Courier, Sameday, Cargus, DPD, GLS or Poșta Română, to more than a thousand lockers, or on the same day in Bucharest.</li>
        <li><b>30-day returns.</b> Twice the legal minimum, with free collection from your address.</li>
        <li><b>Set-up included.</b> In our showroom we'll move your data from your old phone and activate your new device.</li>
      </ul>` })}
      <h2>${t('Date de identificare')}</h2>
      <dl class="kv">
        <div><dt>${t('Denumire')}</dt><dd>${co().legal_name}</dd></div><div><dt>${t('CUI')}</dt><dd>${co().cui}</dd></div>
        <div><dt>${t('Nr. Reg. Com.')}</dt><dd>${co().reg_com}</dd></div><div><dt>EUID</dt><dd>${co().euid}</dd></div>
        <div><dt>${t('Sediu')}</dt><dd>${co().address}</dd></div><div><dt>${t('Capital social')}</dt><dd>${co().capital}</dd></div>
        <div><dt>${t('Cont bancar')}</dt><dd>${co().iban} · ${co().bank}</dd></div>
      </dl>`,
  });
}

export const contactSentBlock = () => html`<div class="empty"><div class="empty__icon">${icon('check', { size: 28 })}</div><h2>${t('Mesaj trimis')}</h2><p>${t('Mulțumim! Revenim în cel mult o zi lucrătoare.')}</p></div>`;

export function contactPage(ctx, { sent = false, errors = {}, values = {} } = {}) {
  const c = co(), s = sh();
  const content = html`<section class="section section--page"><div class="wrap">
    ${breadcrumbs([{ label: t('Contact') }])}
    <div class="contact">
      <div class="contact__info">
        <p class="eyebrow">${t('Contact')}</p>
        <h1 class="h1">${tHtml('Suntem aici <em>pentru tine.</em>')}</h1>
        <ul class="contact__list">
          <li>${icon('phone', { size: 20 })}<div><b><a href=${`tel:${(c.phone || '').replace(/\s/g, '')}`}>${c.phone}</a></b><span>${t(c.phone_hours)}</span></div></li>
          <li>${icon('mail', { size: 20 })}<div><b><a href=${`mailto:${c.email}`}>${c.email}</a></b><span>${t('Răspundem în cel mult o zi lucrătoare')}</span></div></li>
          <li>${icon('package', { size: 20 })}<div><b><a href=${`mailto:${c.orders_email}`}>${c.orders_email}</a></b><span>${t('Pentru comenzi, facturi și retururi')}</span></div></li>
        </ul>
        <div class="showroom-card" id="showroom">
          <h2 class="h4">${s.name}</h2>
          <p>${icon('pin', { size: 18 })} ${s.address}</p><p>${icon('clock', { size: 18 })} ${t(s.hours)}</p>
          <a class="btn btn--ghost btn--sm" target="_blank" rel="noopener" href=${`https://www.openstreetmap.org/?mlat=${s.lat}&mlon=${s.lng}#map=17/${s.lat}/${s.lng}`}>${t('Deschide harta')} ${icon('external', { size: 14 })}</a>
        </div>
      </div>
      <div class="panel contact__form">
        ${sent ? contactSentBlock() : html`
        <h2 class="h3">${t('Trimite-ne un mesaj')}</h2>
        <form class="form" action="/contact" method="post" data-ajax novalidate>
          ${csrfInput(ctx)}<p class="form-error" role="alert" hidden data-form-error></p>
          <div class="grid2">
            ${field({ name: 'name', label: t('Nume'), required: true, value: values.name, error: errors.name, autocomplete: 'name' })}
            ${field({ name: 'email', label: t('Email'), type: 'email', required: true, value: values.email, error: errors.email, autocomplete: 'email' })}
            ${field({ name: 'phone', label: t('Telefon'), type: 'tel', value: values.phone, autocomplete: 'tel' })}
            ${field({ name: 'topic', label: t('Subiect'), value: values.topic })}
          </div>
          <div class="field field--area"><textarea id="f-message" name="message" rows="5" placeholder=" " required maxlength="2000">${values.message || ''}</textarea><label for="f-message">${t('Mesaj')}</label><p class="field__msg" data-error-for="message">${errors.message || ''}</p></div>
          <input type="text" name="website" class="hp" tabindex="-1" autocomplete="off" aria-hidden="true">
          <button class="btn btn--dark btn--lg" type="submit">${t('Trimite mesajul')}</button>
        </form>`}
      </div>
    </div>
  </div></section>`;
  return page(ctx, { title: t('Contact'), description: t('Contact Livada: {phone}, {email}. Showroom: {address}.', { phone: c.phone, email: c.email, address: s.address }), body: content });
}

export function deliveryPage(ctx) {
  const ms = methods();
  const nets = lockerNetworks(ms, 0);
  const byNet = Object.fromEntries(nets.map((n) => [n.key, n]));
  const total = nets.reduce((a, n) => a + n.lockers + n.points, 0);
  const nf = (n) => Number(n).toLocaleString(locale());
  const groups = [['courier', t('Curier la adresă')], ['express', t('Livrare în aceeași zi')], ['locker', t('Lockere și puncte de ridicare')], ['pickup', t('Ridicare personală')]];
  // „804 lockere și 1.908 puncte de ridicare în 665 localități”
  const netText = (n) => {
    const v = { lockers: nf(n.lockers), points: nf(n.points), cities: nf(n.cities) };
    if (n.lockers && n.points) return t('{lockers} lockere și {points} puncte de ridicare în {cities} localități', v);
    if (n.points) return t('{points} puncte de ridicare în {cities} localități', v);
    return t('{lockers} lockere în {cities} localități', v);
  };
  return doc(ctx, {
    slug: 'livrare', title: t('Livrare și lockere'), eyebrow: t('Livrare'),
    lead: t('Comenzile plasate până la ora {hour}:00 în zilele lucrătoare pleacă în aceeași zi. Livrare gratuită la adresă de la 500 lei și în lockere de la 300 lei.', { hour: st().cutoff_hour ?? 16 }),
    scripts: ['/js/delivery.js'],
    body: html`
    <div class="lmap-cta">
      <div><b>${t('{n} lockere și puncte de ridicare', { n: nf(total) })}</b><span>${t('în toate cele 42 de județe — vezi pe hartă care e cel mai aproape de tine.')}</span></div>
      <button class="btn btn--dark" type="button" data-open-lockers>${icon('pin', { size: 18 })} ${t('Deschide harta')}</button>
    </div>
    ${groups.map(([type, label]) => {
    const list = ms.filter((m) => m.type === type && (type !== 'locker' || byNet[m.carrier]));
    if (!list.length) return '';
    return html`<h2>${label}</h2><div class="rate-table">${list.map((m) => {
      const n = m.type === 'locker' ? byNet[m.carrier] : null;
      return html`<div class="rate-row">
        ${m.type === 'pickup' || m.type === 'express' ? carrierBadge(m.carrier, { size: 44 }) : carrierLogo(m.carrier)}
        <div class="rate-row__main"><b>${t(m.name)}</b><span>${t(m.description)}</span>${n ? html`<span class="muted">${netText(n)}</span>` : ''}</div>
        <div class="rate-row__eta"><span>${t(m.eta)}</span><small>${estimate(m)}</small></div>
        <div class="rate-row__price"><b>${m.price ? lei(m.price) : t('Gratuit')}</b>${m.free_over ? html`<small>${t('gratuit de la {amount} lei', { amount: Math.round(m.free_over / 100) })}</small>` : ''}</div>
      </div>`;
    })}</div>`;
  })}
    <h2>${t('Plata ramburs')}</h2>
    <p>${t('Plata la livrare (numerar sau card) costă {fee} și este disponibilă pentru comenzile de până la {max} lei. La easybox și FANbox plătești cu cardul direct la locker.', { fee: lei(st().cod_fee ?? 999), max: Math.round((st().cod_max ?? 1500000) / 100).toLocaleString('ro-RO') })}</p>
    <h2>${t('Despre harta lockerelor')}</h2>
    <p class="muted">${pick({
    ro: html`Locațiile provin din listele publicate de FAN Courier (FANbox), GLS (lockere și ParcelShop), Cargus (lockere și Ship & Go) și DPD (DPD Box și DPD Pickup); punctele easybox, din OpenStreetMap (© contribuitorii OpenStreetMap, ODbL). Lista se actualizează periodic. Hărți © OpenStreetMap.`,
    en: html`Locations come from the lists published by FAN Courier (FANbox), GLS (lockers and ParcelShop), Cargus (lockers and Ship & Go) and DPD (DPD Box and DPD Pickup); easybox points come from OpenStreetMap (© OpenStreetMap contributors, ODbL). The list is updated regularly. Maps © OpenStreetMap.`,
  })}</p>`,
    after: html`${lockerModal(nets, { title: t('Harta lockerelor'), mode: 'browse' })}${jsonScript('lockers-data', lockerConfig(nets, 'browse'))}`,
  });
}

export function returnsPage(ctx) {
  return doc(ctx, {
    slug: 'retur', title: t('Retur 30 de zile'), eyebrow: t('Retur'),
    lead: t('Te-ai răzgândit? Ai 30 de zile calendaristice de la livrare să returnezi produsul, fără să ne explici de ce.'),
    body: pick({ ro: html`
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
    en: html`
      <ol class="steps-list">
        <li><b>Fill in the form</b> in your account, or email us at ${co().orders_email} with your order number.</li>
        <li><b>Pack the product</b> in its original packaging, with all its accessories.</li>
        <li><b>We collect the parcel</b> free of charge from your address, or you can drop it off at any easybox.</li>
        <li><b>Get your money back</b> within 14 days of us receiving the return, using the same payment method.</li>
      </ol>
      <h2>Conditions</h2>
      <p>Under Government Emergency Ordinance (OUG) 34/2014 on consumer rights, you have a statutory right to withdraw from the purchase within 14 days; Livada extends this to 30 days. Products must be complete and show no signs of wear. Unsealed products can be returned; if they show signs of use beyond what is necessary to establish their nature and functioning, we may withhold an amount equal to the reduction in their value.</p>
      <p>As provided by law, personalised (engraved) products and products whose hygiene seal has been broken (in-ear headphones) cannot be returned.</p>
      <h2>Model withdrawal form</h2>
      <p>You can use the model withdrawal form in Annex 2 to OUG 34/2014 or any other unequivocal statement, sent to ${co().orders_email} or by post to ${co().address}.</p>` }),
  });
}

export function warrantyPage(ctx) {
  return doc(ctx, {
    slug: 'garantie', title: t('Garanție și service'), eyebrow: t('Garanție'),
    lead: t('Toate produsele sunt noi, originale și beneficiază de garanția legală de conformitate de 24 de luni.'),
    body: pick({ ro: html`
      <p>Garanția legală de conformitate (Legea 449/2003 și OUG 140/2021) acoperă orice neconformitate existentă la livrare și apărută în 2 ani. Pe lângă aceasta, produsele Apple beneficiază de garanția limitată Apple de un an, valabilă în rețeaua de service autorizat Apple din România.</p>
      <h2>Cum trimiți un produs în service</h2>
      <ul class="ticks"><li>Scrie-ne la ${co().orders_email} cu numărul comenzii și descrierea problemei.</li>
      <li>Organizăm ridicarea gratuită prin curier și te ținem la curent cu fiecare etapă.</li>
      <li>Termenul legal de remediere este de maximum 15 zile calendaristice.</li></ul>
      <p>Poți aduce produsul și direct în showroom: ${sh().address}.</p>`,
    en: html`
      <p>The statutory guarantee of conformity (Law 449/2003 and Government Emergency Ordinance 140/2021) covers any lack of conformity that existed at the time of delivery and becomes apparent within 2 years. In addition, Apple products come with Apple's one-year limited warranty, honoured by Apple's authorised service network in Romania.</p>
      <h2>How to send a product for repair</h2>
      <ul class="ticks"><li>Email us at ${co().orders_email} with your order number and a description of the problem.</li>
      <li>We arrange free collection by courier and keep you updated at every stage.</li>
      <li>The legal deadline for bringing the product into conformity is 15 calendar days at most.</li></ul>
      <p>You can also bring the product straight to our showroom: ${sh().address}.</p>` }),
  });
}

export function termsPage(ctx) {
  const c = co();
  return doc(ctx, {
    slug: 'termeni', title: t('Termeni și condiții'), eyebrow: t('Legal'), lead: t('Ultima actualizare: 1 octombrie 2026.'),
    body: pick({ ro: html`
      <h2>1. Cine suntem</h2><p>Site-ul livada.ro este administrat de ${c.legal_name}, CUI ${c.cui}, ${c.reg_com}, cu sediul în ${c.address}, email ${c.email}, telefon ${c.phone}.</p>
      <h2>2. Comanda și contractul</h2><p>Plasarea comenzii reprezintă o ofertă de cumpărare. Contractul se încheie la confirmarea comenzii de către Livada prin email. Ne rezervăm dreptul de a anula comenzile în caz de eroare evidentă de preț sau stoc, cu rambursarea integrală a sumelor plătite.</p>
      <h2>3. Prețuri și plată</h2><p>Prețurile sunt exprimate în lei și includ TVA (21%). Acceptăm plata cu cardul (3‑D Secure), ramburs, transfer bancar și rate fără dobândă prin cardurile partenere. Factura fiscală se emite electronic, la expediere, și se transmite prin RO e-Factura pentru persoanele juridice.</p>
      <h2>4. Livrare</h2><p>Livrăm pe teritoriul României prin curieri, lockere sau ridicare din showroom, conform paginii <a href="/livrare">Livrare</a>. Riscul trece la cumpărător la predarea produsului.</p>
      <h2>5. Dreptul de retragere</h2><p>Ai 30 de zile de la livrare să te retragi din contract, conform paginii <a href="/retur">Retur</a> și OUG 34/2014.</p>
      <h2>6. Garanție</h2><p>Conform paginii <a href="/garantie">Garanție</a>.</p>
      <h2>7. Precomenzi</h2><p>Produsele marcate „Precomandă” se livrează în ordinea plasării comenzilor, după data lansării. Poți anula precomanda oricând înainte de expediere, fără costuri.</p>
      <h2>8. Soluționarea litigiilor</h2><p>Pentru reclamații ne poți scrie la ${c.email}. Poți apela și la ANPC (anpc.ro), la procedura SAL sau la platforma europeană SOL (ec.europa.eu/consumers/odr).</p>`,
    en: html`
      <h2>1. Who we are</h2><p>The livada.ro website is operated by ${c.legal_name}, tax identification number (CUI) ${c.cui}, Trade Register number ${c.reg_com}, with its registered office at ${c.address}, email ${c.email}, phone ${c.phone}.</p>
      <h2>2. Orders and the contract</h2><p>Placing an order constitutes an offer to purchase. The contract is concluded when Livada confirms the order by email. We reserve the right to cancel orders in the event of an obvious pricing or stock error, with a full refund of any amounts paid.</p>
      <h2>3. Prices and payment</h2><p>Prices are shown in lei (RON) and include VAT (21%). We accept payment by card (3‑D Secure), cash on delivery, bank transfer and interest-free instalments with partner cards. The VAT invoice is issued electronically on dispatch and, for companies, is submitted through the RO e-Factura national e-invoicing system.</p>
      <h2>4. Delivery</h2><p>We deliver within Romania by courier, to lockers or via collection from our showroom, as described on the <a href="/livrare">Delivery</a> page. Risk passes to the buyer when the product is handed over.</p>
      <h2>5. Right of withdrawal</h2><p>You have 30 days from delivery to withdraw from the contract, as described on the <a href="/retur">Returns</a> page and in accordance with Government Emergency Ordinance (OUG) 34/2014 on consumer rights.</p>
      <h2>6. Warranty</h2><p>As described on the <a href="/garantie">Warranty</a> page.</p>
      <h2>7. Pre-orders</h2><p>Products marked “Pre-order” are delivered in the order in which the orders were placed, after the launch date. You can cancel a pre-order free of charge at any time before dispatch.</p>
      <h2>8. Dispute resolution</h2><p>For complaints, you can write to us at ${c.email}. You can also contact ANPC, the Romanian National Authority for Consumer Protection (anpc.ro), use the alternative dispute resolution procedure (SAL) or the European online dispute resolution platform (SOL/ODR, ec.europa.eu/consumers/odr).</p>` }),
  });
}

export function privacyPage(ctx) {
  const c = co();
  return doc(ctx, {
    slug: 'confidentialitate', title: t('Politica de confidențialitate'), eyebrow: 'GDPR', lead: t('Cum colectăm, folosim și protejăm datele tale personale.'),
    body: pick({ ro: html`
      <h2>Operatorul</h2><p>${c.legal_name}, ${c.address}, ${c.email}.</p>
      <h2>Ce date prelucrăm și de ce</h2>
      <ul class="ticks"><li><b>Date de cont și comandă</b> (nume, email, telefon, adrese) — pentru executarea contractului (art. 6(1)(b) GDPR).</li>
      <li><b>Date de facturare</b> — obligație legală fiscală (art. 6(1)(c)), păstrate 10 ani.</li>
      <li><b>Email marketing</b> — doar cu acordul tău (art. 6(1)(a)), retractabil oricând.</li>
      <li><b>Date tehnice</b> (IP, browser) — interes legitim pentru securitate și prevenirea fraudei.</li></ul>
      <h2>Cui transmitem datele</h2><p>Curierilor (FAN Courier, Sameday, Cargus, DPD, GLS, Poșta Română, Packeta) strict pentru livrare, procesatorului de plăți, contabilității și autorităților, când legea o cere. Nu vindem datele tale.</p>
      <h2>Drepturile tale</h2><p>Acces, rectificare, ștergere, restricționare, portabilitate și opoziție. Le poți exercita la ${c.email} sau direct din contul tău (export JSON). Ai dreptul de a depune plângere la ANSPDCP (dataprotection.ro).</p>
      <h2>Securitate</h2><p>Parolele sunt stocate cu hash scrypt, conexiunea este criptată TLS, iar datele cardului nu ajung niciodată pe serverele noastre.</p>`,
    en: html`
      <h2>The data controller</h2><p>${c.legal_name}, ${c.address}, ${c.email}.</p>
      <h2>What data we process and why</h2>
      <ul class="ticks"><li><b>Account and order data</b> (name, email, phone, addresses) — to perform the contract (Art. 6(1)(b) GDPR).</li>
      <li><b>Billing data</b> — a legal tax obligation (Art. 6(1)(c)), kept for 10 years.</li>
      <li><b>Email marketing</b> — only with your consent (Art. 6(1)(a)), which you can withdraw at any time.</li>
      <li><b>Technical data</b> (IP address, browser) — our legitimate interest in security and fraud prevention.</li></ul>
      <h2>Who we share your data with</h2><p>Couriers (FAN Courier, Sameday, Cargus, DPD, GLS, Poșta Română, Packeta) strictly for delivery, the payment processor, our accountants and the authorities, where required by law. We do not sell your data.</p>
      <h2>Your rights</h2><p>Access, rectification, erasure, restriction of processing, data portability and objection. You can exercise them by writing to ${c.email} or directly from your account (JSON export). You have the right to lodge a complaint with ANSPDCP, the Romanian data protection authority (dataprotection.ro).</p>
      <h2>Security</h2><p>Passwords are stored as scrypt hashes, the connection is TLS-encrypted, and card details never reach our servers.</p>` }),
  });
}

export function cookiesPage(ctx) {
  return doc(ctx, {
    slug: 'cookies', title: t('Politica de cookie-uri'), eyebrow: t('Cookie-uri'), lead: t('Folosim puține cookie-uri, iar cele neesențiale doar cu acordul tău.'),
    body: html`<table class="table"><thead><tr><th>${t('Cookie')}</th><th>${t('Scop')}</th><th>${t('Durată')}</th></tr></thead><tbody>
      <tr><td>lv_vid</td><td>${t('Identificator anonim pentru protecția formularelor (CSRF)')}</td><td>${t('1 an')}</td></tr>
      <tr><td>lv_cart</td><td>${t('Păstrează coșul de cumpărături')}</td><td>${t('60 de zile')}</td></tr>
      <tr><td>lv_sid</td><td>${t('Sesiunea de autentificare')}</td><td>${t('până la 30 de zile')}</td></tr>
      <tr><td>lv_lang</td><td>${t('Limba aleasă pentru magazin (română sau engleză)')}</td><td>${t('1 an')}</td></tr>
      <tr><td>lv_flash</td><td>${t('Mesaje de confirmare între pagini')}</td><td>${t('1 minut')}</td></tr>
      <tr><td>lv_consent (localStorage)</td><td>${t('Alegerea ta privind cookie-urile')}</td><td>${t('1 an')}</td></tr></tbody></table>
      <p>${t('Nu folosim în prezent cookie-uri de publicitate. Dacă vom adăuga cookie-uri de analiză, ele se vor activa doar după ce alegi „Accept toate”.')}</p>
      <button class="btn btn--ghost btn--sm" type="button" data-cookie-reset>${t('Schimbă preferințele')}</button>`,
  });
}

const FAQ = [
  ['Produsele sunt originale și noi?', 'Da. Vindem exclusiv produse Apple noi, sigilate, din distribuția oficială pentru Uniunea Europeană, cu factură și garanție de 24 de luni.'],
  ['Cât durează livrarea?', 'Comenzile plasate în zilele lucrătoare până la 16:00 pleacă în aceeași zi și ajung de regulă a doua zi. În București și Ilfov poți primi comanda chiar în aceeași seară.'],
  ['Cum aleg un locker?', 'La finalizarea comenzii alege „Locker”, caută localitatea sau strada și alege punctul pe hartă — easybox, FANbox, Cargus Ship & Go, DPD sau GLS. Când coletul ajunge, primești codul de deschidere prin SMS.'],
  ['Pot plăti în rate?', 'Da, 3, 6 sau 12 rate fără dobândă cu cardurile de credit partenere, pentru comenzi de minimum 500 lei.'],
  ['Emiteți factură pe firmă?', 'Sigur. La checkout alege „Persoană juridică” și completează CUI-ul — validăm cifra de control automat. Factura se transmite și în RO e-Factura.'],
  ['Pot returna un produs desigilat?', 'Da, în 30 de zile, dacă este complet și fără urme de utilizare. Excepție fac căștile in-ear cu sigiliul de igienă desfăcut.'],
  ['Ce se întâmplă cu o precomandă?', 'Precomenzile se livrează în ordinea plasării, imediat după lansare. Le poți anula gratuit oricând înainte de expediere.'],
  ['Am nevoie de cont ca să comand?', 'Nu, poți comanda ca vizitator. Cu un cont confirmat îți vezi istoricul, adresele salvate și primești 50 lei la prima comandă.'],
];

// aceleași întrebări, în aceeași ordine, în engleză
const FAQ_EN = [
  ['Are the products new and genuine?', 'Yes. We sell only new, sealed Apple products from official European Union distribution, with a VAT invoice and a 24-month warranty.'],
  ['How long does delivery take?', 'Orders placed on working days by 16:00 ship the same day and usually arrive the next day. In Bucharest and Ilfov you can even get your order the same evening.'],
  ['How do I choose a locker?', 'At checkout, choose “Locker”, search for your town or street and pick the point on the map — easybox, FANbox, Cargus Ship & Go, DPD or GLS. When the parcel arrives, you’ll get the opening code by text message.'],
  ['Can I pay in instalments?', 'Yes, 3, 6 or 12 interest-free instalments with partner credit cards, for orders of at least 500 lei.'],
  ['Can you invoice my company?', 'Of course. At checkout choose “Company” and enter your tax ID (CUI) — we validate the check digit automatically. The invoice is also submitted through RO e-Factura.'],
  ['Can I return an unsealed product?', 'Yes, within 30 days, as long as it’s complete and shows no signs of use. The only exception is in-ear headphones whose hygiene seal has been broken.'],
  ['What happens with a pre-order?', 'Pre-orders are delivered in the order they were placed, right after launch. You can cancel them free of charge at any time before dispatch.'],
  ['Do I need an account to order?', 'No, you can check out as a guest. With a verified account you can see your order history and saved addresses, and you get 50 lei off your first order.'],
];

export function helpPage(ctx) {
  return doc(ctx, {
    slug: 'ajutor', title: t('Întrebări frecvente'), eyebrow: t('Ajutor'), lead: t('Răspunsuri rapide. Pentru restul, suntem la un telefon distanță.'),
    body: html`<div class="faq">${pick({ ro: FAQ, en: FAQ_EN }).map(([q, a]) => html`<details class="faq__item"><summary>${q}${icon('plus', { size: 18 })}</summary><p>${a}</p></details>`)}</div>
      <p><a class="btn btn--dark" href="/contact">${t('Contactează-ne')}</a></p>`,
  });
}

export const STATIC_PAGES = {
  despre: aboutPage, livrare: deliveryPage, retur: returnsPage, garantie: warrantyPage, termeni: termsPage,
  confidentialitate: privacyPage, cookies: cookiesPage, ajutor: helpPage,
};
export { raw };
