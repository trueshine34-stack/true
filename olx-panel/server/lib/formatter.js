/**
 * Formatowanie ogloszenia: czyszczenie tresci zrodlowej, szablon opisu, tytul, hashtagi.
 * Czyste funkcje - bez I/O.
 */

const PHONE_RE = /(?:\+?\d{1,3}[\s-]?)?(?:\(?\d{2,3}\)?[\s.-]?)?\d{3}[\s.-]?\d{2,3}[\s.-]?\d{2,3}/g;
const EMAIL_RE = /[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/gi;
const URL_RE = /\b(?:https?:\/\/|www\.)[^\s<>()]+/gi;
const SOCIAL_RE = /\b(?:whatsapp|viber|telegram|messenger|instagram|facebook|fb\.com|tiktok|@[a-z0-9._-]{3,})\b/gi;
const MARKET_RE = /\b(?:allegro(?:lokalnie)?|olx|vinted|ebay|amazon|aliexpress|otomoto|sprzedajemy\.pl|shopee|temu)\b/gi;
const CALL_RE = /\b(?:zadzwo[nń]|dzwo[nń]|kontakt telefoniczny|tel\.?|telefon|nr tel|numer telefonu|napisz sms|sms)\s*[:\-–]?\s*/gi;
const PROMO_RE = /\b(?:promocja|wyprzeda[zż]|okazja|hit cenowy|super cena|tanio|mega tanio|najta[nń]sze?|polecam|okazyjnie|hit!*)\b/gi;
const EMOJI_RE = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2190}-\u{21FF}]/gu;

const STOPWORDS = new Set([
  'i', 'oraz', 'do', 'na', 'w', 'we', 'z', 'ze', 'dla', 'od', 'po', 'przy', 'pod', 'nad',
  'the', 'and', 'for', 'new', 'nowy', 'nowa', 'nowe', 'szt', 'cm', 'mm', 'kpl',
]);

/** Sprawdza, czy fragment zawiera cudze dane kontaktowe lub odnosnik. */
function hasContact(text) {
  const value = String(text);
  const phones = value.match(new RegExp(PHONE_RE.source, 'g')) || [];
  if (phones.some((m) => m.replace(/\D/g, '').length >= 9)) return true;
  return [URL_RE, EMAIL_RE, SOCIAL_RE, MARKET_RE, CALL_RE]
    .some((re) => new RegExp(re.source, re.flags.replace('g', '')).test(value));
}

/** Wycina same tokeny kontaktowe i sprzata to, co zostalo po nich w zdaniu. */
function stripContactTokens(text) {
  return String(text)
    .replace(URL_RE, ' ')
    .replace(EMAIL_RE, ' ')
    .replace(SOCIAL_RE, ' ')
    .replace(MARKET_RE, ' ')
    .replace(CALL_RE, ' ')
    .replace(PHONE_RE, (match) => (match.replace(/\D/g, '').length >= 9 ? ' ' : match))
    // po wycieciu zostaja wiszace spojniki i przyimki: "napisz na lub" -> ""
    .replace(/\b(?:na|w|we|do|pod|przez|lub|albo|oraz|i|z|ze|pisz|napisz|dzwo[nń])(?:\s+(?:na|w|we|do|pod|przez|lub|albo|oraz|i|z|ze))+\b/gi, ' ')
    .replace(/\s+([,.;:!?])/g, '$1')
    .replace(/([,;:])\s*(?=[,.;:])/g, '')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();
}

/**
 * Usuwa cudze dane kontaktowe, linki i nazwy konkurencyjnych serwisow.
 * Najpierw probuje wyrzucic cale zdanie z kontaktem - to daje czystszy tekst
 * niz wycinanie pojedynczych slow. Gdy tak zniknelaby wiekszosc tresci,
 * wraca do wycinania tokenow.
 */
export function sanitizeText(text) {
  if (!text) return '';
  return String(text)
    .split('\n')
    .map((line) => {
      if (!line.trim() || !hasContact(line)) return line;
      const sentences = line.split(/(?<=[.!?…])\s+/);
      if (sentences.length > 1) {
        const kept = sentences.filter((s) => !hasContact(s)).join(' ').trim();
        if (kept.length >= line.trim().length * 0.4) return kept;
      }
      return stripContactTokens(line);
    })
    .join('\n');
}

