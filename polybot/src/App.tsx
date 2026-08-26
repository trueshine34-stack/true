import { useCallback, useEffect, useState } from 'react';
import type { AccountConfig } from './core/account';
import { DEFAULT_SETTINGS, type StrategySettings } from './core/settings';
import { loadAccount, loadSettings, saveSettings } from './core/storage';
import {
  PolyBot,
  type CounterState,
  type SignalState,
} from './native/polybot';
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
import { signedUsd, usd } from './core/money';
import {
  loadContainer,
  saveContainer,
  splitFor,
  withBots,
  type Container,
} from './core/container';
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
  const [settings, setSettings] = useState<StrategySettings>(DEFAULT_SETTINGS);
  const [account, setAccount] = useState<AccountConfig | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [showBalance, setShowBalance] = useState(false);
  const [balanceHistory, setBalanceHistory] = useState<BalancePoint[]>([]);
  const [goal, setGoal] = useState<GoalState | null>(null);
  const [adjustments, setAdjustments] = useState<Adjustment[]>([]);
  /** What the open round makes if it goes our way, reported by the desk. */
  const [potential, setPotential] = useState(0);
  /** The startup connect did not answer in time; the desk opened anyway. */
  const [slowStart, setSlowStart] = useState(false);
  const [day, setDay] = useState<DayGoal | null>(null);
  const [dayAsked, setDayAsked] = useState(false);
  const [dayInput, setDayInput] = useState('');
  const [container, setContainer] = useState<Container | null>(null);
  /** The bots' own money, which is in this wallet and is not the desk's. */
  const [counter, setCounter] = useState<CounterState | null>(null);
  const [signal, setSignal] = useState<SignalState | null>(null);
  /** What the desk says is already in the market, for the container's split. */
  const [committed, setCommitted] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [stored, acct] = await Promise.all([loadSettings(), loadAccount()]);
      if (cancelled) return;
      setSettings(stored);
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
          settings: stored,
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
            const next = appendBalance(current, r.usdc);
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

  useEffect(() => {
    void loadBalanceHistory().then(setBalanceHistory);
    void loadAdjustments().then(setAdjustments);
    void loadGoal().then(setGoal);
    void loadContainer().then(setContainer);
    void loadDayGoal().then((d) => {
      setDay(d);
      setDayAsked(true);
    });
  }, []);

  // Ten times the day's opening balance ends the day: the stop goes on and
  // stays on until the clock rolls past midnight.
  useEffect(() => {
    if (!day || balance == null) return;
    if (day.hitAt != null || !dayReached(day, balance)) return;
    const hit = markHit(day);
    setDay(hit);
    void saveDayGoal(hit);
  }, [day, balance]);

  // The run starts at the first balance the app ever sees. Doing it here rather
  // than at connect time means a reinstall picks up where the money is, not at
  // zero.
  useEffect(() => {
    if (goal != null || balance == null || balance <= 0) return;
    const started = startRun(balance);
    setGoal(started);
    void saveGoal(started);
  }, [goal, balance]);

  const putOff = useCallback(() => {
    if (!goal || balance == null) return;
    const next = snoozeGoal(goal, balance);
    setGoal(next);
    void saveGoal(next);
  }, [goal, balance]);

  /**
   * The money is out: the next run starts from what is left, and the amount
   * withdrawn is remembered so the balance line does not read it as a loss.
   */
  const restart = useCallback(
    (withdrawn: number) => {
      if (!goal || balance == null) return;
      const next = restartRun(goal, balance);
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
    [goal, balance],
  );

  const askDay = dayAsked && needsBaseline(day) && balance != null;
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
  // Both small bots run on their own money in the service; the panel only
  // reads them, and it reads them here because the container's arithmetic
  // needs them as much as the desk does.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.counterState()
        .then((s) => {
          if (!cancelled) setCounter(s);
        })
        .catch(() => {});
      void PolyBot.signalState()
        .then((s) => {
          if (!cancelled) setSignal(s);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  // One definition of what is locked, shared by the header, the guard and the
  // desk. A bot's stake is a reserve like any other; it is merged in here
  // rather than stored, because the bot owns it and it moves with what it
  // makes — two copies of the same dollar would sooner or later disagree.
  const held = withBots(container ?? { corePct: 0.3, reserves: [] }, [
    { name: 'Контр-бот', usd: counter?.enabled ? counter.cash : 0 },
    { name: 'Индикаторы', usd: signal?.enabled ? signal.cash : 0 },
  ]);
  const split = splitFor(held, (balance ?? 0) + committed);

  const applyContainer = useCallback((next: Container) => {
    setContainer(next);
    void saveContainer(next);
  }, []);

  /** A quarter of the wallet per five-minute round. */
  const roundGoal = balance != null ? balance * 0.25 : 0;

  const remind = balance != null && shouldRemind(goal, balance);
  const progress = goal && balance != null ? goalProgress(goal, balance) : null;

  const applySettings = useCallback((next: StrategySettings) => {
    setSettings(next);
    void saveSettings(next);
    void PolyBot.updateSettings({ settings: next }).catch(() => {
      // Not connected yet; connect() carries the settings across.
    });
  }, []);

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
    return <Setup settings={settings} onDone={onSetupDone} />;
  }

  return (
    <div className="app">
      {showBalance && (
        <BalanceSheet
          history={balanceHistory}
          adjustments={adjustments}
          balance={balance}
          goal={goal}
          container={container ?? { corePct: 0.3, reserves: [] }}
          split={split}
          onContainer={applyContainer}
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
                  placeholder={balance?.toFixed(2) ?? ''}
                  value={dayInput}
                  onChange={(e) => setDayInput(e.target.value)}
                />
              </div>
            </div>
            <div className="row">
              <span className="label">Цель</span>
              <span className="value">
                {usd(
                  (Number(dayInput.replace(',', '.')) || balance || 0) * DAY_MULTIPLE,
                )}
              </span>
            </div>
            <button
              className="primary"
              style={{ marginTop: 12 }}
              onClick={() =>
                setDayBaseline(Number(dayInput.replace(',', '.')) || balance || 0)
              }
            >
              Начать день
            </button>
          </div>
        </div>
      )}

      {/*
        The bots' stakes are real money in this wallet. When they and the core
        add up to more than the deposit, the desk has nothing to trade with —
        which shows up as "container full" on every buy and is otherwise a
        mystery. Say it once, plainly, where the money is.
      */}
      {split.free <= 0 && split.bots > 0 && (
        <div className="banner warn">
          Контейнеры ботов ({usd(split.bots)}) и запас ({usd(split.core)}) заняли
          весь депозит — руками торговать нечем. Уменьшите суммы ботов или
          пополните баланс.
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

      <div className="topbar">
        <button className="headnum" onClick={() => setShowBalance(true)}>
          <b>{balance === null ? '—' : balance.toFixed(2)}</b>
          <s>баланс</s>
        </button>
        <button className="headnum" onClick={() => setShowBalance(true)}>
          <b className="muted">{split.locked > 0 ? split.locked.toFixed(2) : '—'}</b>
          <s>в контейнере</s>
        </button>
        <div className="headnum">
          <b className="muted">{roundGoal > 0 ? roundGoal.toFixed(2) : '—'}</b>
          <s>цель 25%</s>
        </div>
        {/* Green once the round could make the goal, amber while it could not. */}
        <div className="headnum">
          <b className={potential >= roundGoal && roundGoal > 0 ? 'up' : 'warn'}>
            {potential > 0 ? signedUsd(potential).replace(' $', '') : '—'}
          </b>
          <s>если сыграет</s>
        </div>
      </div>

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
          onSummary={setPotential}
          onCommitted={setCommitted}
          containerLocked={split.locked}
          locked={locked}
          counter={counter}
          signal={signal}
          onCounter={setCounter}
          onSignal={setSignal}
          appSettings={
            <SettingsScreen
              settings={settings}
              account={account}
              onChange={applySettings}
              onForget={onForget}
            />
          }
        />
      </div>
    </div>
  );
}


