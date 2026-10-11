import { one, all } from '../db/index.js';

const VALID = "status NOT IN ('cancelled', 'returned')";

/** zilele din ultimele N (inclusiv azi), în ora României */
function daysBack(n) {
  const out = [];
  const fmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Bucharest' });
  for (let i = n - 1; i >= 0; i--) out.push(fmt.format(new Date(Date.now() - i * 86400000)));
  return out;
}

// datele sunt stocate în UTC; pentru agregare pe zile folosim ora României (+3 vara / +2 iarna, aproximăm cu +3h în sezonul curent)
const LOCAL_DAY = "date(created_at, '+3 hours')";

export function dashboard() {
  const sum = (where, ...p) => one(`SELECT COALESCE(SUM(total), 0) s, COUNT(*) n FROM orders WHERE ${VALID} AND ${where}`, ...p);
  const today = sum(`${LOCAL_DAY} = date('now', '+3 hours')`);
  const last30 = sum("created_at >= datetime('now', '-30 days')");
  const prev30 = sum("created_at >= datetime('now', '-60 days') AND created_at < datetime('now', '-30 days')");
  const yesterday = sum(`${LOCAL_DAY} = date('now', '+3 hours', '-1 day')`);
  const customers30 = one("SELECT COUNT(*) n FROM users WHERE role = 'customer' AND created_at >= datetime('now', '-30 days')").n;
  const customersPrev = one("SELECT COUNT(*) n FROM users WHERE role = 'customer' AND created_at >= datetime('now', '-60 days') AND created_at < datetime('now', '-30 days')").n;
  const toProcess = one("SELECT COUNT(*) n FROM orders WHERE status IN ('new', 'confirmed', 'processing')").n;

  const days = daysBack(30);
  const rows = Object.fromEntries(all(`SELECT ${LOCAL_DAY} d, SUM(total) s, COUNT(*) n FROM orders WHERE ${VALID} AND created_at >= datetime('now', '-31 days') GROUP BY d`).map((r) => [r.d, r]));
  const series = days.map((d) => ({ day: d, revenue: rows[d]?.s || 0, orders: rows[d]?.n || 0 }));

  const byStatus = all("SELECT status, COUNT(*) n FROM orders WHERE created_at >= datetime('now', '-30 days') GROUP BY status ORDER BY n DESC");
  const byDelivery = all(`SELECT CASE WHEN delivery_method LIKE 'locker_%' THEN 'locker' WHEN delivery_method LIKE 'courier_%' THEN 'courier' ELSE delivery_method END AS k,
    COUNT(*) n FROM orders WHERE ${VALID} AND created_at >= datetime('now', '-30 days') GROUP BY k ORDER BY n DESC`);
  const byPayment = all(`SELECT payment_method k, COUNT(*) n FROM orders WHERE ${VALID} AND created_at >= datetime('now', '-30 days') GROUP BY k ORDER BY n DESC`);
  const top = all(`SELECT i.name, i.slug, SUM(i.qty) qty, SUM(i.line_total) revenue, MAX(i.image) image FROM order_items i JOIN orders o ON o.id = i.order_id
    WHERE o.${VALID} AND o.created_at >= datetime('now', '-30 days') GROUP BY i.name ORDER BY revenue DESC LIMIT 6`);
  const lowStock = all(`SELECT v.id, v.sku, v.stock, v.options, p.name, p.id product_id, p.options p_options FROM variants v JOIN products p ON p.id = v.product_id
    WHERE p.active = 1 AND v.active = 1 AND p.preorder = 0 AND v.stock <= 2 ORDER BY v.stock, p.name LIMIT 8`);
  const recent = all('SELECT * FROM orders ORDER BY id DESC LIMIT 8');

  const pct = (a, b) => (b ? Math.round(((a - b) / b) * 1000) / 10 : null);
  return {
    today, yesterday, last30, prev30, toProcess, customers30,
    deltas: { revenue: pct(last30.s, prev30.s), orders: pct(last30.n, prev30.n), aov: pct(last30.n ? last30.s / last30.n : 0, prev30.n ? prev30.s / prev30.n : 0), customers: pct(customers30, customersPrev), today: pct(today.s, yesterday.s) },
    aov: last30.n ? Math.round(last30.s / last30.n) : 0,
    series, byStatus, byDelivery, byPayment, top, lowStock, recent,
  };
}

export function customerStats(userId) {
  return one(`SELECT COUNT(*) n, COALESCE(SUM(CASE WHEN ${VALID} THEN total ELSE 0 END), 0) spent FROM orders WHERE user_id = ?`, userId);
}
