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
import { PolyBot } from '../native/polybot';
import { GOAL_GAIN, WITHDRAW_SHARE, goalProgress, type GoalState } from '../core/goal';

const W = 320;
const H = 132;

/**
 * Taking money off the venue, to an address of your own.
 *
 * One transfer of USDC on Polygon, signed by the app with the key it already
 * holds. It lands on Polygon at the same address — the wallet has to be
 * switched to that network to see it — because Polygon and BSC are different
 * chains and no transaction crosses between them.
 *
 * The two things that stop it are worth saying before the button is pressed
 * rather than after: collateral sitting on a Polymarket proxy instead of on
 * the key's own address, and no POL to pay for the block. So the state is read
 * when the panel opens and the button says which it is.
 */
function Withdraw({ address }: { address: string }) {
  const [info, setInfo] = useState<Awaited<
    ReturnType<typeof PolyBot.withdrawInfo>
  > | null>(null);
  const [amount, setAmount] = useState('0.05');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [hash, setHash] = useState<string | null>(null);

  const usdAmount = Number(amount.replace(',', '.'));
  const ready =
    info != null &&
    info.gasReady &&
    info.sendable > 0 &&
    Number.isFinite(usdAmount) &&
    usdAmount > 0 &&
    usdAmount <= info.sendable + 1e-9;

  const look = () => {
    setNote(null);
    void PolyBot.withdrawInfo()
      .then(setInfo)
      .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
  };

  const send = () => {
    if (!ready) return;
    setBusy(true);
    setNote(null);
    setHash(null);
    void PolyBot.withdraw({ to: address, usd: usdAmount })
      .then((r) => {
        setHash(r.hash);
        look();
      })
      .catch((e) => setNote(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  };

  if (!address) return null;

  return (
    <div className="withdraw">
      {info == null ? (
        <button className="ghost wide" onClick={look}>
          вывести на этот кошелёк
        </button>
      ) : (
        <>
          <div className="withdraw-state muted">
            на ключе {usd(info.sendable)} · газ{' '}
            <b className={info.gasReady ? 'up' : 'down'}>{info.pol.toFixed(3)} POL</b>
            {info.proxy && info.sendable <= 0 && ' · деньги на прокси Polymarket'}
          </div>

          <div className="withdraw-row">
            <input
              type="text"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
            <button className="primary" disabled={!ready || busy} onClick={send}>
              {busy ? 'шлю…' : `вывести ${usd(usdAmount || 0)}`}
            </button>
          </div>

          <div className="withdraw-note muted">
            USDC в сети Polygon на {address.slice(0, 6)}…{address.slice(-4)} — в
            кошельке переключите сеть на Polygon
          </div>
        </>
      )}

      {hash && (
        <div className="banner info withdraw-hash">
          отправлено · {hash.slice(0, 10)}…{hash.slice(-6)}
        </div>
      )}
      {note && <div className="banner warn">{note}</div>}
    </div>
  );
}

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
  savings,
  savingsAddress,
  onSavingsAddress,
  goal,
  onRestart,
  onClose,
}: {
  history: BalancePoint[];
  adjustments: Adjustment[];
  balance: number | null;
  /** USDT held off the venue, where profit is withdrawn to. */
  savings: number;
  savingsAddress: string;
  onSavingsAddress: (address: string) => void;
  goal: GoalState | null;
  /** The money is out: record how much, and start the run again. */
  onRestart: (withdrawn: number) => void;
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
          it does not read as losing it.
        */}
        {savings > 0 && (
          <div className="balparts">
            <div>
              <span className="muted">на бирже</span>
              <b>{balance === null ? '—' : usd(balance)}</b>
            </div>
            <div>
              <span className="muted">USDT BEP-20</span>
              <b>{usd(savings)}</b>
            </div>
          </div>
        )}

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

        <Withdraw address={savingsAddress} />

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

        {goal && balance != null && <GoalCard goal={goal} balance={balance} onRestart={onRestart} />}

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

/**
 * How the run is doing against its goal, and what to take out when it gets
 * there. The figure is the point — "withdraw some" is advice nobody acts on,
 * "вывести 8.03 $" is a decision already made.
 */
function GoalCard({
  goal,
  balance,
  onRestart,
}: {
  goal: GoalState;
  balance: number;
  onRestart: (withdrawn: number) => void;
}) {
  const p = goalProgress(goal, balance);
  const share = Math.round((p.gain / GOAL_GAIN) * 100);
  const [confirming, setConfirming] = useState(false);
  const [amount, setAmount] = useState('');

  return (
    <div className={`goal${p.reached ? ' goal-hit' : ''}`}>
      <div className="goal-head">
        <span>
          Цель ×2 от {usd(p.baseline)}
          {goal.rounds > 0 && <span className="muted"> · круг {goal.rounds + 1}</span>}
        </span>
        <span className={p.profit >= 0 ? 'up' : 'down'}>{signedUsd(p.profit)}</span>
      </div>

      <div className="bar">
        <i style={{ width: `${Math.max(0, Math.min(100, share))}%` }} />
      </div>

      {p.reached ? (
        <>
          <div className="goal-call">
            Баланс удвоился. Вывести <b>{usd(p.suggested)}</b> —{' '}
            {Math.round(WITHDRAW_SHARE * 100)}% профита. Останется{' '}
            {usd(p.leftAfter)}, всё ещё больше, чем в начале круга.
          </div>
          {confirming ? (
            <div className="draftrow">
              {/*
                The figure matters: it is what gets carried back into the
                balance line, so a withdrawal reads as a step aside rather than
                a loss. Pre-filled with the suggestion, editable because the
                amount that actually left the wallet is the one that counts.
              */}
              <label className="mini">
                <span>вывел, $</span>
                <input
                  type="number"
                  inputMode="decimal"
                  autoFocus
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                />
              </label>
              <button
                className="primary compact"
                onClick={() => {
                  onRestart(Math.max(0, Number(amount.replace(',', '.')) || 0));
                  setConfirming(false);
                }}
              >
                Записать
              </button>
            </div>
          ) : (
            <button
              className="ghost compact"
              onClick={() => {
                setAmount(p.suggested.toFixed(2));
                setConfirming(true);
              }}
            >
              Вывел — считать заново
            </button>
          )}
        </>
      ) : (
        <div className="muted goal-call">
          До цели {usd(p.remaining)} — это {usd(p.target)} на балансе. Тогда
          напомню вывести {Math.round(WITHDRAW_SHARE * 100)}% профита.{' '}
          {/* Money can leave the wallet without the goal being reached, and a
              baseline that no longer matches reality measures nothing. */}
          <button className="linkbtn" onClick={() => onRestart(0)}>
            считать от текущего
          </button>
        </div>
      )}
    </div>
  );
}

/**
 * What is on the exchange and not for trading.
 *
 * A share off the top that no order can reach, plus whatever is set aside for a
 * particular strategy. The free figure is the one that matters — it is what the
 * buy buttons are allowed to spend — so it is the one in bold.
 */
