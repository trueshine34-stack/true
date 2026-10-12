import { COUNTIES } from '../db/defaults.js';
import { t } from './i18n.js';

export class ValidationError extends Error {
  constructor(errors) {
    super(Object.values(errors)[0] || t('Date invalide.'));
    this.status = 422;
    this.errors = errors;
  }
}

export const clean = (v, max = 200) => String(v ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
export const cleanText = (v, max = 2000) => String(v ?? '').replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, '').trim().slice(0, max);

export const isEmail = (s) => /^[^\s@"<>()[\]\\,;:]+@[^\s@"<>()[\]\\,;:]+\.[a-z]{2,}$/i.test(s) && s.length <= 190;

/** telefon RO: 07xx xxx xxx / +40 7xx … / 02x, 03x fix */
export function normalizePhone(s) {
  let d = String(s || '').replace(/[^\d+]/g, '');
  if (d.startsWith('+40')) d = `0${d.slice(3)}`;
  else if (d.startsWith('0040')) d = `0${d.slice(4)}`;
  else if (d.startsWith('40') && d.length === 11) d = `0${d.slice(2)}`;
  return /^0[237]\d{8}$/.test(d) ? d : null;
}

export const isPostcode = (s) => /^\d{6}$/.test(String(s || '').trim());

/** validarea cifrei de control a CUI/CIF (cu sau fără prefixul RO) */
export function isCui(s) {
  const v = String(s || '').toUpperCase().replace(/^RO/, '').replace(/\s/g, '');
  if (!/^\d{2,10}$/.test(v)) return false;
  const key = '753217532';
  const digits = v.slice(0, -1).padStart(9, '0');
  let sum = 0;
  for (let i = 0; i < 9; i++) sum += Number(digits[i]) * Number(key[i]);
  let c = (sum * 10) % 11;
  if (c === 10) c = 0;
  return c === Number(v.slice(-1));
}

export function passwordProblem(pw) {
  if (typeof pw !== 'string' || pw.length < 8) return t('Parola trebuie să aibă cel puțin 8 caractere.');
  if (pw.length > 200) return t('Parola este prea lungă.');
  if (!/[a-zA-ZăâîșțĂÂÎȘȚ]/.test(pw) || !/\d/.test(pw)) return t('Parola trebuie să conțină cel puțin o literă și o cifră.');
  return null;
}

export const isCounty = (s) => COUNTIES.includes(s);

/** validează o adresă de livrare/facturare; returnează obiectul curățat */
export function address(body, prefix = '', errors = {}) {
  const f = (k, max) => clean(body[`${prefix}${k}`], max);
  const a = {
    first_name: f('first_name', 60), last_name: f('last_name', 60), county: f('county', 40), city: f('city', 80),
    street: f('street', 160), postcode: f('postcode', 6),
  };
  if (!a.first_name) errors[`${prefix}first_name`] = t('Completează prenumele.');
  if (!a.last_name) errors[`${prefix}last_name`] = t('Completează numele.');
  if (!isCounty(a.county)) errors[`${prefix}county`] = t('Alege județul.');
  if (a.city.length < 2) errors[`${prefix}city`] = t('Completează localitatea.');
  if (a.street.length < 4) errors[`${prefix}street`] = t('Completează strada și numărul.');
  if (a.postcode && !isPostcode(a.postcode)) errors[`${prefix}postcode`] = t('Codul poștal are 6 cifre.');
  return a;
}
