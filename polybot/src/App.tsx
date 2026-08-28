import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccountConfig } from './core/account';
import {
  loadAccount,
  loadSavingsAddress,
  saveSavingsAddress,
} from './core/storage';
import { PolyBot } from './native/polybot';
import { Manual } from './ui/Manual';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';
import { BalanceSheet } from './ui/BalanceSheet';
import {
  appendAdjustment,
  appendBalance,
  loadAdjustments,
  loadBalanceHistory,
  saveAdjustments,
  saveBalanceHistory,
  type Adjustment,
  type BalancePoint,
} from './core/balance';
import {
  WITHDRAW_SHARE,
  goalProgress,
  loadGoal,
  restartRun,
  saveGoal,
  shouldRemind,
  snoozeGoal,
  startRun,
  type GoalState,
} from './core/goal';
import { usd } from './core/money';
import {
  DAY_MULTIPLE,
  dayReached,
  dayTarget,
  isLocked,
  loadDayGoal,
  markHit,
  needsBaseline,
  saveDayGoal,
  startDay,
  untilMidnightText,
  type DayGoal,
} from './core/day';

type Phase = 'loading' | 'setup' | 'ready';

/**
 * Nothing at startup may hang the app.
 *
 * Every one of these calls crosses to the native side and from there to the
 * exchange; a slow network turned the splash screen into a dead end with no way
 * out. A call that has not answered in time is treated as not having answered —
 * the service carries on regardless, and the screen stops waiting for it.
 */
function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race([
    promise.catch(() => null),
    new Promise<null>((resolve) => setTimeout(() => resolve(null), ms)),
  ]);
}

