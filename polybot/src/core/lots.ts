/**
 * A position read back as the purchases it is made of.
 *
 * The desk shows one average and the exchange reports one, but a position built
 * out of two buys a dime apart is two trades: a margin worked out from the
 * average asks the cheaper purchase for more than it needs and the dearer one
 * for less than it was promised. The sell rules cover them one at a time, each
 * priced off what it actually paid — so the chips have to say the same thing,
 * or the number on the button is not the number the rule will use.
 *
 * This mirrors the pairing the service does: buys oldest first, every sell that
 * was not cancelled taking shares off the front of the queue. A resting sell
 * counts as cover, because the question is "which purchase still has no exit
 * arranged" and an offer on the book is one.
 */
export type Lot = {
  shares: number;
  /** What it actually went at, which is not always what was asked. */
  price: number;
  at: number;
};

type LogLike = {
  action: 'BUY' | 'SELL';
  price: number;
  fillPrice?: number | null;
  size: number;
  matched: number;
  status: string;
  placedAt: number;
};

export function uncoveredLots(orders: LogLike[]): Lot[] {
  const mine = [...orders].sort((a, b) => a.placedAt - b.placedAt);
  const lots: Lot[] = [];

  for (const o of mine) {
    if (o.action !== 'BUY' || o.matched <= 1e-9) continue;
    lots.push({
      shares: o.matched,
      price: o.fillPrice ?? o.price,
      at: o.placedAt,
    });
  }

  for (const o of mine) {
    if (o.action !== 'SELL' || o.status === 'cancelled') continue;
    // A resting sell has sold nothing yet and still covers its shares: what is
    // being counted is exits arranged, not money received.
    let left = Math.max(o.matched, o.size);
    let i = 0;
    while (left > 1e-9 && i < lots.length) {
      const take = Math.min(lots[i]!.shares, left);
      lots[i]!.shares -= take;
      left -= take;
      if (lots[i]!.shares <= 1e-9) i += 1;
    }
  }

  return lots.filter((l) => l.shares > 1e-6);
}

/**
 * What the next exit will be measured from.
 *
 * The oldest purchase with no exit arranged — the one the rule will cover on
 * its next pass. Null when every purchase already has one, which is when the
 * chips have nothing left to be about.
 */
export function nextLotCost(orders: LogLike[]): number | null {
  const lot = uncoveredLots(orders)[0];
  return lot && lot.price > 0 ? lot.price : null;
}
