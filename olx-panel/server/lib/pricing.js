/**
 * Silnik marzy. Czyste funkcje - bez I/O, latwe do testowania.
 *
 * Kolejnosc rozstrzygania reguly (od najsilniejszej):
 *   nadpisanie w ogloszeniu > regula zrodla > regula kategorii > regula globalna
 */

export const ROUNDING_MODES = ['none', 'end99', 'up1', 'up5', 'up10', 'up50', 'up100'];

const round2 = (n) => Math.round((n + Number.EPSILON) * 100) / 100;

export function applyRounding(value, mode) {
  if (!Number.isFinite(value)) return 0;
  switch (mode) {
    case 'end99': {
      // najblizsza w gore wartosc konczaca sie na ,99
      const whole = Math.floor(value);
      const candidate = whole + 0.99;
      return round2(candidate >= value ? candidate : whole + 1.99);
    }
    case 'up1': return Math.ceil(value);
    case 'up5': return Math.ceil(value / 5) * 5;
    case 'up10': return Math.ceil(value / 10) * 10;
    case 'up50': return Math.ceil(value / 50) * 50;
    case 'up100': return Math.ceil(value / 100) * 100;
    case 'none':
    default: return round2(value);
  }
}

export function resolveRule(settings, { sourceId, categoryKey, override } = {}) {
  const base = settings?.pricing || {};
  const byCategory = (categoryKey && settings?.categoryRules?.[categoryKey]) || {};
  const bySource = (sourceId && settings?.sourceRules?.[sourceId]) || {};
  const clean = (obj) => Object.fromEntries(
    Object.entries(obj || {}).filter(([, v]) => v !== null && v !== undefined && v !== ''),
  );
  return { ...base, ...clean(byCategory), ...clean(bySource), ...clean(override) };
}

/**
 * @param {number} basePrice cena zakupu / cena zrodlowa
 * @param {object} rule wynik resolveRule()
 * @returns {{base:number, markup:number, shipping:number, fee:number, price:number, raw:number, margin:number, marginPercent:number, currency:string, rule:object}}
 */
export function computePrice(basePrice, rule = {}) {
  const base = Math.max(0, Number(basePrice) || 0);
  const percent = Number(rule.percent) || 0;
  const fixed = Number(rule.fixed) || 0;
  const minMargin = Number(rule.minMargin) || 0;
  const maxMargin = Number(rule.maxMargin) || 0;
  const shipping = Number(rule.shipping) || 0;
  const feePercent = Math.min(Number(rule.feePercent) || 0, 90);

  let markup = base * (percent / 100) + fixed;
  if (markup < minMargin) markup = minMargin;
  if (maxMargin > 0 && markup > maxMargin) markup = maxMargin;

  const net = base + markup + shipping;
  // prowizja doliczana od gory, zeby marza netto sie nie rozjechala
  const raw = feePercent > 0 ? net / (1 - feePercent / 100) : net;
  const price = applyRounding(raw, rule.rounding || 'none');
  const fee = round2(price * (feePercent / 100));
  const margin = round2(price - base - shipping - fee);

  return {
    base: round2(base),
    markup: round2(markup),
    shipping: round2(shipping),
    fee,
    raw: round2(raw),
    price,
    margin,
    marginPercent: base > 0 ? round2((margin / base) * 100) : 0,
    currency: rule.currency || 'PLN',
    negotiable: rule.negotiable !== false,
    rule: {
      percent, fixed, minMargin, maxMargin, shipping, feePercent,
      rounding: rule.rounding || 'none',
    },
  };
}

/** Cena dla konkretnego ogloszenia z uwzglednieniem wszystkich warstw regul. */
export function priceListing(listing, settings) {
  const rule = resolveRule(settings, {
    sourceId: listing.sourceId,
    categoryKey: listing.categoryId || listing.categorySlug,
    override: listing.priceOverride,
  });
  if (Number.isFinite(Number(listing.manualPrice)) && Number(listing.manualPrice) > 0) {
    const manual = applyRounding(Number(listing.manualPrice), 'none');
    const base = Number(listing.basePrice) || 0;
    return {
      ...computePrice(base, rule),
      price: manual,
      margin: Math.round((manual - base) * 100) / 100,
      marginPercent: base > 0 ? Math.round(((manual - base) / base) * 10000) / 100 : 0,
      manual: true,
    };
  }
  return computePrice(listing.basePrice, rule);
}
