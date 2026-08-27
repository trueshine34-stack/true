import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { btc, depthShape, priceLabel } from '../core/depth';

/** Chart units, scaled to whatever width the screen gives them. */
const W = 360;
const H = 76;

/** The book is kept locally, so this costs nothing but a redraw. */
const TICK_MS = 150;

export interface Book {
  bid: number;
  ask: number;
  span: number;
  bids: number[];
  asks: number[];
}

/**
 * Binance's book under the window's price.
 *
 * The line above says where the price is; this says what is holding it there.
 * Both sides are drawn to one scale out from the mid, so the side that leans
 * is the side with more money behind it — and the totals under it are what is
 * bid and offered inside the whole span, which is the number the lean is worth.
 *
 * Updated seven times a second off a book kept in the app from Binance's
 * hundred-millisecond diff stream: nothing here is a network round trip.
 */
export function DepthPanel() {
  const [book, setBook] = useState<Book | null>(null);
  /** Where the running five minutes opened, for the move under the price. */
  const [open, setOpen] = useState(0);

  useEffect(() => {
    let alive = true;
    const pull = () => {
      void PolyBot.binanceDepth()
        .then((r) => {
          if (!alive) return;
          if (!r.ready || !r.bid || !r.ask) {
            setBook(null);
            return;
          }
          setBook({
            bid: r.bid,
            ask: r.ask,
            span: r.span ?? 0,
            bids: r.bids ?? [],
            asks: r.asks ?? [],
          });
        })
        .catch(() => {
          if (alive) setBook(null);
        });
    };
    pull();
    const timer = window.setInterval(pull, TICK_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  // The five-minute candle in progress opens where the window opened, and the
  // distance from it is what the bet is about — so it belongs on the price
  // rather than one panel further up.
  useEffect(() => {
    let alive = true;
    const pull = () => {
      void PolyBot.binanceCandles({ interval: '5m' })
        .then((r) => {
          const last = r.candles?.[r.candles.length - 1];
          if (alive && last) setOpen(last[1]);
        })
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, 2_000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  return <DepthFace book={book} open={open} />;
}

/**
 * The drawing, from a book and nothing else — kept apart from the polling so a
 * fixed book can be rendered and looked at.
 */
export function DepthFace({
  book,
  open = 0,
}: {
  book: Book | null;
  /** Where the current five minutes opened, or nothing when it is unknown. */
  open?: number;
}) {
  const shape = book ? depthShape(book.bids, book.asks, W, H) : null;
  const mid = book ? (book.bid + book.ask) / 2 : 0;

  return (
    <div className="depth">
      <svg className="depth-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        {shape && (
          <>
            <path className="depth-bid" d={shape.bidPath} />
            <path className="depth-ask" d={shape.askPath} />
          </>
        )}
        {/* The mid, where the two sides meet and the next trade happens. */}
        <line className="depth-mid" x1={W / 2} x2={W / 2} y1="0" y2={H} />
      </svg>

      {/*
        The span either side, and what stands inside it. Three prices say how
        wide the picture is; the two sizes say which way it leans.
      */}
      <div className="depth-foot">
        <span className="up">{shape ? btc(shape.bidTotal) : '—'}</span>
        <i>{mid > 0 ? priceLabel(mid * (1 - book!.span)) : ''}</i>
        <b>
          {mid > 0 ? priceLabel(mid) : 'стакан грузится'}
          {mid > 0 && open > 0 && (
            <em className={mid >= open ? 'up' : 'down'}>
              {mid >= open ? '+' : '−'}
              {Math.round(Math.abs(mid - open)).toLocaleString('ru-RU')}$
            </em>
          )}
        </b>
        <i>{mid > 0 ? priceLabel(mid * (1 + book!.span)) : ''}</i>
        <span className="down">{shape ? btc(shape.askTotal) : '—'}</span>
      </div>
    </div>
  );
}
