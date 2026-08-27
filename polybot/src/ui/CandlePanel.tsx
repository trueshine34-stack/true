import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { candleShape, signedPct, type Candle } from '../core/candles';
import { findLevels } from '../core/levels';
import { priceLabel } from '../core/depth';

/** Chart units, scaled to whatever width the screen gives them. */
const W = 360;
const H = 150;

/** Binance pushes the forming candle every couple of seconds. */
const TICK_MS = 1_000;

/**
 * Binance's five-minute candles, and the prices they keep turning at.
 *
 * Four hours of context: a window opening into the fourth green candle of a
 * run is a different bet from one opening into chop. The lines across it are
 * where price has stopped more than once — the level overhead is what a rally
 * has to get through, the one underneath is what a fall has to break.
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
  const levels = shape ? findLevels(candles, shape.last) : [];
  // The candles' own scale, so a level lands on the price that made it.
  const y = (price: number) =>
    shape ? ((shape.top - price) / (shape.top - shape.floor)) * H : 0;

  return (
    <div className="candles">
      <svg className="candles-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        {/*
          Levels first, under the candles: they are the background the price is
          working against, not marks on top of it.
        */}
        {levels.map((level) => (
          <line
            key={level.price}
            className={`slevel ${level.kind}`}
            x1="0"
            x2={W}
            y1={y(level.price).toFixed(1)}
            y2={y(level.price).toFixed(1)}
          />
        ))}

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

        {/*
          The prices themselves go over the candles: a label a candle is
          drawn on top of is a label with its last digits missing.
        */}
        {levels.map((level) => (
          <text
            key={level.price}
            className={`slevel-tag ${level.kind}`}
            x={W - 3}
            y={(y(level.price) - 2.5).toFixed(1)}
            textAnchor="end"
          >
            {priceLabel(level.price)}
          </text>
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
