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
import { DAY_MULTIPLE, dayTarget, type DayGoal } from '../core/day';

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
  lockedUsd,
  lockedPct,
  onLocked,
  day,
  onDayBaseline,
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
  /** Money set aside right now, in dollars, which no order may reach. */
  locked: number;
  /** How it is set: a fixed sum, or a share of the wallet. One or the other. */
  lockedUsd: number;
  lockedPct: number;
  onLocked: (usd: number, pct: number) => void;
  /** The day's goal, whose number is edited here rather than in settings. */
  day: DayGoal | null;
  onDayBaseline: (amount: number) => void;
  /** USDT held off the venue, where profit is withdrawn to. */
  savings: number;
  savingsAddress: string;
  onSavingsAddress: (address: string) => void;
  onClose: () => void;
}) {
  const [span, setSpan] = useState(2);

  /**
   * Whether the reserve is being said as a share rather than as a sum.
   *
   * Seeded from what is actually set — a share, where there is one, is what
   * the engine goes by — but held here afterwards, because switching to the
   * empty one has to show its empty field. The sheet is mounted fresh each
   * time it is opened, so the seed is never stale.
   */
  const [inPct, setInPct] = useState(lockedPct > 0);

  /**
   * What is being typed, in each unit, held here and nowhere else.
   *
   * Seeded from what is stored when the sheet opens and then never written to
   * again from the outside. This is the whole of "it must not reset itself":
   * the balance is re-read every half minute and the reserve travels with it,
   * so a field that mirrored the props would blank or jump under the thumb
   * every time a reading landed — and a number typed and not yet saved would
   * be gone.
   *
   * Two drafts rather than one, because twenty dollars and twenty percent are
   * different amounts. Switching the unit shows the other draft; it writes
   * nothing. It used to write immediately, which meant tapping % with no
   * share set cleared the sum that was set — the reserve wiping itself on a
   * tap that was only meant to change the unit.
   */
  const [usdDraft, setUsdDraft] = useState(lockedUsd > 0 ? String(lockedUsd) : '');
  const [pctDraft, setPctDraft] = useState(
    lockedPct > 0 ? String(Math.round(lockedPct * 100)) : '',
  );
  const draft = inPct ? pctDraft : usdDraft;

  /** What the draft would set, in the units the engine keeps. */
  const wanted = (() => {
    const value = Number(draft.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) return { usd: 0, pct: 0 };
    return inPct
      ? { usd: 0, pct: Math.min(1, value / 100) }
      : { usd: value, pct: 0 };
  })();

  /** Whether saving would change anything, which is what the button says. */
  const dirty =
    Math.abs(wanted.usd - lockedUsd) > 1e-9 ||
    Math.abs(wanted.pct - lockedPct) > 1e-9;

  const save = () => onLocked(wanted.usd, wanted.pct);

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
              <>
                <div>
                  <span className="muted">заблокировано</span>
                  <b>{usd(locked)}</b>
                </div>
                <div>
                  <span className="muted">в торговле</span>
                  <b>{free === null ? '—' : usd(free)}</b>
                </div>
              </>
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
        <div className="lockrow">
          <label className="field ballock">
            <span>заблокировано</span>
            <input
              type="number"
              inputMode="decimal"
              min={0}
              max={inPct ? 100 : undefined}
              step={inPct ? '5' : '1'}
              placeholder="0"
              value={draft}
              onChange={(e) =>
                inPct
                  ? setPctDraft(e.target.value)
                  : setUsdDraft(e.target.value)
              }
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.currentTarget.blur();
                  save();
                }
              }}
            />
          </label>
          {/*
            Which of the two the number is, and the button that applies it.

            Saving is its own tap rather than something that happens when the
            field loses focus: a reserve is the one number here that must be
            exactly what was meant, and "it applied when I touched something
            else" is not a thing anyone can rely on. The button says whether
            there is anything to apply.
          */}
          <div className="pcts lockmode">
            <button
              className={!inPct ? 'on' : undefined}
              onClick={() => setInPct(false)}
            >
              $
            </button>
            <button
              className={inPct ? 'on' : undefined}
              onClick={() => setInPct(true)}
            >
              %
            </button>
          </div>
          <button
            className={`locksave${dirty ? ' on' : ''}`}
            disabled={!dirty}
            onClick={save}
          >
            {dirty ? 'Сохранить' : 'Сохранено'}
          </button>
        </div>
        {/*
          The day's goal, which is one number: what the day is counted from.
          The target is ten times it and the stop follows it, so the number
          belongs here, next to the balance it is measured against — settings
          only says whether the stop is armed.

          Changing it starts the day again from the new figure: a goal moved
          up is a day that is no longer over, and the block lifts with it.
        */}
        <div className="lockrow">
          <label className="field ballock">
            <span>цель дня, от $</span>
            <input
              type="number"
              inputMode="decimal"
              min={0}
              step="1"
              placeholder={balance != null ? balance.toFixed(2) : '0'}
              defaultValue={day != null ? String(day.baseline) : ''}
              onBlur={(e) => {
                const value = Number(e.target.value.replace(',', '.'));
                if (Number.isFinite(value) && value > 0) onDayBaseline(value);
              }}
            />
          </label>
          <div className="balgoal">
            <span className="muted">×{DAY_MULTIPLE}</span>
            <b>{day != null ? usd(dayTarget(day)) : '—'}</b>
          </div>
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
