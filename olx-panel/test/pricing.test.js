import test from 'node:test';
import assert from 'node:assert/strict';
import { applyRounding, computePrice, resolveRule } from '../server/lib/pricing.js';

test('округление до ...,99 всегда идёт вверх', () => {
  assert.equal(applyRounding(100, 'end99'), 100.99);
  assert.equal(applyRounding(100.5, 'end99'), 100.99);
  assert.equal(applyRounding(100.99, 'end99'), 100.99);
  assert.equal(applyRounding(101.01, 'end99'), 101.99);
});

test('остальные режимы округления', () => {
  assert.equal(applyRounding(101.2, 'up1'), 102);
  assert.equal(applyRounding(101.2, 'up5'), 105);
  assert.equal(applyRounding(101.2, 'up10'), 110);
  assert.equal(applyRounding(101.2, 'up50'), 150);
  assert.equal(applyRounding(101.2, 'up100'), 200);
  assert.equal(applyRounding(101.234, 'none'), 101.23);
});

test('процентная наценка с фиксированной надбавкой', () => {
  const r = computePrice(100, { percent: 25, fixed: 10, rounding: 'none' });
  assert.equal(r.markup, 35);
  assert.equal(r.price, 135);
  assert.equal(r.margin, 35);
  assert.equal(r.marginPercent, 35);
});

test('минимальная маржа поднимает наценку на дешёвом товаре', () => {
  const r = computePrice(10, { percent: 20, minMargin: 15, rounding: 'none' });
  assert.equal(r.markup, 15);
  assert.equal(r.price, 25);
});

test('максимальная маржа ограничивает наценку на дорогом товаре', () => {
  const r = computePrice(5000, { percent: 30, maxMargin: 400, rounding: 'none' });
  assert.equal(r.markup, 400);
  assert.equal(r.price, 5400);
});

test('комиссия площадки считается сверху и сохраняет чистую маржу', () => {
  const r = computePrice(100, { percent: 20, feePercent: 10, rounding: 'none' });
  // 120 / 0.9 = 133.33; комиссия 13.33; на руки остаётся 120 => маржа 20
  assert.equal(r.price, 133.33);
  assert.ok(Math.abs(r.margin - 20) < 0.02);
});

test('доставка включается в цену, но не в маржу', () => {
  const r = computePrice(100, { percent: 0, shipping: 15, rounding: 'none' });
  assert.equal(r.price, 115);
  assert.equal(r.margin, 0);
});

test('приоритет правил: объявление > источник > категория > базовое', () => {
  const settings = {
    pricing: { percent: 10, rounding: 'none', currency: 'PLN' },
    categoryRules: { '1234': { percent: 20 } },
    sourceRules: { src_a: { percent: 30 } },
  };
  assert.equal(resolveRule(settings, {}).percent, 10);
  assert.equal(resolveRule(settings, { categoryKey: '1234' }).percent, 20);
  assert.equal(resolveRule(settings, { categoryKey: '1234', sourceId: 'src_a' }).percent, 30);
  assert.equal(resolveRule(settings, { sourceId: 'src_a', override: { percent: 45 } }).percent, 45);
});

test('пустые поля в правиле не затирают базовое значение', () => {
  const settings = { pricing: { percent: 10, fixed: 5 }, sourceRules: { s: { percent: '', fixed: 7 } } };
  const rule = resolveRule(settings, { sourceId: 's' });
  assert.equal(rule.percent, 10);
  assert.equal(rule.fixed, 7);
});

test('нулевая закупка не ломает расчёт процента маржи', () => {
  const r = computePrice(0, { percent: 25, minMargin: 5, rounding: 'none' });
  assert.equal(r.price, 5);
  assert.equal(r.marginPercent, 0);
});
