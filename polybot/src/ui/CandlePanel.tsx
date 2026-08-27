import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { candleShape, signedPct, type Candle } from '../core/candles';
import { priceLabel } from '../core/depth';

/** Chart units, scaled to whatever width the screen gives them. */
const W = 360;
const H = 84;

/** Binance pushes the forming candle every couple of seconds. */
const TICK_MS = 1_000;

/**
 * Binance's five-minute candles, between the window and the book.
 *
 * The window's chart above is one five minutes against the price it must
 * beat; this is the four hours before it. A window opening into the fourth
 * green candle of a run is a different bet from one opening into chop, and
 * that is not visible anywhere else on the desk.
 *
 * The candles are kept in the app off Binance's own stream, so this reads
 * memory and only redraws.
 */
export function CandlePanel() {
  const [candles, setCandles] = useState<Candle[]>([]);

  useEffect(() => {
    let alive = true;
    const pull = () => {
      void PolyBot.binanceCandles()
        .then((r) => {
          if (alive) setCandles((r.candles ?? []) as Candle[]);
        })
        .catch(() => {
          // Still connecting; the frame fills in when it does.
        });
    };
    pull();
    const timer = window.setInterval(pull, TICK_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  return <CandleFace candles={candles} />;
}

/** The drawing, from candles and nothing else. */
export function CandleFace({ candles }: { candles: Candle[] }) {
  const shape = candleShape(candles, W, H);

  return (
    <div className="candles">
      <svg className="candles-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        {shape?.bars.map((bar, i) => (
          <g key={i} className={bar.up ? 'candle up' : 'candle down'}>
            {/* The wick first, so the body sits over it. */}
            <line
              x1={bar.x}
              x2={bar.x}
              y1={bar.high.toFixed(1)}
              y2={bar.low.toFixed(1)}
            />
            <rect
              x={(bar.x - bar.half).toFixed(1)}
              y={bar.top.toFixed(1)}
              width={(bar.half * 2).toFixed(1)}
              height={Math.max(0.6, bar.bottom - bar.top).toFixed(1)}
            />
          </g>
        ))}
      </svg>

      {/*
        What Binance says the price is, and what these four hours came to.
        Nothing else: the axis of a context chart is context.
      */}
      <div className="candles-foot">
        <span className="muted">5м</span>
        <b>{shape ? priceLabel(shape.last) : '—'}</b>
        <span className={shape && shape.changePct >= 0 ? 'up' : 'down'}>
          {shape ? signedPct(shape.changePct) : ''}
        </span>
      </div>
    </div>
  );
}