/** Normalizuje bialy znak, kasuje spam wykrzyknikow i krzyczace CAPS-y. */
export function normalizeText(text) {
  if (!text) return '';
  const lines = String(text)
    .replace(/\r\n?/g, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .split('\n')
    .map((line) => line
      .replace(/[!]{2,}/g, '!')
      .replace(/[?]{2,}/g, '?')
      .replace(/[.]{4,}/g, '...')
      .replace(/[ \t]{2,}/g, ' ')
      .trim())
    .map((line) => (isShouting(line) ? sentenceCase(line) : line));

  const out = [];
  for (const line of lines) {
    if (line === '' && out[out.length - 1] === '') continue; // max jedna pusta linia
    out.push(line);
  }
  return out.join('\n').trim();
}

function isShouting(line) {
  const letters = line.replace(/[^\p{L}]/gu, '');
  if (letters.length < 8) return false;
  const upper = letters.replace(/[^\p{Lu}]/gu, '').length;
  return upper / letters.length > 0.7;
}

function sentenceCase(line) {
  const lower = line.toLocaleLowerCase('pl-PL');
  return lower.charAt(0).toLocaleUpperCase('pl-PL') + lower.slice(1);
}

/** Tytul: bez spamu, bez kontaktow, w limicie znakow OLX (domyslnie 70). */
export function formatTitle(rawTitle, opts = {}) {
  const { maxLength = 70, titleCase = 'smart', sanitize = true, prefix = '', suffix = '' } = opts;
  let title = String(rawTitle || '').replace(/\s+/g, ' ').trim();
  if (sanitize) title = sanitizeText(title);
  title = title
    .replace(EMOJI_RE, '')
    .replace(PROMO_RE, '')
    .replace(/[!]{1,}/g, '')
    .replace(/[*_#|]+/g, ' ')
    .replace(/\s*[-–—]\s*$/, '')
    .replace(/\s{2,}/g, ' ')
    .trim();

  if (titleCase === 'smart' && isShouting(title)) title = smartCase(title);
  else if (titleCase === 'upperFirst') title = sentenceCase(title);

  const full = [prefix, title, suffix].filter(Boolean).join(' ').replace(/\s{2,}/g, ' ').trim();
  return truncateWords(full, maxLength);
}

function smartCase(title) {
  return title
    .split(' ')
    .map((word) => {
      if (/\d/.test(word)) return word;                       // modele, rozmiary
      if (word.length <= 3 && word === word.toUpperCase()) return word; // skroty: USB, LED
      return word.charAt(0).toLocaleUpperCase('pl-PL') + word.slice(1).toLocaleLowerCase('pl-PL');
    })
    .join(' ');
}

export function truncateWords(text, maxLength) {
  const value = String(text || '').trim();
  if (value.length <= maxLength) return value;
  const cut = value.slice(0, maxLength);
  const lastSpace = cut.lastIndexOf(' ');
  return (lastSpace > maxLength * 0.6 ? cut.slice(0, lastSpace) : cut).replace(/[\s,.\-–—]+$/, '');
}

/** Podstawia {{zmienne}} w szablonie. Brakujace zmienne znikaja bez sladu. */
export function renderTemplate(template, vars) {
  return String(template || '').replace(/\{\{\s*([\w.]+)\s*\}\}/g, (_, key) => {
    const value = key.split('.').reduce((acc, part) => (acc == null ? acc : acc[part]), vars);
    return value === undefined || value === null ? '' : String(value);
  });
}

/** Lista cech "✔ Klucz: wartosc" z atrybutow ogloszenia. */
export function buildHighlights(attributes = {}, limit = 8) {
  return Object.entries(attributes)
    .filter(([, v]) => v !== null && v !== undefined && String(v).trim() !== '')
    .slice(0, limit)
    .map(([k, v]) => `✔ ${sentenceCase(String(k).replace(/[_-]+/g, ' '))}: ${String(v).trim()}`)
    .join('\n');
}

export function buildHashtags(listing, limit = 6) {
  const words = `${listing.title || ''} ${listing.brand || ''} ${listing.categoryName || ''}`
    .toLocaleLowerCase('pl-PL')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .split(/\s+/)
    .filter((w) => w.length >= 3 && !STOPWORDS.has(w) && !/^\d+$/.test(w));
  const unique = [...new Set(words)].slice(0, limit);
  return unique.map((w) => `#${w}`).join(' ');
}

/**
 * Sklada gotowe ogloszenie: { title, description }.
 * @param {object} listing znormalizowane ogloszenie
 * @param {object} template settings.template
 * @param {object} priced wynik priceListing()
 */
export function formatListing(listing, template = {}, priced = null) {
  const t = {
    title: '{{title}}',
    body: '{{description}}',
    vars: {},
    maxTitleLength: 70,
    maxBodyLength: 9000,
    titleCase: 'smart',
    sanitize: true,
    hashtags: true,
    ...template,
  };

  const cleanTitleSource = t.sanitize ? sanitizeText(listing.title) : listing.title;
  const baseTitle = formatTitle(cleanTitleSource, {
    maxLength: t.maxTitleLength,
    titleCase: t.titleCase,
    sanitize: t.sanitize,
  });

  let description = normalizeText(listing.description || '');
  if (t.sanitize) description = normalizeText(sanitizeText(description));

  const vars = {
    ...t.vars,
    title: baseTitle,
    description,
    brand: listing.brand || '',
    price: priced ? `${priced.price.toFixed(2)} ${priced.currency}` : '',
    highlights: buildHighlights(listing.attributes),
    hashtags: t.hashtags ? buildHashtags({ ...listing, title: baseTitle }) : '',
  };

  const title = formatTitle(renderTemplate(t.title, vars), {
    maxLength: t.maxTitleLength,
    titleCase: 'none',
    sanitize: false,
  });

  let body = normalizeText(renderTemplate(t.body, vars));
  if (t.hashtags && vars.hashtags && !body.includes(vars.hashtags)) {
    body = `${body}\n\n${vars.hashtags}`;
  }
  if (body.length > t.maxBodyLength) body = truncateWords(body, t.maxBodyLength);

  return { title, description: body };
}
