import { feePerShare, netSellPrice } from './money';
import type { LoggedOrder } from '../native/polybot';

/**
 * A buy and the sell that closed it, as one thing.
 *
 * The order list showed four rows for what was really two trades, and left the
 * arithmetic — did that round make anything? — to be done in your head from two
 * prices that were not next to each other. Pairing them puts the result where
 * the question is asked, and halves the space it takes.
 *
 * Lots are matched oldest-first, which is how a position is actually unwound:
 * the shares that have been held longest are the ones a sell takes out.
 */
export type TradeRow = {
  key: string;
  outcome: string;
  /** Shares in this pairing. */
  shares: number;
  buyPrice: number | null;
  sellPrice: number | null;
  /**
   * closed — bought and sold; pending — a sell is resting over the buy;
   * open — bought, nothing offered; buying — the buy itself is still resting.
   */
  status: 'closed' | 'pending' | 'open' | 'buying';
  /** What the round made, or would make if the resting sell fills. */
  pnl: number | null;
  pct: number | null;
  /** The resting order this row can be cancelled by, when there is one. */
  orderId?: string | null;
  /** Either leg placed by a rule rather than by hand. */
  auto: boolean;
  /**
   * How the round was closed: by the rule, or by hand. Null while it is open.
   * Which leg did it is the thing worth seeing — a sale made by tapping is a
   * decision, one made by the ladder is the rule doing its job.
   */
  closedBy?: 'rule' | 'hand' | null;
  /** When the purchase happened. */
  at: number;
  /**
   * The row's most recent moment — the sale, where there is one.
   *
   * A round that closed is news now, not at the moment it was opened. Sorting
   * by the purchase buried freshly closed rounds under later, still-open buys,
   * which reads on screen as "sold but not shown".
   */
  movedAt: number;
};

/** What the shares cost, fee included — the same arithmetic the event score uses. */
export const buyCost = (shares: number, price: number): number =>
  shares * price + feePerShare(price) * shares;

/** What a sale pays, fee deducted. */
export const sellProceeds = (shares: number, price: number): number =>
  shares * netSellPrice(price);

type Lot = {
  shares: number;
  price: number;
  at: number;
  auto: boolean;
  id: number;
};

/**
 * Turn a window's orders into trades.
 *
 * Three passes over one queue of lots, in the order money actually moves:
 * fills close lots, then a resting sell claims what is left over, then whatever
 * still has no offer against it is simply open. A buy that has not filled at
 * all is not a lot yet — it is an order, and says so.
 */
export function pairOrders(orders: LoggedOrder[]): TradeRow[] {
  // Grouped by token id where there is one: two outcomes never share a token,
  // while the printed label can be empty on a fill the venue reported without
  // one — and a sell filed under "" closes nothing, leaving every purchase it
  // paid for still reading as open.
  const byOutcome = new Map<string, LoggedOrder[]>();
  for (const order of orders) {
    const key = order.asset || order.outcome;
    const list = byOutcome.get(key) ?? [];
    list.push(order);
    byOutcome.set(key, list);
  }

  const rows: TradeRow[] = [];

  for (const [, group] of byOutcome) {
    const chronological = [...group].sort((a, b) => a.placedAt - b.placedAt);
    const outcome = group.find((o) => o.outcome)?.outcome ?? '';
    const lots: Lot[] = [];

    for (const order of chronological) {
      if (order.action !== 'BUY') continue;
      if (order.matched > 1e-9) {
        lots.push({
          shares: order.matched,
          price: order.price,
          at: order.placedAt,
          auto: order.auto,
          id: order.id,
        });
      }
      if (order.status === 'resting' && order.matched <= 1e-9) {
        rows.push({
          key: `b${order.id}`,
          outcome,
          shares: order.size,
          buyPrice: order.price,
          sellPrice: null,
          status: 'buying',
          orderId: order.orderId,
          pnl: null,
          pct: null,
          auto: order.auto,
          at: order.placedAt,
          movedAt: order.placedAt,
        });
      }
    }

    /** Take `want` shares off the front of the queue, oldest lot first. */
    const take = (want: number): Lot[] => {
      const taken: Lot[] = [];
      let left = want;
      while (left > 1e-9 && lots.length > 0) {
        const lot = lots[0];
        const size = Math.min(lot.shares, left);
        taken.push({ ...lot, shares: size });
        lot.shares -= size;
        left -= size;
        if (lot.shares <= 1e-9) lots.shift();
      }
      return taken;
    };

    const close = (
      sell: LoggedOrder,
      shares: number,
      status: 'closed' | 'pending',
    ) => {
      for (const lot of take(shares)) {
        // A hundredth of a share is float dust from splitting lots, not a
        // trade — it showed up as a "0.0 → 0.0" row with a zero result.
        if (lot.shares < 0.01) continue;
        const cost = buyCost(lot.shares, lot.price);
        const proceeds = sellProceeds(lot.shares, sell.price);
        rows.push({
          key: `${status}${sell.id}-${lot.id}-${lot.shares.toFixed(3)}`,
          outcome,
          shares: lot.shares,
          buyPrice: lot.price,
          sellPrice: sell.price,
          status,
          orderId: status === 'pending' ? sell.orderId : null,
          closedBy: sell.auto ? 'rule' : 'hand',
          pnl: proceeds - cost,
          pct: cost > 0 ? (proceeds - cost) / cost : null,
          auto: lot.auto || sell.auto,
          at: lot.at,
          movedAt: sell.placedAt,
        });
      }
    };

    // Fills first: money that has actually moved decides which lots are gone.
    for (const order of chronological) {
      if (order.action === 'SELL' && order.matched > 1e-9) {
        close(order, order.matched, 'closed');
      }
    }

    // Then what is merely offered, over whatever is left.
    for (const order of chronological) {
      if (order.action !== 'SELL') continue;
      const resting = order.size - order.matched;
      if (resting > 1e-9 && (order.status === 'resting' || order.status === 'partial')) {
        close(order, resting, 'pending');
      }
    }

    for (const lot of lots) {
      if (lot.shares <= 1e-9) continue;
      rows.push({
        key: `o${lot.id}-${lot.shares.toFixed(3)}`,
        outcome,
        shares: lot.shares,
        buyPrice: lot.price,
        sellPrice: null,
        status: 'open',
        pnl: null,
        pct: null,
        auto: lot.auto,
        at: lot.at,
        movedAt: lot.at,
      });
    }
  }

  return rows.sort((a, b) => b.movedAt - a.movedAt);
}

/** What the paired rows come to — the same figure the window's score shows. */
export function realised(rows: TradeRow[]): number {
  return rows
    .filter((r) => r.status === 'closed')
    .reduce((sum, r) => sum + (r.pnl ?? 0), 0);
}
