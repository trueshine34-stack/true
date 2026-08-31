import { useMemo, useState } from 'react';
import {
  SPANS,
  adjustedPoints,
  pathFor,
  sliceFor,
  statsFor,
  totalWithdrawn,
  type Adjustment,
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
  adjustments,
  balance,
  free,
  locked,
  onLocked,
  savings,
  savingsAddress,
  onSavingsAddress,
  onClose,
}: {
  history: BalancePoint[];
  adjustments: Adjustment[];
  /** The wallet on the venue, reserve and all. */
  balance: number | null;
  /** And what is left of it to trade with. */
  free: number | null;
  /** Money set aside, which no order may reach. */
  locked: number;
  onLocked: (usd: number) => void;
  /** USDT held off the venue, where profit is withdrawn to. */
  savings: number;
  savingsAddress: string;
  onSavingsAddress: (address: string) => void;
  onClose: () => void;
}) {
  const [span, setSpan] = useState(2);

  // Carry withdrawals back into the line: taking profit out is not a loss, and
  // a chart that reads it as one says the run gave back everything it made.
  const points = useMemo(
    () => adjustedPoints(sliceFor(history, SPANS[span].ms), adjustments),
    [history, adjustments, span],
  );
  const withdrawn = useMemo(() => totalWithdrawn(adjustments), [adjustments]);
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
          <div className="balnow-value">
            {balance === null ? '—' : usd(balance + savings)}
          </div>
          {stats && (
            <div className={`balnow-change ${rising ? 'up' : 'down'}`}>
              {signedUsd(stats.change)}
              <span className="muted"> · {signedPct(stats.changePct)}</span>
            </div>
          )}
        </div>

        {/*
          What that total is made of. Only the collateral can be traded; the
          rest has been taken off the venue and is counted so that withdrawing
          it does not read as losing it. And of the collateral, only what is
          not locked away — that part is on the venue and untouchable at once,
          which is two different things and so two different figures.
        */}
        {(savings > 0 || locked > 0) && (
          <div className="balparts">
            <div>
              <span className="muted">на бирже</span>
              <b>{balance === null ? '—' : usd(balance)}</b>
            </div>
            {locked > 0 && (
              <div>
                <span className="muted">в торговле</span>
                <b>{free === null ? '—' : usd(free)}</b>
              </div>
            )}
            {savings > 0 && (
              <div>
                <span className="muted">USDT BEP-20</span>
                <b>{usd(savings)}</b>
              </div>
            )}
          </div>
        )}

        {/*
          Money the app is not allowed to reach.

          Enforced where the balance is read rather than where each order is
          sized: every buy in the app — both bots, both accounts and every tap
          — asks for the balance and gets this number already taken out of it,
          so there is no path that could forget. Above the wallet it simply
          means nothing may be bought.
        */}
        <label className="field ballock">
          <span>заблокировано, $</span>
          <input
            type="number"
            inputMode="decimal"
            min={0}
            step="1"
            placeholder="0"
            defaultValue={locked > 0 ? String(locked) : ''}
            onBlur={(e) => onLocked(Number(e.target.value))}
          />
        </label>
        <div className="muted balhint">
          Эти деньги не участвуют в покупках вообще: и боты, и ручные ордера
          считают размер от остатка, а не от баланса. Уже открытые позиции и
          продажи это не трогает.
        </div>

        {/*
          The address money is taken out to, and the button that takes it.
          Watched on both chains; sent to only on Polygon, which is the one the
          app holds a key for.
        */}
        <label className="field balsavings">
          <span>кошелёк вывода</span>
          <input
            type="text"
            inputMode="text"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder="0x…"
            defaultValue={savingsAddress}
            onBlur={(e) => onSavingsAddress(e.target.value)}
          />
        </label>

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
            {withdrawn > 0 && (
              <div className="row">
                <span className="label">Выведено всего</span>
                <span className="value">
                  {usd(withdrawn)}
                  <span className="muted"> · учтено в линии</span>
                </span>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
