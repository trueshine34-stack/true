import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { candleShape, signedPct, type Candle } from '../core/candles';
import { findLevels } from '../core/levels';
import { levelAhead, ratePerHour, trendOf } from '../core/trend';
import { priceLabel } from '../core/depth';

/** Every window is five minutes, and every window opens on a multiple of it. */
const WINDOW_SEC = 300;

/** Seconds in one candle of an interval, for the marks that are about time. */
function intervalSec(interval: string): number {
  const n = Number(interval.replace(/[^0-9]/g, '')) || 1;
  return interval.endsWith('h') ? n * 3600 : n * 60;
}

/** Chart width in chart units, scaled to whatever the screen gives it. */
const W = 360;

/**
 * The candle in progress follows the tape rather than an interval, so the
 * chart is redrawn at about the rate a screen can show a change at all. It
 * reads memory the app already holds: nothing here is a request.
 */
const TICK_MS = 250;

/**
 * Binance's five-minute candles, and the prices they keep turning at.
 *
 * Four hours of context: a window opening into the fourth green candle of a
 * run is a different bet from one opening into chop. The lines across it are
 * where price has stopped more than once — the level overhead is what a rally
 * has to get through, the one underneath is what a fall has to break.
 *
 * The candles are kept in the app off Binance's own streams — the kline frames
 * for the shape and every trade as it prints for the right-hand edge — so this
 * reads memory and only redraws. One panel per interval: the five-minute series for
 * the hours behind the window, the one-minute series for the last hour of it.
 */
export function CandlePanel({
  interval = '5m',
  height = 150,
}: {
  interval?: string;
  /** Chart units tall. The closer view is the shorter one. */
  height?: number;
}) {
  const [candles, setCandles] = useState<Candle[]>([]);

  useEffect(() => {
    let alive = true;
    const pull = () => {
      void PolyBot.binanceCandles({ interval })
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
  }, [interval]);

  return <CandleFace candles={candles} interval={interval} height={height} />;
}

/** The drawing, from candles and nothing else. */
export function CandleFace({
  candles,
  interval = '5m',
  height: H = 150,
}: {
  candles: Candle[];
  interval?: string;
  height?: number;
}) {
  const shape = candleShape(candles, W, H);
  const levels = shape ? findLevels(candles, shape.last) : [];

  /*
    Where the last half hour has been going, fitted rather than eyeballed —
    and the level it is heading into, which is the one worth watching out of
    the three. The close chart looks at thirty minutes, the wide one at an
    hour: each about a screen's worth of its own candles.
  */
  const trend = trendOf(candles, interval === '5m' ? 60 : 30);
  const ahead = shape && trend ? levelAhead(levels, shape.last, trend.way) : null;

  /*
    Where the running five minutes began.

    Windows open on multiples of three hundred seconds, so on the minute chart
    the newest candle whose open time is one of those is the one this bet
    started from — and on a chart of thirty minutes there are six of them, of
    which only the last is the window being traded.
  */
  const opened =
    shape && WINDOW_SEC % intervalSec(interval) === 0
      ? [...shape.bars].reverse().find((b) => b.time % WINDOW_SEC === 0)
      : undefined;
  // The candles' own scale, so a level lands on the price that made it.
  const y = (price: number) =>
    shape ? ((shape.top - price) / (shape.top - shape.floor)) * H : 0;

  /*
    Two levels a few dollars apart put their prices on top of each other and
    neither can be read. The one price is heading into is kept whatever else
    goes; the rest keep their lines and lose the label.
  */
  const labelled = (() => {
    if (!shape) return [] as typeof levels;
    const order = [
      ...levels.filter((l) => l.price === ahead),
      ...levels.filter((l) => l.price !== ahead),
    ];
    const kept: typeof levels = [];
    for (const level of order) {
      const at = y(level.price);
      if (kept.every((k) => Math.abs(y(k.price) - at) >= 9)) kept.push(level);
    }
    return kept;
  })();

  return (
    <div className="candles">
      <svg className="candles-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        {/*
          Levels first, under the candles: they are the background the price is
          working against, not marks on top of it.
        */}
        {/*
          The fitted line, over the span it was fitted to. Flat is drawn too:
          "no direction" is an answer, and an empty chart looks like a missing
          one rather than a quiet market.
        */}
        {shape && trend && (
          <line
            className={`trendline ${trend.way}`}
            x1={(shape.bars[trend.fromIndex]?.x ?? 0).toFixed(1)}
            x2={(shape.bars[shape.bars.length - 1]?.x ?? W).toFixed(1)}
            y1={y(trend.from).toFixed(1)}
            y2={y(trend.to).toFixed(1)}
          />
        )}

        {levels.map((level) => (
          <line
            key={level.price}
            className={`slevel ${level.kind}${level.price === ahead ? ' ahead' : ''}`}
            x1="0"
            x2={W}
            y1={y(level.price).toFixed(1)}
            y2={y(level.price).toFixed(1)}
          />
        ))}

        {/*
          A small mark over the candle the window opened on. It sits just above
          that candle rather than at the top of the panel, so it points at the
          price the window is being judged against and not merely at a moment.
        */}
        {opened &&
          (() => {
            // The tip sits just over the candle's high, and the whole mark is
            // pushed back inside when that candle is against the ceiling.
            const tip = Math.max(6, opened.high - 2);
            const top = tip - 5;
            return (
              <path
                className="openmark"
                d={
                  `M${(opened.x - 3.4).toFixed(1)} ${top.toFixed(1)}` +
                  `L${(opened.x + 3.4).toFixed(1)} ${top.toFixed(1)}` +
                  `L${opened.x.toFixed(1)} ${tip.toFixed(1)}Z`
                }
              />
            );
          })()}

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
        {labelled.map((level) => (
          <text
            key={level.price}
            className={`slevel-tag ${level.kind}${
              level.price === ahead ? ' ahead' : ''
            }`}
            x={3}
            /* A level near the ceiling puts its price under the line instead
               of half off the top of the panel. */
            y={(y(level.price) < 11
              ? y(level.price) + 8
              : y(level.price) - 2.5
            ).toFixed(1)}
            textAnchor="start"
          >
            {priceLabel(level.price)}
          </text>
        ))}
      </svg>

      {/*
        Where this interval opened, and how far it has come from there. On the
        five-minute chart that is the window's own open — the price the bet is
        settled against — and the number beside it is the whole question.
      */}
      <div className="candles-foot">
        <span className="muted">{interval.replace('m', 'м')}</span>
        <b>{shape ? priceLabel(shape.open) : '—'}</b>
        <span className={shape && shape.sinceOpen >= 0 ? 'up' : 'down'}>
          {shape ? signedPct(shape.sinceOpen) : ''}
        </span>
        {trend && (
          <em className={`trendrate ${trend.way}`}>
            {trend.way === 'up' ? '↗' : trend.way === 'down' ? '↘' : '→'}{' '}
            {trend.way === 'flat' ? 'вбок' : ratePerHour(trend.perHour)}
          </em>
        )}
      </div>
    </div>
  );
}
