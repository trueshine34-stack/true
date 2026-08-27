import { useEffect, useState } from 'react';
import { PolyBot } from '../native/polybot';
import {
  bigPrice,
  shapeWindow,
  signedPrice,
  type ChartShape,
  type PricePoint,
} from '../core/chart';

/** Chart units. Drawn once and scaled to whatever width the screen gives it. */
const W = 360;
const H = 88;

/** Kept clear at the right edge for the live dot. */
const INSET = 8;

/** A live window is redrawn this often; the series itself ticks every 5s. */
const LIVE_MS = 2_000;

/**
 * The window's price against the price it has to beat.
 *
 * This is the same series Polymarket draws — their sixty-second TWAP, the
 * average the market actually settles on — and the dashed line across it is
 * the window's opening price. Everything a five-minute bet turns on is in the
 * distance between the two: above the line Up pays, below it Down does, and
 * the gap is how much room the price still has to change its mind.
 *
 * A finished window is fetched once and then left alone; only the running one
 * is polled, and only while it is on screen.
 */
export function WindowChart({
  windowStart,
  live,
}: {
  windowStart: number | null;
  /** Whether this window is still running, and so still worth re-asking. */
  live: boolean;
}) {
  const [points, setPoints] = useState<PricePoint[]>([]);
  const [target, setTarget] = useState(0);
  const [start, setStart] = useState(0);

  useEffect(() => {
    if (windowStart == null) return;
    let alive = true;

    const pull = () => {
      void PolyBot.polyWindow({ windowStart })
        .then((r) => {
          if (!alive) return;
          setPoints((r.points ?? []) as PricePoint[]);
          setTarget(r.target ?? 0);
          setStart(windowStart);
        })
        .catch(() => {
          // A window the price service has not opened yet answers with an
          // error; the frame stays and fills in when it does.
        });
    };

    pull();
    if (!live) return () => {
      alive = false;
    };
    const timer = window.setInterval(pull, LIVE_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [windowStart, live]);

  // Points belonging to the window before this one would be drawn at the wrong
  // place on the new one's axis, so nothing is drawn until the answer for the
  // window on screen is in.
  const mine = start === windowStart ? points : [];
  const shape =
    windowStart == null
      ? null
      : shapeWindow(
          mine,
          windowStart,
          start === windowStart ? target : 0,
          W,
          H,
          INSET,
        );

  return <ChartFace shape={shape} target={start === windowStart ? target : 0} />;
}

/**
 * The drawing itself, from a shape and a target and nothing else.
 *
 * Kept apart from the fetching so what ends up on the glass can be rendered
 * from a fixed set of readings — a chart is the one thing here that cannot be
 * checked by asserting on numbers.
 */
export function ChartFace({
  shape,
  target,
}: {
  shape: ChartShape | null;
  target: number;
}) {
  const value = shape?.last?.value ?? 0;
  const delta = value > 0 && target > 0 ? value - target : 0;
  const winning = delta >= 0;

  return (
    <div className="wchart">
      {/*
        Above the drawing, not over it: how far this window is from the price
        it must beat, what that price is now, and the line itself. Laid over
        the chart they landed on the curve as often as not.
      */}
      <div className="wchart-head">
        <b className={value > 0 ? (winning ? 'up' : 'down') : 'muted'}>
          {value > 0 && target > 0 ? signedPrice(delta) : '—'}
        </b>
        <span className="muted">{value > 0 ? bigPrice(value) : ''}</span>
        <span className="wchart-goal muted">
          {target > 0 ? bigPrice(target) : ''}
        </span>
      </div>

      <svg className="wchart-svg" viewBox={`0 0 ${W} ${H}`} aria-hidden>
        <defs>
          <linearGradient id="wchartfill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--warn)" stopOpacity="0.26" />
            <stop offset="100%" stopColor="var(--warn)" stopOpacity="0" />
          </linearGradient>
        </defs>

        {shape && (
          <>
            {shape.area && <path d={shape.area} fill="url(#wchartfill)" />}
            {/* The price to beat: one line, across everything, the way the
                site draws it — it is the only reference on the chart. */}
            <line
              className="wchart-target"
              x1="0"
              x2={W}
              y1={shape.targetY}
              y2={shape.targetY}
            />
            {shape.path && (
              <path className="wchart-line" d={shape.path} fill="none" />
            )}
            {shape.last && (
              <>
                <circle
                  className="wchart-halo"
                  cx={shape.last.x}
                  cy={shape.last.y}
                  r="7"
                />
                <circle
                  className="wchart-dot"
                  cx={shape.last.x}
                  cy={shape.last.y}
                  r="3"
                />
              </>
            )}
          </>
        )}
      </svg>

    </div>
  );
}