const STARTUP_MS = 12_000;
export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [account, setAccount] = useState<AccountConfig | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  /** USDT held off the venue, at the address profit is withdrawn to. */
  const [savings, setSavings] = useState(0);
  // Read inside the balance poller, which must not restart on every reading.
  const savingsRef = useRef(0);
  const [savingsAddress, setSavingsAddress] = useState('');

  /**
   * What the run is worth: the collateral on the venue plus what has been
   * taken off it.
   *
   * The desk sizes orders from the venue balance alone — that is the only
   * money it can spend — but the goal, the day's stop and the balance line are
   * about the run, and the run keeps what it has withdrawn. A line that drops
   * by the amount taken out reads a good week as a bad one.
   */
  const worth = balance == null ? null : balance + savings;
  const [showBalance, setShowBalance] = useState(false);
  const [balanceHistory, setBalanceHistory] = useState<BalancePoint[]>([]);
  const [goal, setGoal] = useState<GoalState | null>(null);
  const [adjustments, setAdjustments] = useState<Adjustment[]>([]);
  /** What the open round makes if it goes our way, reported by the desk. */
  /** The startup connect did not answer in time; the desk opened anyway. */
  const [slowStart, setSlowStart] = useState(false);
  const [day, setDay] = useState<DayGoal | null>(null);
  const [dayAsked, setDayAsked] = useState(false);
  const [dayInput, setDayInput] = useState('');

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const acct = await loadAccount();
      if (cancelled) return;
      setAccount(acct);

      // The service outlives the UI, so a bot started earlier is still trading;
      // rejoin it rather than reconnecting over a running engine.
      const state = await withTimeout(PolyBot.getState(), 4_000);
      if (!cancelled && state?.serviceAlive && acct) {
        setPhase('ready');
        return;
      }
      if (!acct) {
        if (!cancelled) setPhase('setup');
        return;
      }

      // The key is sealed by the Android Keystore, so it opens without anything
      // from the user. A vault that cannot be opened — reinstalled app, cleared
      // keystore — means the key is genuinely gone and has to be entered again.
      const vault = await withTimeout(PolyBot.vaultLoad(), 4_000);
      if (cancelled) return;
      if (!vault?.privateKey) {
        setPhase('setup');
        return;
      }

      // The key is here, so the desk opens either way. A connect that is still
      // in flight finishes on its own thread and the desk starts working when
      // it lands; a connect that failed says so on the first order rather than
      // by never showing the screen at all.
      const connected = await withTimeout(
        PolyBot.connect({
          privateKey: vault.privateKey,
          funderAddress: acct.funderAddress,
          signatureType: Number(acct.signatureType),
        }),
        STARTUP_MS,
      );
      if (cancelled) return;
      if (!connected) setSlowStart(true);
      setPhase('ready');
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * The foreground service, from the moment the desk is usable.
   *
   * It used to start only when the standing sell rule was switched on, which
   * meant the notification — the only view of the account there is with the
   * app closed — was missing for anyone trading by hand. It also asks for the
   * notification permission, which is why it waits for the wallet: there is
   * nothing to show before that.
   */
  useEffect(() => {
    if (phase !== 'ready') return;
    let alive = true;
    let timer = 0;

    // The desk opens before the wallet has finished connecting on a slow
    // start, and the service refuses to start without it — so this asks again
    // until it takes, and then stops asking.
    const arm = () => {
      void PolyBot.start()
        .then(() => {
          if (alive) window.clearInterval(timer);
        })
        .catch(() => {
          // Not connected yet, or the notification was refused. Either way the
          // desk works; this only keeps trying for the notification.
        });
    };

    arm();
    timer = window.setInterval(arm, 8_000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [phase]);

  // Wallet balance in the header. It only moves when an order fills, so a slow
  // poll is enough — and it must not run before the engine holds credentials.
  useEffect(() => {
    if (phase !== 'ready') return;
    let cancelled = false;

    const read = () => {
      void PolyBot.getBalance()
        .then((r) => {
          if (cancelled) return;
          setBalance(r.usdc);
          // Every reading is a point on the line. Repeats are dropped inside
          // appendBalance, which returns the same array — so an unchanged
          // balance costs neither a render nor a write.
          setBalanceHistory((current) => {
            const next = appendBalance(current, r.usdc + savingsRef.current);
            if (next !== current) void saveBalanceHistory(next);
            return next;
          });
        })
        .catch(() => {
          // Offline or not connected yet; keep the last known figure.
        });
    };

    read();
    const timer = window.setInterval(read, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [phase]);

  /**
   * And the pocket the profit goes to.
   *
   * Read on the same slow beat, off the public chain and without a key: it
   * only moves when money is withdrawn, and the whole point of watching it is
   * that a withdrawal is not a loss.
   */
  useEffect(() => {
    if (!savingsAddress) {
      savingsRef.current = 0;
      setSavings(0);
      return;
    }
    let cancelled = false;
    const read = () => {
      void PolyBot.chainBalance({ address: savingsAddress })
        .then((r) => {
          if (cancelled) return;
          // Both chains at that address: USDT taken out by hand on BSC, and
          // USDC the desk sent itself on Polygon. A total that counted only
          // the first would dip by the amount withdrawn the moment it landed.
          savingsRef.current = r.total;
          setSavings(r.total);
        })
        .catch(() => {
          // A node that will not answer is not a reason to forget the figure.
        });
    };
    read();
    const timer = window.setInterval(read, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [savingsAddress]);

  useEffect(() => {
    void loadSavingsAddress().then(setSavingsAddress);
    void loadBalanceHistory().then(setBalanceHistory);
    void loadAdjustments().then(setAdjustments);
    void loadGoal().then(setGoal);
    void loadDayGoal().then((d) => {
      setDay(d);
      setDayAsked(true);
    });
  }, []);

  // Ten times the day's opening balance ends the day: the stop goes on and
  // stays on until the clock rolls past midnight.
  useEffect(() => {
    if (!day || worth == null) return;
    if (day.hitAt != null || !dayReached(day, worth)) return;
    const hit = markHit(day);
    setDay(hit);
    void saveDayGoal(hit);
  }, [day, worth]);

  // The run starts at the first balance the app ever sees. Doing it here rather
  // than at connect time means a reinstall picks up where the money is, not at
  // zero.
  useEffect(() => {
    if (goal != null || worth == null || worth <= 0) return;
    const started = startRun(worth);
    setGoal(started);
    void saveGoal(started);
  }, [goal, worth]);

  const putOff = useCallback(() => {
    if (!goal || worth == null) return;
    const next = snoozeGoal(goal, worth);
    setGoal(next);
    void saveGoal(next);
  }, [goal, worth]);

  /**
   * The money is out: the next run starts from what is left, and the amount
   * withdrawn is remembered so the balance line does not read it as a loss.
   */
  const restart = useCallback(
    (withdrawn: number) => {
      if (!goal || worth == null) return;
      const next = restartRun(goal, worth);
      setGoal(next);
      void saveGoal(next);

      if (withdrawn > 0) {
        setAdjustments((current) => {
          const list = appendAdjustment(current, withdrawn, 'withdraw');
          void saveAdjustments(list);
          return list;
        });
      }
    },
    [goal, worth],
  );

  const askDay = dayAsked && needsBaseline(day) && worth != null;
  const locked = isLocked(day);

  const setDayBaseline = useCallback((amount: number) => {
    if (!Number.isFinite(amount) || amount <= 0) return;
    const next = startDay(amount);
    setDay(next);
    void saveDayGoal(next);
  }, []);

  /**
   * The deposit is cash plus what is in the market, so the locked share does
   * not shrink as the cash is spent.
   */

  /** A quarter of the wallet per five-minute round. */

  const remind = worth != null && shouldRemind(goal, worth);
  const progress = goal && worth != null ? goalProgress(goal, worth) : null;

  const onSetupDone = useCallback((acct: AccountConfig) => {
    setAccount(acct);
    setPhase('ready');
  }, []);

  const onForget = useCallback(() => {
    void PolyBot.stop().catch(() => {});
    setAccount(null);
    setPhase('setup');
  }, []);

  if (phase === 'loading') {
    return (
      <div className="app">
        <div className="center muted">Загрузка…</div>
      </div>
    );
  }

  if (phase === 'setup') {
    return <Setup onDone={onSetupDone} />;
  }

  return (
    <div className="app">
      {showBalance && (
        <BalanceSheet
          history={balanceHistory}
          adjustments={adjustments}
          balance={balance}
          savings={savings}
          savingsAddress={savingsAddress}
          onSavingsAddress={(next) => {
            setSavingsAddress(next.trim());
            void saveSavingsAddress(next);
          }}
          goal={goal}
          onRestart={(withdrawn) => {
            restart(withdrawn);
            setShowBalance(false);
          }}
          onClose={() => setShowBalance(false)}
        />
      )}
      {askDay && (
        <div className="sheet-scrim">
          <div className="sheet">
            <div className="sheet-head">
              <h2>Цель дня ×{DAY_MULTIPLE}</h2>
            </div>
            <div className="bigfield">
              <span>считаем от, $</span>
              <div className="bigrow">
                <input
                  type="number"
                  inputMode="decimal"
                  autoFocus
                  placeholder={worth?.toFixed(2) ?? ''}
                  value={dayInput}
                  onChange={(e) => setDayInput(e.target.value)}
                />
              </div>
            </div>
            <div className="row">
              <span className="label">Цель</span>
              <span className="value">
                {usd(
                  (Number(dayInput.replace(',', '.')) || worth || 0) * DAY_MULTIPLE,
                )}
              </span>
            </div>
            <button
              className="primary"
              style={{ marginTop: 12 }}
              onClick={() =>
                setDayBaseline(Number(dayInput.replace(',', '.')) || worth || 0)
              }
            >
              Начать день
            </button>
          </div>
        </div>
      )}

      {slowStart && (
        <div className="banner warn">
          Биржа не ответила за {STARTUP_MS / 1000} с — экран открыт, подключение
          продолжается. Если ордера не проходят, переподключите кошелёк в
          настройках.
        </div>
      )}

      {locked && day && (
        <div className="banner lockbanner">
          <b>Цель дня ×{DAY_MULTIPLE} взята.</b> {usd(dayTarget(day))} от{' '}
          {usd(day.baseline)}. Покупки заблокированы ещё {untilMidnightText()} —
          до полуночи. Продажи и правило выхода работают.
        </div>
      )}

      {/*
        The one moment a run is most worth protecting is the one it is least
        likely to be protected in, so the app says it out loud — with the figure
        already worked out, because "take some off the table" is advice nobody
        acts on and "вывести 8.03 $" is a decision already made.
      */}
      {remind && progress && (
        <div className="banner goalbanner">
          <div>
            Баланс удвоился: <b>{usd(progress.balance)}</b> от{' '}
            {usd(progress.baseline)}. Пора вывести{' '}
            <b>{usd(progress.suggested)}</b> —{' '}
            {Math.round(WITHDRAW_SHARE * 100)}% профита.
          </div>
          <div className="goalbanner-acts">
            <button className="ghost compact" onClick={() => setShowBalance(true)}>
              Подробнее
            </button>
            <button className="ghost compact" onClick={putOff}>
              Позже
            </button>
          </div>
        </div>
      )}

      {/*
        One screen and one settings button. There was nothing to switch between
        — the desk is the app — and a tab bar for it cost a permanent strip of
        the screen to say so. Everything that used to live under "Настройки"
        now folds in under the desk's own settings, behind the same gear.
      */}
      <div className="scroll">
        <Manual
          onOpenBalance={() => setShowBalance(true)}
          savings={savings}
          locked={locked}
          appSettings={
            <SettingsScreen account={account} onForget={onForget} />
          }
        />
      </div>
    </div>
  );
}


