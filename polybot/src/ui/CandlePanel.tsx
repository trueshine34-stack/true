import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { candleShape, signedPct, type Candle } from '../core/candles';
import { findLevels } from '../core/levels';
import { levelAhead, ratePerHour, trendOf } from '../core/trend';
import { forecast, type BookRead } from '../core/forecast';
import { loadTrail, rememberTrail, trailBetween } from '../core/trail';
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
 * The share of the width kept clear on the right for the forecast.
 *
 * A projection drawn over the candles is an opinion about the past. Given its
 * own lane, it is a claim about the future that the chart will walk into and
 * settle by itself.
 */
const LANE = 0.25;

/** So the candles get the rest. */
const PLOT = W * (1 - LANE);

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
  const [book, setBook] = useState<BookRead | null>(null);

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
      // Where the resting size is stacked right now, which is the nearest
      // thing the forecast has to a wall it can actually see.
      void PolyBot.binanceDepth()
        .then((d) => {
          if (!alive) return;
          setBook(
            d.ready && d.bid && d.ask
              ? {
                  bid: d.bid,
                  ask: d.ask,
                  span: d.span ?? 0,
                  bids: d.bids ?? [],
                  asks: d.asks ?? [],
                }
              : null,
          );
        })
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, TICK_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [interval]);

  return (
    <CandleFace
      candles={candles}
      book={book}
      interval={interval}
      height={height}
    />
  );
}

/** The drawing, from candles and nothing else. */
export function CandleFace({
  candles,
  book = null,
  interval = '5m',
  height: H = 150,
}: {
  candles: Candle[];
  /** The order book, when there is one: the forecast leans on it. */
  book?: BookRead | null;
  interval?: string;
  height?: number;
}) {
  const last = candles.length > 0 ? candles[candles.length - 1][4] : 0;
  const levels = last > 0 ? findLevels(candles, last) : [];

  /*
    Where the last half hour has been going, fitted rather than eyeballed —
    and the level it is heading into, which is the one worth watching out of
    the three. The close chart looks at thirty minutes, the wide one at an
    hour: each about a screen's worth of its own candles.
  */
  const trend = trendOf(candles, interval === '5m' ? 60 : 30);
  const ahead = last > 0 && trend ? levelAhead(levels, last, trend.way) : null;

  /*
    And where it is likely to go from here, drawn in the lane the candles have
    not reached yet. The horizon is however many candles fit in that lane, so
    the projection is always exactly as far ahead as there is room to show it.
  */
  const steps = Math.max(1, Math.round((candles.length * LANE) / (1 - LANE)));
  const ahead5 = forecast(candles, trend, levels, book, steps);

  /*
    The claim is written down the first time it is made and never revised, so
    once these intervals print, the chart can show what was said about them
    beside what actually happened. That is the only way a line drawn into the
    future is worth anything.
  */
  const trail = ahead5 ? rememberTrail(interval, ahead5.points) : loadTrail(interval);

  /*
    The frame has to hold the projection as well as the candles, or a path that
    leaves their range is simply drawn off the panel.
  */
  const shape = candleShape(
    candles,
    PLOT,
    H,
    ahead5
      ? [
          ...ahead5.points.map((p) => p.price),
          // And enough of the band that the near half of it is inside the
          // frame. All of it would squash the candles the chart is for; none
          // of it would put the cone against the ceiling from the first step.
          ...ahead5.points
            .slice(0, Math.ceil(ahead5.points.length / 2))
            .flatMap((p) => [p.hi, p.lo]),
        ]
      : [],
  );

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
    The lane keeps the candles' own spacing, so a step of the forecast is one
    candle wide and the two halves of the chart read as one timeline.
  */
  const slot = candles.length > 0 ? PLOT / candles.length : 0;
  const futureX = (i: number) => PLOT + slot * (i + 0.5);

  /*
    What was predicted for the candles that have since printed. Each of these
    was written down a whole horizon before its interval opened, so the gap
    between this line and the candles under it is the forecast's own record.
  */
  const marked = shape
    ? trailBetween(
        trail,
        shape.bars[0]?.time ?? 0,
        shape.bars[shape.bars.length - 1]?.time ?? 0,
      )
        .map((p) => {
          const bar = shape.bars.find((b) => b.time === p.time);
          return bar ? { x: bar.x, y: y(p.price) } : null;
        })
        .filter((p): p is { x: number; y: number } => p !== null)
    : [];

  /** A path through points, or nothing when there are too few to draw. */
  const track = (points: { x: number; y: number }[]) =>
    points.length < 2
      ? null
      : points
          .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`)
          .join('');

  const path = ahead5
    ? [
        { x: shape?.bars[shape.bars.length - 1]?.x ?? PLOT, y: y(shape?.last ?? 0) },
        ...ahead5.points.map((p, i) => ({ x: futureX(i), y: y(p.price) })),
      ]
    : [];

  /*
    The band, as one shape: out along the top and back along the bottom. It is
    the honest part of the drawing — five candles out the answer is a cone, and
    a bare line would claim a precision the method does not have.
  */
  const cone =
    ahead5 && shape
      ? `M${(shape.bars[shape.bars.length - 1]?.x ?? PLOT).toFixed(1)} ${y(shape.last).toFixed(1)}` +
        ahead5.points
          .map((p, i) => `L${futureX(i).toFixed(1)} ${y(p.hi).toFixed(1)}`)
          .join('') +
        [...ahead5.points]
          .reverse()
          .map((p, i) =>
            `L${futureX(ahead5.points.length - 1 - i).toFixed(1)} ${y(p.lo).toFixed(1)}`,
          )
          .join('') +
        'Z'
      : null;

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
          The lane the candles have not reached yet, marked off so the drawing
          in it is read as a claim rather than as data.
        */}
        {ahead5 && (
          <line className="lanesplit" x1={PLOT} x2={PLOT} y1="0" y2={H} />
        )}

        {cone && <path className="cone" d={cone} />}

        {/*
          What the forecast said about candles that have since printed. Drawn
          under them, because the candles are what happened and this is only
          what was expected.
        */}
        {track(marked) && (
          <path className="forecast past" d={track(marked)!} />
        )}

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

        {/* The path itself, over the cone it belongs to. */}
        {track(path) && (
          <path className={`forecast ${ahead5?.way ?? 'flat'}`} d={track(path)!} />
        )}

        {/* Where it expects the move to run out, if something is in the way. */}
        {ahead5?.wall != null && (
          <line
            className="forecast-wall"
            x1={PLOT}
            x2={W}
            y1={y(ahead5.wall).toFixed(1)}
            y2={y(ahead5.wall).toFixed(1)}
          />
        )}

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
        {ahead5 && (
          <em className={`forecast-tag ${ahead5.way}`}>
            прогноз {priceLabel(ahead5.target)}
            {ahead5.wall != null ? ` · упор ${priceLabel(ahead5.wall)}` : ''}
          </em>
        )}
      </div>
    </div>
  );
}
