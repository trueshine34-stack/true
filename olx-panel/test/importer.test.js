import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCsv, mapRow, importFromCsv, importFromJson, parseHtml, toNumber, normalizeListing } from '../shared/importer.js';

test('разбор чисел в польском формате', () => {
  assert.equal(toNumber('1 299,99 zł'), 1299.99);
  assert.equal(toNumber('149.50'), 149.5);
  assert.equal(toNumber('bez ceny'), 0);
  assert.equal(toNumber(null), 0);
});

test('CSV с автоопределением разделителя и кавычками', () => {
  const rows = parseCsv('title;price;description\n"Kurtka; zimowa";149,99;"Opis ""premium"""');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].title, 'Kurtka; zimowa');
  assert.equal(rows[0].description, 'Opis "premium"');
});

test('маппинг колонок на разных языках и сбор лишних в атрибуты', () => {
  const listing = mapRow({ tytuł: 'Kurtka', cena: '99,90', zdjęcia: 'https://a/1.jpg|https://a/2.jpg', kolor: 'czarny' });
  assert.equal(listing.title, 'Kurtka');
  assert.equal(listing.basePrice, 99.9);
  assert.deepEqual(listing.images, ['https://a/1.jpg', 'https://a/2.jpg']);
  assert.equal(listing.attributes.kolor, 'czarny');
});

test('импорт CSV присваивает источник и статус черновика', () => {
  const items = importFromCsv('title,price\nLampa,49', { sourceId: 'src_1' });
  assert.equal(items.length, 1);
  assert.equal(items[0].sourceId, 'src_1');
  assert.equal(items[0].status, 'draft');
  assert.ok(items[0].id.startsWith('ad_'));
});

test('импорт JSON массива объектов', () => {
  const items = importFromJson(JSON.stringify([{ Name: 'Stół', Price: 399, Images: ['https://a/1.jpg'] }]));
  assert.equal(items[0].title, 'Stół');
  assert.equal(items[0].basePrice, 399);
  assert.deepEqual(items[0].images, ['https://a/1.jpg']);
});

test('нормализация отбрасывает не-http картинки и режет до 12 штук', () => {
  const listing = normalizeListing({
    title: 'X',
    images: ['data:image/png;base64,AAA', ...Array.from({ length: 15 }, (_, i) => `https://a/${i}.jpg`)],
  });
  assert.equal(listing.images.length, 12);
  assert.ok(listing.images.every((u) => u.startsWith('https://')));
});

test('парсинг страницы через JSON-LD', () => {
  const html = `<html><head>
    <script type="application/ld+json">{"@type":"Product","name":"Rower górski","description":"Stan idealny",
    "image":["https://x/1.jpg"],"brand":{"name":"Kross"},
    "offers":{"@type":"Offer","price":"1299.00","priceCurrency":"PLN","itemCondition":"https://schema.org/NewCondition"}}</script>
    </head><body></body></html>`;
  const listing = parseHtml(html, 'https://example.com/rower');
  assert.equal(listing.title, 'Rower górski');
  assert.equal(listing.basePrice, 1299);
  assert.equal(listing.currency, 'PLN');
  assert.equal(listing.brand, 'Kross');
  assert.equal(listing.condition, 'Nowy');
  assert.equal(listing.sourceUrl, 'https://example.com/rower');
});

test('парсинг через OpenGraph, когда микроразметки нет', () => {
  const html = `<meta property="og:title" content="Stolik kawowy">
    <meta property="og:description" content="Drewno dębowe">
    <meta property="og:image" content="https://x/2.jpg">
    <meta property="product:price:amount" content="249,00">`;
  const listing = parseHtml(html, 'https://shop.example/stolik');
  assert.equal(listing.title, 'Stolik kawowy');
  assert.equal(listing.basePrice, 249);
  assert.deepEqual(listing.images, ['https://x/2.jpg']);
});

test('страница без данных о товаре не даёт объявления', () => {
  assert.equal(parseHtml('<html><body>nic tu nie ma</body></html>', 'https://x'), null);
});
