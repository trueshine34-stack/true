import test from 'node:test';
import assert from 'node:assert/strict';
import {
  sanitizeText, normalizeText, formatTitle, renderTemplate,
  buildHighlights, buildHashtags, formatListing, truncateWords,
} from '../shared/formatter.js';

test('очистка убирает чужой телефон, почту, ссылку и упоминание площадки', () => {
  const out = sanitizeText('Zadzwoń 601 234 567 lub kontakt@sklep.pl, więcej na www.allegro.pl/oferta');
  assert.ok(!/601/.test(out));
  assert.ok(!/kontakt@/.test(out));
  assert.ok(!/allegro/i.test(out));
  assert.ok(!/www\./.test(out));
});

test('короткие числа не считаются телефоном', () => {
  assert.match(sanitizeText('Rozmiar 42, wysokość 180 cm'), /42/);
});

test('нормализация схлопывает пустые строки и капс', () => {
  const out = normalizeText('SUPER OKAZJA DLA KAZDEGO!!!\n\n\n\nOpis   towaru');
  assert.ok(!out.includes('\n\n\n'));
  assert.ok(!/!!!/.test(out));
  assert.equal(out.split('\n')[0], 'Super okazja dla kazdego!');
});

test('заголовок обрезается по границе слова и без спам-слов', () => {
  const title = formatTitle('PROMOCJA!!! Kurtka zimowa męska bardzo ciepła model 2024 rozmiar XL super jakość dla każdego', { maxLength: 70 });
  assert.ok(title.length <= 70);
  assert.ok(!/PROMOCJA/i.test(title));
  assert.ok(!title.endsWith(' '));
});

test('короткий заголовок не режется', () => {
  assert.equal(formatTitle('Kurtka zimowa XL', { maxLength: 70, titleCase: 'none' }), 'Kurtka zimowa XL');
});

test('truncateWords не рвёт слово посередине', () => {
  assert.equal(truncateWords('abcdef ghijkl mnopqr', 15), 'abcdef ghijkl');
});

test('подстановка переменных, включая вложенные', () => {
  assert.equal(renderTemplate('{{a}} — {{b.c}}', { a: 'X', b: { c: 'Y' } }), 'X — Y');
  assert.equal(renderTemplate('[{{missing}}]', {}), '[]');
});

test('список характеристик и хештеги', () => {
  assert.equal(buildHighlights({ kolor: 'czarny', rozmiar: 'XL' }), '✔ Kolor: czarny\n✔ Rozmiar: XL');
  const tags = buildHashtags({ title: 'Kurtka zimowa dla mężczyzn', brand: 'Nike' });
  assert.ok(tags.includes('#kurtka'));
  assert.ok(!tags.includes('#dla')); // стоп-слово
});

test('полное оформление объявления', () => {
  const listing = {
    title: 'KURTKA ZIMOWA!!! tel 601234567',
    description: 'Ciepła kurtka.\n\n\nNapisz na whatsapp 601 234 567 albo www.sklep.pl',
    attributes: { kolor: 'czarny' },
  };
  const template = {
    title: '{{title}}',
    body: '{{description}}\n\n{{highlights}}\n\nCena: {{price}}\n{{footer}}',
    vars: { footer: 'Faktura na życzenie.' },
    sanitize: true,
    hashtags: true,
    maxTitleLength: 70,
    maxBodyLength: 9000,
    titleCase: 'smart',
  };
  const result = formatListing(listing, template, { price: 149.99, currency: 'PLN' });

  assert.ok(!/601/.test(result.description));
  assert.ok(!/whatsapp/i.test(result.description));
  assert.ok(result.description.includes('✔ Kolor: czarny'));
  assert.ok(result.description.includes('149.99 PLN'));
  assert.ok(result.description.includes('Faktura na życzenie.'));
  assert.ok(result.description.includes('#kurtka'));
  assert.ok(result.title.length <= 70 && result.title.length > 0);
});

test('лимит длины описания соблюдается', () => {
  const long = 'a'.repeat(500);
  const result = formatListing({ title: 'Test produkt', description: long }, { body: '{{description}}', maxBodyLength: 100, hashtags: false });
  assert.ok(result.description.length <= 100);
});

test('предложение с чужим контактом удаляется целиком, без обрубков', () => {
  const out = sanitizeText('Ciepła kurtka puchowa. Napisz na whatsapp 601 234 567 lub www.allegro.pl/sklep. Materiał wodoodporny.');
  assert.equal(out, 'Ciepła kurtka puchowa. Materiał wodoodporny.');
});

test('если контакт есть в единственном предложении — чистятся токены, текст остаётся', () => {
  const out = sanitizeText('Sprzedam rower kontakt 601234567');
  assert.ok(out.includes('Sprzedam rower'));
  assert.ok(!/601234567/.test(out));
});

test('текст без контактов не меняется', () => {
  const text = 'Kurtka zimowa, rozmiar XL. Materiał: poliester 100%.';
  assert.equal(sanitizeText(text), text);
});
