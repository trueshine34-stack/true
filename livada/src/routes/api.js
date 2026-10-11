import { HttpError } from '../lib/http.js';
import { limit } from './helpers.js';
import { clean } from '../lib/validate.js';
import * as catalog from '../services/catalog.js';
import * as cartSvc from '../services/cart.js';
import { lockersPayload, searchPlaces, methodByCode, estimate } from '../services/shipping.js';
import { toggleFavorite, favoriteIds } from '../services/users.js';
import { summaryRows, promoForm } from '../views/store/cart.js';
import { lei } from '../lib/format.js';

export default function apiRoutes(r) {
  r.get('/api/search', (ctx) => {
    limit(ctx, 'search', 120, 60_000);
    const q = clean(ctx.query.get('q'), 80);
    ctx.json({ q, results: q.length >= 2 ? catalog.suggestions(q, 6).map((s) => ({ ...s, price: lei(s.price_from) })) : [] });
  });

  r.get('/api/lockers', (ctx) => {
    const p = lockersPayload();
    const headers = { 'Cache-Control': 'public, max-age=3600', ETag: p.etag, Vary: 'Accept-Encoding' };
    if (ctx.req.headers['if-none-match'] === p.etag) return ctx.send('application/json; charset=utf-8', '', 304, headers);
    const ae = ctx.req.headers['accept-encoding'] || '';
    if (/\bbr\b/.test(ae)) return ctx.send('application/json; charset=utf-8', p.br, 200, { ...headers, 'Content-Encoding': 'br' });
    if (/\bgzip\b/.test(ae)) return ctx.send('application/json; charset=utf-8', p.gz, 200, { ...headers, 'Content-Encoding': 'gzip' });
    return ctx.send('application/json; charset=utf-8', p.raw, 200, headers);
  });

  /** localitățile pentru căutarea de pe harta lockerelor */
  r.get('/api/places', (ctx) => {
    limit(ctx, 'places', 240, 60_000);
    const q = clean(ctx.query.get('q'), 60);
    ctx.json({ q, places: q.length >= 2 ? searchPlaces(q) : [] }, 200, { 'Cache-Control': 'public, max-age=86400' });
  });

  /** recalculează totalurile la checkout când se schimbă livrarea / plata */
  r.post('/api/checkout/quote', (ctx) => {
    const cart = cartSvc.loadCart(ctx.cartId);
    const deliveryCode = clean(ctx.body.delivery_method, 40) || undefined;
    const payment = clean(ctx.body.payment_method, 20) || undefined;
    // la vizitatori, emailul tastat în checkout permite verificarea codurilor „doar la prima comandă”
    const email = clean(ctx.body.email, 190);
    const customer = ctx.user ? { userId: ctx.user.id, email: ctx.user.email } : (email.includes('@') ? { email } : null);
    const t = cartSvc.priceCart(cart, { deliveryCode, payment, customer });
    const m = deliveryCode ? methodByCode(deliveryCode) : null;
    ctx.json({
      rows: summaryRows(t).s, promo: cart.promo_code ? promoForm(ctx, cart, t).s : undefined, total: t.total, totalLabel: lei(t.total), codAllowed: t.codAllowed,
      installments: Object.fromEntries([3, 6, 12].map((n) => [n, lei(Math.ceil(t.total / n))])),
      eta: m ? `${m.type === 'pickup' ? 'Ridicare' : 'Livrare estimată'}: ${estimate(m)}` : '',
      installmentsAllowed: t.total >= 50000,
    });
  });

  r.get('/api/favorites', (ctx) => ctx.json({ ids: ctx.user ? favoriteIds(ctx.user.id) : [] }));
  r.post('/api/favorites/:id', (ctx) => {
    if (!ctx.user) throw new HttpError(401, 'Autentifică-te ca să salvezi produse la favorite.');
    const on = toggleFavorite(ctx.user.id, Number(ctx.params.id));
    ctx.json({ ok: true, on, message: on ? 'Adăugat la favorite.' : 'Eliminat din favorite.' });
  });
}
