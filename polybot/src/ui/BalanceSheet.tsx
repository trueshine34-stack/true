import { useMemo, useState } from 'react';
import {
  SPANS,
  pathFor,
  sliceFor,
  statsFor,
  type BalancePoint,
} from '../core/balance';
import { signedPct, signedUsd, usd } from '../core/money';

const W = 320;
const H = 132;

/**
 * The balance over time.
 *
 * The header number answers "how much"; this answers "which way". The line is
 * the whole point, so it gets the room — the figures around it are there to
 * scale it, not to compete with it.
 */
export function BalanceSheet({
  history,
  balance,
  onClose,
}: {
  history: BalancePoint[];
  balance: number | null;
  onClose: () => void;
}) {
  const [span, setSpan] = useState(2);

  const points = useMemo(
    () => sliceFor(history, SPANS[span].ms),
    [history, span],
  );
  const stats = useMemo(() => statsFor(points), [points]);
  const path = useMemo(() => pathFor(points, W, H), [points]);
  const rising = (stats?.change ?? 0) >= 0;

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>Баланс</h2>
          <button className="xbtn" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        <div className="balnow">
          <div className="balnow-value">{balance === null ? '—' : usd(balance)}</div>
          {stats && (
            <div className={`balnow-change ${rising ? 'up' : 'down'}`}>
              {signedUsd(stats.change)}
              <span className="muted"> · {signedPct(stats.changePct)}</span>
            </div>
          )}
        </div>

        {path ? (
          <svg
            className="balchart"
            viewBox={`0 0 ${W} ${H}`}
            preserveAspectRatio="none"
            role="img"
            aria-label="История баланса"
          >
            <defs>
              <linearGradient id="balfill" x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="0%"
                  stopColor={rising ? 'var(--up)' : 'var(--down)'}
                  stopOpacity="0.28"
                />
                <stop
                  offset="100%"
                  stopColor={rising ? 'var(--up)' : 'var(--down)'}
                  stopOpacity="0"
                />
              </linearGradient>
            </defs>
            <path d={path.area} fill="url(#balfill)" />
            <path
              d={path.line}
              fill="none"
              stroke={rising ? 'var(--up)' : 'var(--down)'}
              strokeWidth="2"
              strokeLinejoin="round"
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
            />
          </svg>
        ) : (
          <div className="muted empty">
            Пока нечего показать — история набирается, пока приложение открыто.
          </div>
        )}

        <div className="pcts spanrow">
          {SPANS.map((s, i) => (
            <button
              key={s.label}
              className={i === span ? 'on' : undefined}
              onClick={() => setSpan(i)}
            >
              {s.label}
            </button>
          ))}
        </div>

        {stats && (
          <>
            <div className="row">
              <span className="label">Было</span>
              <span className="value">{usd(stats.first)}</span>
            </div>
            <div className="row">
              <span className="label">Минимум · максимум</span>
              <span className="value">
                {usd(stats.min)} <span className="muted">·</span> {usd(stats.max)}
              </span>
            </div>
            <div className="row">
              <span className="label">Точек</span>
              <span className="value muted">{points.length}</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
