import { useCallback, useEffect, useRef, useState } from 'react';
import type { AccountConfig } from './core/account';
import { loadAccount, loadSavingsAddress } from './core/storage';
import { PolyBot, type WalletSlot } from './native/polybot';
import { Manual } from './ui/Manual';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';
import { BalanceSheet } from './ui/BalanceSheet';
import {
  appendBalance,
  shouldRecord,
  loadAdjustments,
  loadBalanceHistory,
  saveBalanceHistory,
  type Adjustment,
  type BalancePoint,
} from './core/balance';
import {
  WITHDRAW_SHARE,
  goalProgress,
  loadGoal,
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
  buyingStopped,
  loadDayGoal,
  loadDayLock,
  saveDayLock,
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

/**
 * How far sideways a swipe has to go to be a wallet switch.
 *
 * The desk is a tall scrolling column, so a gesture that triggered easily would
 * change accounts every time the charts were read. Sixty pixels and mostly
 * horizontal is a movement nobody makes by accident.
 */
const SWIPE_MIN = 60;
export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [account, setAccount] = useState<AccountConfig | null>(null);
  /*
    The wallets, and which one the screen is on.

    Both trade at once — each has its own key, its own order log and its own
    sell rule running in the service — so this is only about what is being
    looked at. Which makes the frame colour the important part of it: the one
    thing a second account must never do is let you act on it believing it was
    the first, and a label is read when you go looking for it, which is not when
    that mistake happens.
  */
  const [wallets, setWallets] = useState<WalletSlot[]>([]);
  const [slot, setSlot] = useState(0);
  /** The empty chair being filled, when a second wallet is being connected. */
  const [filling, setFilling] = useState<number | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  /** USDT held off the venue, at the address profit is withdrawn to. */
  const [savings, setSavings] = useState(0);
  // Read inside the balance poller, which must not restart on every reading.
  const savingsRef = useRef(0);
  const [savingsAddress, setSavingsAddress] = useState('');
  /**
   * Money in the wallet that nothing may spend.
   *
   * Set on the balance sheet and enforced natively, so [balance] here is
   * already what is left after it. Kept in state only to be shown and edited.
   */
  const [reserve, setReserve] = useState(0);
  /** How that reserve is set: a fixed sum, or a share of the wallet. */
  const [reserveUsd, setReserveUsd] = useState(0);
  const [reservePct, setReservePct] = useState(0);
  /** The wallet before the reserve, which is what the balance line is of. */
  const [wallet, setWallet] = useState<number | null>(null);

  /** One reading of the wallet, unpacked into the four things it says. */
  const readBalance = useCallback(
    (r: { usdc: number; wallet?: number; locked?: number;
          lockedUsd?: number; lockedPct?: number }) => {
      setBalance(r.usdc);
      setWallet(r.wallet ?? r.usdc);
      setReserve(r.locked ?? 0);
      setReserveUsd(r.lockedUsd ?? 0);
      setReservePct(r.lockedPct ?? 0);
    },
    [],
  );

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
  /**
   * Whether the goal is allowed to stop buying.
   *
   * On unless it was turned off, and read before the first balance arrives —
   * so a day that was already won never flashes its block onto a screen whose
   * owner switched the block off yesterday.
   */
  const [dayLock, setDayLock] = useState(true);
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
        .then(async (r) => {
          if (cancelled) return;
          readBalance(r);

          // Whether any of the money is currently shares rather than cash.
          // A reading taken while it is would put the cost of the position on
          // the line as a loss, which is what made it read as a permanent
          // drawdown: on a five-minute market something is nearly always open.
          let holding = false;
          try {
            const held = await PolyBot.getPositions();
            holding = (held.positions ?? []).some((p) => p.size > 0.01);
          } catch {
            // Cannot tell: treat it as held and wait for a clearer moment.
            holding = true;
          }
          if (cancelled) return;

          setBalanceHistory((current) => {
            if (!shouldRecord(current, Date.now(), holding)) return current;
            // The line is the run's own money, all of it: locking part of it
            // away is a decision about what may be traded, not a withdrawal,
            // and a line that stepped down by it would read as a loss.
            const next = appendBalance(
              current,
              (r.wallet ?? r.usdc) + savingsRef.current,
            );
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
  }, [phase, readBalance]);

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
    void loadDayLock().then(setDayLock);
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


  // The sheet on the first trade of the day is part of the stop: it is asking
  // for the number the stop is measured from. With the stop off there is
  // nothing to measure, so it does not open — the goal is then edited in the
  // balance sheet like any other figure.
  const askDay = dayLock && dayAsked && needsBaseline(day) && worth != null;
  const locked = buyingStopped(day, dayLock);

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

  /** Re-read the slots, which is how a connect or a rename becomes visible. */
  const readWallets = useCallback(async () => {
    const list = await PolyBot.walletList().catch(() => null);
    if (!list) return;
    setWallets(list.slots);
    setSlot(list.current);
  }, []);

  useEffect(() => {
    if (phase !== 'ready') return;
    void readWallets();
  }, [phase, readWallets]);

  /**
   * Move the screen to another wallet.
   *
   * The native side keeps both engines running; what has to happen here is the
   * one thing it cannot do for us — a slot that has a key but has never been
   * connected in this process has to be handed its key, or the desk would show
   * an account that cannot sign.
   */
  const goToWallet = useCallback(
    async (next: number) => {
      if (next === slot || next < 0 || next >= wallets.length) return;
      const target = wallets[next];
      if (!target?.connected) {
        // An empty chair is an invitation rather than a dead end.
        await PolyBot.walletSelect({ slot: next }).catch(() => {});
        setSlot(next);
        setFilling(next);
        return;
      }
      await PolyBot.walletSelect({ slot: next }).catch(() => {});
      setSlot(next);
      const acct = await loadAccount(next);
      if (acct) setAccount(acct);
      const vault = await PolyBot.vaultLoad({ slot: next }).catch(() => null);
      if (vault?.privateKey && acct) {
        await PolyBot.connect({
          privateKey: vault.privateKey,
          funderAddress: acct.funderAddress,
          signatureType: Number(acct.signatureType),
        }).catch(() => {});
      }
      void readWallets();
    },
    [slot, wallets, readWallets],
  );

  /*
    A swipe moves between them.

    Horizontal and decisive, or nothing: the desk is a tall scrolling column and
    a gesture that competed with that would cost a wallet switch every time you
    read the charts. So it has to be mostly sideways and long enough to be
    meant.
  */
  const swipe = useRef<{ x: number; y: number } | null>(null);
  const onSwipeStart = useCallback((e: React.TouchEvent) => {
    const touch = e.touches[0];
    /*
      Not where something else is already listening to the finger.

      The charts are dragged sideways to make room ahead of the price and
      pinched to zoom, which is the same gesture as this one and used far more
      often — so a drag along a chart was changing wallet. A slider is the same
      story. The rule is the plain one: a horizontal gesture belongs to the
      thing it started on, and only a swipe that started on none of them is
      about which account is on screen.
    */
    const on = (touch?.target as HTMLElement | undefined)?.closest?.(
      '.candles, .depthfold, input[type="range"], .balchart',
    );
    swipe.current =
      touch && !on ? { x: touch.clientX, y: touch.clientY } : null;
  }, []);
  const onSwipeEnd = useCallback(
    (e: React.TouchEvent) => {
      const from = swipe.current;
      swipe.current = null;
      const touch = e.changedTouches[0];
      if (!from || !touch) return;
      const dx = touch.clientX - from.x;
      const dy = touch.clientY - from.y;
      if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) < Math.abs(dy) * 1.6) return;
      void goToWallet(slot + (dx < 0 ? 1 : -1));
    },
    [goToWallet, slot],
  );

  const onSetupDone = useCallback(
    (acct: AccountConfig) => {
      setAccount(acct);
      setFilling(null);
      setPhase('ready');
      void readWallets();
    },
    [readWallets],
  );

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

  /* The empty chair, being filled. The desk is still there behind it. */
  if (filling != null) {
    return (
      <Setup
        slot={filling}
        onDone={onSetupDone}
        onCancel={() => {
          setFilling(null);
          void goToWallet(0);
        }}
      />
    );
  }

  const here = wallets[slot];

  return (
    <div
      className={`app${wallets.length > 1 ? ' framed' : ''}`}
      style={
        here ? ({ ['--wallet']: here.accent } as React.CSSProperties) : undefined
      }
      onTouchStart={onSwipeStart}
      onTouchEnd={onSwipeEnd}
    >
      {/*
        Which account this is, where the frame already said it in colour.

        The frame is what stops a trade going to the wrong wallet; this is for
        the second afterwards, when you want the name rather than the hue. It is
        only here at all once there is more than one, because with one wallet
        there is nothing to be confused with.
      */}
      {wallets.filter((w) => w.connected).length > 1 && (
        <div className="walletstrip">
          {wallets.map((w) => (
            <button
              key={w.index}
              className={w.index === slot ? 'on' : undefined}
              style={
                w.index === slot
                  ? ({ ['--wallet']: w.accent } as React.CSSProperties)
                  : undefined
              }
              onClick={() => void goToWallet(w.index)}
            >
              {w.name}
            </button>
          ))}
        </div>
      )}
      {showBalance && (
        <BalanceSheet
          history={balanceHistory}
          adjustments={adjustments}
          balance={wallet ?? balance}
          free={balance}
          locked={reserve}
          lockedUsd={reserveUsd}
          lockedPct={reservePct}
          onLocked={(usd, pct) => {
            const safeUsd = Number.isFinite(usd) && usd > 0 ? usd : 0;
            const safePct = Number.isFinite(pct) && pct > 0 ? Math.min(1, pct) : 0;
            setReserveUsd(safeUsd);
            setReservePct(safePct);
            void PolyBot.setLocked({ usd: safeUsd, pct: safePct })
              .then(() => PolyBot.getBalance())
              .then(readBalance)
              .catch(() => {});
          }}
          savings={savings}
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
          key={slot}
          slot={slot}
          onOpenBalance={() => setShowBalance(true)}
          savings={savings}
          locked={locked}
          appSettings={
            <SettingsScreen
              account={account}
              onForget={onForget}
              wallets={wallets}
              slot={slot}
              onGoWallet={(next) => void goToWallet(next)}
              onWalletsChanged={() => void readWallets()}
              dayLock={dayLock}
              onDayLock={(on) => {
                setDayLock(on);
                void saveDayLock(on);
              }}
              dayHit={isLocked(day)}
            />
          }
        />
      </div>
    </div>
  );
}


