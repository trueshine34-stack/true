import { useEffect, useRef, useState } from 'react';
import { PolyBot } from '../native/polybot';
import { candleShape, signedPct, type Candle } from '../core/candles';
import { volumeNodes } from '../core/profile';
import { findLevels } from '../core/levels';
import {
  NEAR_MINUTES,
  WIDE_MINUTES,
  levelAhead,
  ratePerHour,
  trendOf,
} from '../core/trend';
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

/** Pinched all the way in, this many candles still have to be readable. */
const MIN_BARS = 8;

/**
 * How many candles each chart shows at rest.
 *
 * Four hours of context on the five-minute chart, the last half hour close up
 * on the minute one — the same two views the desk has always had. A pinch goes
 * either way from here, as far as twice this and as close as [MIN_BARS].
 */
const BARS: Record<string, number> = { '5m': 48, '1m': 30 };

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
  onPick,
  picked,
  height = 150,
  results,
  digits = 0,
  coin,
}: {
  interval?: string;
  /** Called with a candle's open time when it is tapped. */
  onPick?: (time: number) => void;
  picked?: number | null;
  /** Chart units tall. The closer view is the shorter one. */
  height?: number;
  /**
   * What each window made, by the open time of its candle in milliseconds.
   *
   * A five-minute window is a five-minute candle, so its result belongs over
   * that candle. It used to live in a strip of chips behind a button of its
   * own — the same windows in the same order as the chart, drawn a second
   * time, one tap away from the chart that was already showing them.
   */
  results?: Record<string, number>;
  /** How finely this coin's price is printed on the labels. */
  digits?: number;
  /**
   * Which coin these candles are of.
   *
   * Only to be watched: the series itself comes from the native side, which
   * is already pointed at one coin — this is what tells the panel to drop the
   * bars it is holding and ask again rather than draw one coin's shape under
   * another coin's prices for the next few seconds.
   */
  coin?: string;
}) {
  const [candles, setCandles] = useState<Candle[]>([]);
  const [levels, setLevels] = useState<DayLevel[]>([]);
  const [settled, setSettled] = useState<Record<string, 'Up' | 'Down'>>({});

  // Nothing here belongs to the new coin, and a chart of bitcoin's bars with
  // solana's levels drawn over it is worse than an empty frame for the second
  // it takes to answer.
  useEffect(() => {
    setCandles([]);
    setLevels([]);
    setSettled({});
  }, [coin]);

  useEffect(() => {
    let alive = true;
    const pull = () => {
      // Twice what is drawn, so a pinch outwards has somewhere to go. The
      // native side keeps that much and answers out of memory.
      void PolyBot.binanceCandles({ interval, limit: (BARS[interval] ?? 48) * 2 })
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
  }, [interval, coin]);

  /*
    The levels the rule actually holds, rather than a second set computed here
    off what happens to be on screen. They are merged over a day and only added
    to, so they do not move between frames and there is nothing to animate —
    once a second is plenty.
  */
  useEffect(() => {
    let alive = true;
    const pull = () => {
      void PolyBot.dayLevels()
        .then((r) => {
          if (alive) setLevels(r.levels ?? []);
        })
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, 1000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [coin]);

  /*
    And how each window on screen settled. Only the five-minute chart has one
    window per candle, so only it asks — and it asks for what it is showing,
    which fills in over a few seconds as the answers arrive.
  */
  const times = candles.map((c) => c[0]).join(',');
  useEffect(() => {
    if (interval !== '5m') return undefined;
    let alive = true;
    const windows = times ? times.split(',').map(Number) : [];
    if (windows.length === 0) return undefined;
    const pull = () => {
      void PolyBot.windowResults({ windows })
        .then((r) => {
          if (alive) setSettled(r.results ?? {});
        })
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, 2000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [interval, times, coin]);

  return (
    <CandleFace
      candles={candles}
      interval={interval}
      bars={BARS[interval] ?? 48}
      height={height}
      onPick={onPick}
      picked={picked}
      levels={levels}
      settled={settled}
      results={results}
      digits={digits}
    />
  );
}

/** A level as the rule holds it: a zone, with the band it covers. */
export type DayLevel = {
  price: number;
  touches: number;
  kind: 'support' | 'resistance';
  low: number;
  high: number;
};

/** The drawing, from candles and nothing else. */
export function CandleFace({
  candles,
  interval = '5m',
  height: H = 150,
  onPick,
  picked,
  levels: held,
  settled,
  results,
  digits = 0,
  bars,
}: {
  candles: Candle[];
  interval?: string;
  /** How many candles are drawn before anything is pinched. */
  bars?: number;
  height?: number;
  /** Called with a candle's open time when it is tapped. */
  onPick?: (time: number) => void;
  picked?: number | null;
  /** The levels the rule holds. Without them the chart falls back to its own. */
  levels?: DayLevel[];
  /** How each window settled on Polymarket, keyed by window start. */
  settled?: Record<string, 'Up' | 'Down'>;
  /** And what each window made, keyed the same way. */
  results?: Record<string, number>;
  /** How finely this coin's prices are printed on the labels. */
  digits?: number;
}) {
  /**
   * How many candles are on screen, when the chart has been pinched.
   *
   * Null is "as many as came", which is the default and what every frame is
   * until two fingers say otherwise. Held here rather than in the panel above
   * because it is a property of the drawing, and the panel keeps refetching
   * the same series underneath it.
   */
  const [shown, setShown] = useState<number | null>(null);
  const pinch = useRef<{ gap: number; from: number } | null>(null);
  /** When the last pinch ended, so its release is not read as a tap. */
  const pinchedAt = useRef(0);

  // The panel asks for more candles than it draws, so that pinching out has
  // something to reach into; this is how many of them are on screen at rest.
  const atRest = Math.min(bars ?? candles.length, candles.length);
  const visible = candles.slice(-Math.min(shown ?? atRest, candles.length));

  const gapOf = (touches: React.TouchList) => {
    const [a, b] = [touches[0], touches[1]];
    return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
  };

  const onTouchStart = (e: React.TouchEvent) => {
    if (e.touches.length !== 2) return;
    pinch.current = { gap: gapOf(e.touches), from: visible.length };
  };

  const onTouchMove = (e: React.TouchEvent) => {
    const start = pinch.current;
    if (!start || e.touches.length !== 2) return;
    const gap = gapOf(e.touches);
    if (!(gap > 0) || !(start.gap > 0)) return;
    // Fingers apart shows fewer candles, which is what "zoom in" means on a
    // chart: the same width spent on less time.
    const next = Math.round(start.from * (start.gap / gap));
    setShown(Math.max(MIN_BARS, Math.min(candles.length, next)));
  };

  const onTouchEnd = (e: React.TouchEvent) => {
    if (e.touches.length < 2 && pinch.current) {
      pinch.current = null;
      pinchedAt.current = Date.now();
    }
  };

  const shape = candleShape(visible, W, H);
  // The rule's own levels when they are to hand, so the line under the candle
  // is the line a window was refused at. The chart's own reading is the
  // fallback for a frame drawn before the first answer arrives.
  const levels =
    held && held.length > 0
      ? held
      : shape
        ? findLevels(visible, shape.last)
        : [];

  /*
    Where the last half hour has been going, fitted rather than eyeballed —
    and the level it is heading into, which is the one worth watching out of
    the three. The close chart looks at a quarter of an hour, the wide one at
    half of one.
  */
  // The same windows the rules fit their lines over, so the line on the
  // screen is the line the bot is reading — half an hour on the five-minute
  // chart, a quarter of an hour on the minute one.
  const trend = trendOf(visible, interval === '5m' ? WIDE_MINUTES : NEAR_MINUTES);
  const ahead = shape && trend ? levelAhead(levels, shape.last, trend.way) : null;

  /*
    Where the running five minutes began.

    Windows open on multiples of three hundred seconds, so on the minute chart
    the newest candle whose open time is one of those is the one this bet
    started from — and on half an hour of minutes there are six of them, of
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

  /*
    Which candle a tap landed on.

    The chart is drawn in its own units and stretched to whatever width the
    screen gives it, so the tap comes back as a fraction of that width and is
    put back into chart units before the nearest bar is looked for. Every bar
    is a five-minute window, and the window is what the history and the bot's
    own reading are filed under.
  */
  const pick = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!onPick || !shape) return;
    // The fingers coming off a pinch are not a tap on a candle.
    if (pinch.current || Date.now() - pinchedAt.current < 400) return;
    const box = e.currentTarget.getBoundingClientRect();
    if (box.width <= 0) return;
    const at = ((e.clientX - box.left) / box.width) * W;
    let best = shape.bars[0];
    for (const bar of shape.bars) {
      if (Math.abs(bar.x - at) < Math.abs(best.x - at)) best = bar;
    }
    if (best) onPick(best.time);
  };

  const lit = shape?.bars.find((b) => b.time === picked);

  /*
    Where the trading happened, over the whole series rather than the part of
    it on screen.

    Zooming must not move these lines or make them come and go: a price that
    a lot traded at is a fact about the session, not about how many candles
    happen to be drawn — and a band that vanishes when you look closer is
    worse than no band at all. So they are worked out once from everything
    held, and the chart draws the ones that fall inside what it is showing.
  */
  const nodes = volumeNodes(candles).filter(
    (node) => !shape || (node.price >= shape.floor && node.price <= shape.top),
  );

  return (
    <div
      className={`candles${onPick ? ' tappable' : ''}`}
      onPointerUp={onPick ? pick : undefined}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
      onTouchCancel={onTouchEnd}
    >
      <svg className="candles-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        {/* The candle being read, marked behind everything else. */}
        {lit && (
          <rect
            className="candlepick"
            x={(lit.x - lit.half * 2).toFixed(1)}
            y={0}
            width={(lit.half * 4).toFixed(1)}
            height={H}
          />
        )}
        {/*
          Levels first, under the candles: they are the background the price is
          working against, not marks on top of it.
        */}
        {/*
          And under those, where the volume was.

          Not levels and not drawn like them: these are the prices the session
          traded through, faint and many, and what they are for is knowing
          where a move will have something to push through. The strongest is
          the plainest; the rest fade with what went through them.
        */}
        {nodes.map((node) => (
          <line
            key={node.price}
            className="volnode"
            x1={0}
            x2={W}
            y1={y(node.price).toFixed(1)}
            y2={y(node.price).toFixed(1)}
            strokeOpacity={(0.10 + node.weight * 0.28).toFixed(2)}
          />
        ))}
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

        {/*
          A level is a zone rather than a line: the orders sit across the
          prices the market actually turned at, and the rule measures the room
          in front of an entry to the near edge of that band. So the band is
          drawn, faintly, under its own line — a refusal at a price nobody can
          see is a refusal nobody can check.
        */}
        {levels.map((level) => {
          const top = y(Math.max(level.high, level.price));
          const foot = y(Math.min(level.low, level.price));
          const tall = Math.abs(foot - top);
          return (
            <g key={level.price}>
              {tall >= 1.5 && (
                <rect
                  className={`sband ${level.kind}${level.price === ahead ? ' ahead' : ''}`}
                  x="0"
                  width={W}
                  y={Math.min(top, foot).toFixed(1)}
                  height={tall.toFixed(1)}
                />
              )}
              <line
                className={`slevel ${level.kind}${level.price === ahead ? ' ahead' : ''}`}
                x1="0"
                x2={W}
                y1={y(level.price).toFixed(1)}
                y2={y(level.price).toFixed(1)}
              />
            </g>
          );
        })}

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
          How each window settled, over the candle that is that window.
          Polymarket does not settle on this candle — it reads its own
          sixty-second average at the boundary and again at the close — so a
          candle that finishes green can pay Down, and the arrow is the half
          that decides whether a bet was right. Blank while the answer has
          not arrived, which is better than a guess drawn from the candle.
        */}
        {interval === '5m' &&
          shape?.bars.map((bar, i) => {
            // By the bar's own time, never by its position. The bars are
            // whatever the zoom is showing and the series is everything held,
            // so the two indexes stopped agreeing the moment a pinch dropped
            // a candle off the left — and every arrow moved to another candle
            // and, half the time, changed colour with it.
            const won = settled?.[String(bar.time)];
            if (!won) return null;
            const up = won === 'Up';
            // Just over the wick, and pushed back inside at the ceiling.
            const tip = Math.max(5, bar.high - 3);
            return (
              <text
                key={`won-${i}`}
                className={`wonmark ${up ? 'up' : 'down'}`}
                x={bar.x.toFixed(1)}
                y={tip.toFixed(1)}
              >
                {up ? '▲' : '▼'}
              </text>
            );
          })}

        {/*
          And what each window made, over its own candle, above the arrow.
          Only the windows that traded carry one: a nought over every candle
          the desk sat out would bury the few it did not.
        */}
        {interval === '5m' &&
          results &&
          shape?.bars.map((bar, i) => {
            const pnl = results[String(bar.time)];
            if (pnl == null) return null;
            const won = pnl >= 0;
            // Over the settlement arrow, which sits just over the wick.
            const tip = Math.max(5, bar.high - (settled?.[String(bar.time)] ? 14 : 4));
            return (
              <text
                key={`pnl-${i}`}
                className={`pnlmark ${won ? 'up' : 'down'}`}
                x={bar.x.toFixed(1)}
                y={tip.toFixed(1)}
              >
                {won ? '+' : '−'}
                {Math.abs(pnl) >= 10
                  ? Math.round(Math.abs(pnl))
                  : Math.abs(pnl).toFixed(1)}
              </text>
            );
          })}

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
            {priceLabel(level.price, digits)}
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
        <b>{shape ? priceLabel(shape.open, digits) : '—'}</b>
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
