import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  buyBarred,
  buyCeiling,
  DEFAULT_MANUAL_SETTINGS,
  exposureFor,
  DEFAULT_CLICK_SHARES,
  LIMIT_LADDER_COUNT,
  orderCost,
  sellableShares,
  bigPrice,
  openMark,
  stakeShares,
  openingSize,
  minShares,
  type Exposure,
  type ManualSettings,
} from '../core/manual';
import {
  pairOrders,
  realised,
  withLiveOrders,
  type TradeRow,
} from '../core/trades';
import {
  limitLadder,
  netSellPrice,
  positionPnl,
  potentialProfit,
  signedUsd,
  usd,
} from '../core/money';
import { loadManualSettings, saveManualSettings } from '../core/storage';
import { Fold } from './Fold';
import { CandlePanel } from './CandlePanel';
import { DepthPanel } from './DepthPanel';
import {
  PolyBot,
  type AutoSellState,
  type BookLevels,
  type AutoSellRebuy,
  type AutoSellRebuyDone,
  type EventSummary,
  type LoggedOrder,
  type NativeCoin,
  type NativeMarket,
  type SignalHint,
  type NativePosition,
  type OpenOrder,
} from '../native/polybot';

const cents = (p: number) => `${Math.round(p * 100)}¢`;

/** A window's opening time, which is how an event is named on this screen. */
const clockOf = (windowStart: number) =>
  new Date(windowStart * 1000).toLocaleTimeString('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  });

/** "12с назад" — enough to tell a working poll from a stalled one. */
const ago = (at: number) => {
  if (!at) return 'ещё не было';
  const secs = Math.max(0, Math.round((Date.now() - at) / 1000));
  return secs < 90 ? `${secs}с назад` : `${Math.round(secs / 60)}м назад`;
};
const WINDOW_SEC = 300;

/** How long a banner stays up before it clears itself. */
const NOTE_MS = 5_000;

/** A press this long is a hold, not a tap. Android's own threshold is 500 ms. */
const HOLD_MS = 450;

/**
 * The window's phase, as a colour on its clock.
 *
 * Amber while the first minute settles, green through the middle where a
 * position has room to work, red in the last minute — where the rule stops
 * holding out for a margin and the only thing left to do is get out.
 */

/** Seconds as a clock reads them. */
const clock = (secondsLeft: number): string =>
  `${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`;

const clockTone = (secondsLeft: number, lookAhead: boolean): string => {
  if (lookAhead) return 'muted';
  if (secondsLeft <= 60) return 'down';
  if (secondsLeft > WINDOW_SEC - 60) return 'warn';
  return 'up';
};

const IDLE_AUTOSELL: AutoSellState = {
  enabled: false,
  running: false,
  ladder: [0.77, 0.84, 0.89, 0.93, 0.97],
  retryEverySec: 7,
  lastSweepAt: 0,
  rebuys: [],
  rebuysDone: [],
  rows: [],
};

/**
 * Manual trading desk.
 *
 * Built to be worked with a thumb: the chart and the book read at a glance, any
 * price in the book is one tap from a pre-filled order, and the two buy buttons
 * sit at the bottom where the thumb already is. Everything that would push the
 * book off the screen lives behind the settings tab.
 */
export function Manual({
  onSummary,
  onCommitted,
  onOpenBalance,
  savings = 0,
  appSettings,
  locked,
}: {
  /** Potential profit of the round, for the header. */
  onSummary?: (potential: number) => void;
  /** What is in the market, so the container can split the whole deposit. */
  onCommitted?: (usd: number) => void;
  /** What the container holds back, in dollars. */
  /** Opens the balance sheet; the balance lives on the desk's own rail now. */
  onOpenBalance?: () => void;
  /** Held off the venue, at the address profit is withdrawn to. */
  savings?: number;
  /** The app-wide settings, folded in under the desk's own. */
  appSettings?: ReactNode;
  /** The day's goal is met: no new exposure until midnight. */
  locked?: boolean;
}) {
  const [settings, setSettings] = useState<ManualSettings>(DEFAULT_MANUAL_SETTINGS);
  const [tab, setTab] = useState<'desk' | 'settings'>('desk');
  const [market, setMarket] = useState<NativeMarket | null>(null);
  const [books, setBooks] = useState<Record<'Up' | 'Down', BookLevels>>({
    Up: { bids: [], asks: [] },
    Down: { bids: [], asks: [] },
  });
  const [positions, setPositions] = useState<NativePosition[]>([]);
  const [orders, setOrders] = useState<OpenOrder[]>([]);
  const [logged, setLogged] = useState<LoggedOrder[]>([]);
  const [lookAhead, setLookAhead] = useState(false);
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [autoSell, setAutoSell] = useState<AutoSellState>(IDLE_AUTOSELL);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [limitPrice, setLimitPrice] = useState('');
  const [limitSize, setLimitSize] = useState('');
  /**
   * The size chips, open by default.
   *
   * The size is chosen more often than the price is — every trade starts with
   * "how much" — and a row that has to be summoned first is a tap in front of
   * every one of them.
   */
  const [sizingLimit, setSizingLimit] = useState(true);
  /**
   * The share of the window's room the size stands for, once one is chosen.
   *
   * Kept as the choice rather than only its answer: "half of what is free" is
   * a different number at forty cents than at eighty, and the number in the
   * field has to be the one the price on the screen would actually buy.
   */
  const [sizePct, setSizePct] = useState<number | null>(null);
  /**
   * Typing the size out by hand, which is what a long press on it asks for.
   *
   * The chips answer "a quarter, half, all of it" and that is the size nearly
   * every time — but not the time you want eleven shares, and until now there
   * was no way to say eleven at all. A press and hold turns the number into a
   * field; a tap still opens the chips.
   */
  const [typingSize, setTypingSize] = useState(false);
  /** And the same for the price, which lost its wheel to the same gesture. */
  const [typingPrice, setTypingPrice] = useState(false);
  /** The hold timer, and whether it fired — a fired hold eats the tap after it. */
  const holdRef = useRef<number | null>(null);
  const heldRef = useRef(false);
  const clearHold = useCallback(() => {
    if (holdRef.current != null) {
      window.clearTimeout(holdRef.current);
      holdRef.current = null;
    }
  }, []);
  /**
   * The side the dock is about to buy.
   *
   * Chosen by tapping one of the two quotes, which is the moment the decision
   * is actually made; the button between them then only has to say "buy".
   */
  const [side, setSide] = useState<'Up' | 'Down' | null>(null);
  /**
   * Which coin the desk is on, and which it could be on.
   *
   * The same five-minute Up/Down runs on bitcoin, ether and solana, settling
   * the same way against the same oracle — so this is one desk pointed at one
   * of them, not three desks. The native side owns the answer (the service
   * trades whatever it was left on, screen or no screen); this is a copy of
   * it, kept so the row of buttons can light the right one.
   */
  const [coin, setCoin] = useState('btc');
  const [coins, setCoins] = useState<NativeCoin[]>([]);
  /** True while the feeds are being pointed somewhere else. */
  const [coinBusy, setCoinBusy] = useState(false);
  /**
   * Which way the window is leaning, as a hint over the clock.
   *
   * All that is left of the pulse rule: it read four things — the lead off the
   * window's open, a few minutes of momentum, whether the last minute traded,
   * and which way the book leans — and then had opinions about money. The
   * readings were the useful half, so they stayed and the opinions went.
   */
  const [hint, setHint] = useState<SignalHint | null>(null);
  /** The five-minute candle being read, and that window's own orders. */
  const [readWindow, setReadWindow] = useState<number | null>(null);
  const [readOrders, setReadOrders] = useState<LoggedOrder[]>([]);
  /** The event strip is folded away until it is asked for. */
  /** A resting order opened for editing. */
  const [editing, setEditing] = useState<TradeRow | null>(null);
  /** A position opened to be closed, with the price still to be chosen. */
  const [closing, setClosing] = useState<NativePosition | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  /**
   * Money set aside on the balance sheet, shown so the rail's figure is not a
   * mystery. It is already out of [balance]; this is only the label for why.
   */
  const [reserve, setReserve] = useState(0);

  // Read inside pollers that must not re-subscribe every time a setting changes.
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  /**
   * The banner clears itself.
   *
   * Everything it says is about a moment that has passed — an order went, an
   * order was refused — and left up it becomes part of the furniture, still
   * describing something that happened five windows ago.
   */
  useEffect(() => {
    if (note == null) return;
    const timer = window.setTimeout(() => setNote(null), NOTE_MS);
    return () => window.clearTimeout(timer);
  }, [note]);

  /** The book of our own orders, read inside callbacks that must not re-bind. */
  const ordersRef = useRef(orders);
  ordersRef.current = orders;

  /**
   * Until the stored settings arrive, `settings` holds the defaults — and the
   * defaults have the rules off. Re-arming from them would push "off" at a
   * service that was correctly on, which is how the journal filled with
   * off/on pairs.
   */
  const loadedRef = useRef(false);

  useEffect(() => {
    void loadManualSettings().then((stored) => {
      setSettings(stored);
      loadedRef.current = true;
      // The rules live in the native service, which starts every process with
      // them off. Without this the switch would read as on from the store while
      // nothing was actually sweeping — the toggle looked armed and was not.
      if (stored.autoSellEnabled) {
        void PolyBot.autoSellUpdate({
          enabled: stored.autoSellEnabled,
          ladder: stored.autoSellLadder,
          retryEverySec: stored.autoSellRetrySec,
          watchSec: stored.autoSellWatchSec,
          chime: stored.chime,
          dipRescue: stored.autoSellDipRescue,
          ladderLeadSec: stored.autoSellLeadSec,
          ladderStepSec: stored.autoSellStepSec,
          percentMode: stored.autoSellPercentMode,
          profitPct: stored.autoSellProfitPct,
          sliceGapSec: stored.autoSellSliceGapSec,
          panicSec: stored.autoSellPanicSec,
          closeFloor: stored.autoSellCloseFloor,
          lateFloor: stored.autoSellLateFloor,
          lateBandSec: stored.autoSellLateBandSec,
          ride: stored.autoSellRide,
          rideWaitMs: stored.autoSellRideMs,
        }).catch(() => {});
      }
    });
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // What the service is trading, which after a restart is whatever it was left
  // on rather than whatever this screen defaults to.
  useEffect(() => {
    void PolyBot.getCoins()
      .then((r) => {
        setCoins(r.coins);
        setCoin(r.current);
      })
      .catch(() => {});
  }, []);

  /**
   * Point the desk at another coin.
   *
   * Everything on the screen belongs to the market that is going away — its
   * book, its positions, its orders, its history — so it is cleared here
   * rather than left to be overwritten one poll at a time. A window drawn half
   * in one coin and half in another is the one state this screen must never
   * be in: every number on it is a price, and none of them say what they are
   * a price of.
   */
  const pickCoin = useCallback(
    (id: string) => {
      if (id === coin || coinBusy) return;
      const was = coins.find((c) => c.id === coin)?.label ?? coin.toUpperCase();
      // Only this market's: the count is about what is being left behind on
      // the coin going away, not about everything the wallet has ever held.
      const tokens = [market?.upTokenId, market?.downTokenId].filter(Boolean);
      const leaving =
        positions.filter((p) => tokens.includes(p.asset)).length +
        orders.filter((o) => tokens.includes(o.assetId)).length;
      setCoinBusy(true);
      setMarket(null);
      setBooks({ Up: { bids: [], asks: [] }, Down: { bids: [], asks: [] } });
      setPositions([]);
      setOrders([]);
      setLogged([]);
      setEvents([]);
      setSide(null);
      setLimitPrice('');
      setLimitSize('');
      setLookAhead(false);
      // What is left behind is not cancelled and not closed: a five-minute
      // binary settles itself, the sell rule keeps working its ladder on the
      // token it holds, and a resting limit stays on the book. It does stop
      // being visible until the desk comes back, so it is said out loud.
      const left = leaving;
      void PolyBot.setCoin({ id })
        .then((r) => {
          setCoin(r.id);
          if (left > 0) {
            setNote(
              `На ${was} осталось открытым: ${left}. Продажа и лимитки` +
                ' работают, окно досчитается само.',
            );
          }
        })
        .catch(() => setNote('Не вышло сменить монету'))
        .finally(() => setCoinBusy(false));
    },
    [coin, coinBusy, coins, market, positions, orders],
  );

  /** Which five-minute window the clock is in. Changes on the boundary. */
  const windowStart = Math.floor(now / 1000 / WINDOW_SEC) * WINDOW_SEC;

  /**
   * Only the window being traded. Anything from an earlier one is settled — it
   * cannot be sold and its price no longer means anything — so it leaves the
   * list the moment the window turns rather than lingering until the data API
   * marks it redeemable, which can take minutes.
   */
  const livePositions = market
    ? positions.filter((p) => p.conditionId === market.conditionId)
    : [];
  // How each five-minute event went. Slow on purpose: a window's result cannot
  // change once it has closed, and the running one only moves when an order does.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getEvents({ limit: 10 })
        .then((r) => {
          if (cancelled) return;
          setEvents(r.events);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 15_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [windowStart, coin]);

  /**
   * What each five-minute event made, keyed the way the chart holds a candle.
   *
   * A window is a candle, so its result belongs over its own candle rather
   * than in a second list of the same windows in the same order. Only the
   * ones that actually traded: a nought over every candle the desk sat out
   * would bury the handful it did not.
   */
  const results = useMemo(() => {
    const out: Record<string, number> = {};
    for (const e of events) {
      if (e.trades > 0) out[String(e.windowStart * 1000)] = e.pnl;
    }
    return out;
  }, [events]);

  /** What the round makes if it goes our way — the header's second number. */
  const potential = useMemo(
    () => potentialProfit(positions.filter((p) => !p.redeemable)),
    [positions],
  );

  useEffect(() => {
    onSummary?.(potential);
  }, [potential, onSummary]);

  useEffect(() => {
    if (readWindow == null) {
      setReadOrders([]);
      return;
    }
    let cancelled = false;
    void PolyBot.getOrderLog({ windowStart: readWindow })
      .then((r) => {
        if (!cancelled) setReadOrders(r.orders);
      })
      .catch(() => {
        if (!cancelled) setReadOrders([]);
      });
    return () => {
      cancelled = true;
    };
  }, [readWindow, coin]);

  /** The window's orders, paired into trades: a buy and the sell that closed it. */
  const trades = useMemo(() => pairOrders(logged), [logged]);

  /**
   * Which side of the desk's own market a token id is, or null for one that is
   * not on it at all — a leftover order from a window that has closed.
   *
   * Moving an order means pulling it and signing a new one, and the new one is
   * signed against a token id. Taking that id from the row's printed side —
   * "Up" or "Down" — silently re-placed a foreign order on this window's
   * market. So the id comes from the live order itself, and an order this
   * market does not own cannot be moved at all. It can still be cancelled:
   * that goes by order id and is right whatever market it belongs to.
   */
  const sideOfToken = useCallback(
    (assetId: string): 'Up' | 'Down' | null =>
      assetId === market?.upTokenId
        ? 'Up'
        : assetId === market?.downTokenId
          ? 'Down'
          : null,
    [market],
  );


  /**
   * Only what is still working.
   *
   * A limit that has filled is no longer something to watch or cancel; it is a
   * round that happened, and it belongs in the history below. Keeping it in
   * this list meant the one place you look to answer "what is still out there"
   * was mostly answers to a different question.
   */
  /**
   * What this window's own orders say each side cost.
   *
   * The data API reports a fresh position with its size right and its cost at
   * zero for a minute or so; the order log knows what was paid the moment it
   * is paid. Only lots still open count — a round that has been sold is not
   * part of what is held.
   */
  const localAvg = useMemo(() => {
    const acc: Record<string, { shares: number; cost: number }> = {};
    for (const t of trades) {
      if (t.status !== 'open' && t.status !== 'pending') continue;
      if (t.buyPrice == null || t.shares <= 0) continue;
      const at = acc[t.outcome] ?? { shares: 0, cost: 0 };
      at.shares += t.shares;
      at.cost += t.shares * t.buyPrice;
      acc[t.outcome] = at;
    }
    const avg = (side: string) =>
      acc[side] && acc[side].shares > 0 ? acc[side].cost / acc[side].shares : null;
    return { Up: avg('Up'), Down: avg('Down') };
  }, [trades]);

  /**
   * What is still working — from the log, and from the venue over the top.
   *
   * The log pairs a buy with its sell and is what these rows are made of, but
   * it can be behind or wrong about whether an order is still on the book, and
   * an order missing from this list cannot be cancelled or moved. So the
   * venue's own listing is laid over it: anything the exchange says is working
   * appears here, whatever the log believes.
   */
  const working = useMemo(() => {
    const rows = trades.filter(
      (t) => t.status === 'buying' || t.status === 'pending',
    );
    return withLiveOrders(rows, orders, (assetId) => sideOfToken(assetId) ?? '');
  }, [trades, orders, sideOfToken]);
  const realisedPnl = useMemo(() => realised(trades), [trades]);

  /**
   * How finely this coin's price is worth printing.
   *
   * Bitcoin in whole dollars, ether to a tenth, solana to a cent — the same
   * readouts at the resolution each of them actually moves at. Zero until the
   * list has loaded, which is bitcoin's answer anyway.
   */
  const coinDigits = coins.find((c) => c.id === coin)?.digits ?? 0;

  /** The window the desk is trading: this one, or the one after it. */
  const deskWindow = lookAhead ? windowStart + WINDOW_SEC : windowStart;

  /** True while the loaded market is not the one the desk is pointed at. */
  const marketStale = market?.windowStart != null && market.windowStart !== deskWindow;

  // The market must never lag the clock: its token ids are what orders are sent
  // against, so a stale one would place a buy in the window that just ended.
  // While it is behind — including the second or two Gamma needs to index a new
  // window — this polls hard, and backs off once it has caught up.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getMarketForWindow({ windowStart: deskWindow })
        .then((m) => {
          if (!cancelled) setMarket(m);
        })
        .catch(() => {
          // Measured: Polymarket publishes a window's market minutes before
          // it opens, so a look-ahead that finds nothing is a fetch that has
          // not landed rather than a window that does not exist. The desk
          // says so and the poll below tries again.
          if (!cancelled && lookAhead) setMarket(null);
        });
    };
    read();
    const timer = window.setInterval(read, marketStale ? 2000 : 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [deskWindow, lookAhead, marketStale, coin]);

  // A look-ahead is only meaningful until that window becomes the current one.
  useEffect(() => {
    if (lookAhead) setLookAhead(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [windowStart]);

  const tokenFor = useCallback(
    (which: 'Up' | 'Down') =>
      which === 'Up' ? market?.upTokenId : market?.downTokenId,
    [market],
  );

  // Both books, not just the visible one: the two buy buttons must be able to
  // act on either side without the user first switching to it and waiting.
  useEffect(() => {
    const up = tokenFor('Up');
    const down = tokenFor('Down');
    if (!up || !down) return;
    let cancelled = false;
    const read = () => {
      void Promise.all([
        PolyBot.getBookLevels({ tokenId: up, depth: 4 }),
        PolyBot.getBookLevels({ tokenId: down, depth: 4 }),
      ])
        .then(([u, d]) => {
          if (!cancelled) setBooks({ Up: u, Down: d });
        })
        .catch(() => {});
    };
    read();
    // The book is the price the position's result is judged by, so it is worth
    // two seconds — but not less: the venue's patience is shared with the
    // rules, and the buy-back was once refused a price while this polled.
    const timer = window.setInterval(read, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [tokenFor]);

  // The hint is read off things already in memory on the native side, so it
  // costs nothing but the bridge hop.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.signal()
        .then((s) => {
          if (!cancelled) setHint(s);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
    // Each coin's rules are their own, with their own money and their own
    // record, so a switch is a different pair of bots — not the same pair
    // with different numbers.
  }, [coin]);

  // Sizing off the wallet needs the wallet. It only moves when an order fills,
  // so a slow poll is enough. What comes back is already net of the reserve —
  // the native side takes it out where the balance is read — so every number
  // below this line is money that may actually be spent.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getBalance()
        .then((r) => {
          if (cancelled) return;
          setBalance(r.usdc);
          setReserve(r.locked ?? 0);
        })
        .catch(() => {});
    };
    read();
    // Ten seconds rather than thirty, and again the moment the window turns:
    // the reserve is a share per event, so what is free changes at every
    // boundary — and inside the last seconds of a losing window it changes
    // again, when the money held against a side that has lost is let go.
    const timer = window.setInterval(read, 10_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [windowStart, coin]);

  // The desk's own book: what is held and what is working.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getPositions()
        .then((r) => {
          if (!cancelled) setPositions(r.positions.filter((p) => !p.redeemable));
        })
        .catch(() => {});
      void PolyBot.getOpenOrders()
        .then((r) => {
          if (!cancelled) setOrders(r.orders);
        })
        .catch(() => {});
      void PolyBot.getOrderLog({ windowStart: deskWindow })
        .then((r) => {
          if (!cancelled) setLogged(r.orders);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 4000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [deskWindow, coin]);

  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.autoSellState()
        .then((s) => {
          if (cancelled) return;
          setAutoSell(s);
          // The foreground service can be killed by the system. If the setting
          // says the rule should be on and the service says it is not, arm it
          // again rather than leaving a switch that lies.
          // Either rule running is reason for the loop to be up, so both are
          // compared — otherwise a buy-back-only setup would never be re-armed.
          const wanted =
            settingsRef.current.autoSellEnabled;
          if (
            loadedRef.current &&
            (s.enabled !== settingsRef.current.autoSellEnabled ||
              (wanted && !s.running))
          ) {
            void PolyBot.autoSellUpdate({
              enabled: settingsRef.current.autoSellEnabled,
              ladder: settingsRef.current.autoSellLadder,
              retryEverySec: settingsRef.current.autoSellRetrySec,
              watchSec: settingsRef.current.autoSellWatchSec,
              chime: settingsRef.current.chime,
              dipRescue: settingsRef.current.autoSellDipRescue,
              ladderLeadSec: settingsRef.current.autoSellLeadSec,
              ladderStepSec: settingsRef.current.autoSellStepSec,
              percentMode: settingsRef.current.autoSellPercentMode,
              profitPct: settingsRef.current.autoSellProfitPct,
              sliceGapSec: settingsRef.current.autoSellSliceGapSec,
              panicSec: settingsRef.current.autoSellPanicSec,
              closeFloor: settingsRef.current.autoSellCloseFloor,
              lateFloor: settingsRef.current.autoSellLateFloor,
              lateBandSec: settingsRef.current.autoSellLateBandSec,
              ride: settingsRef.current.autoSellRide,
              rideWaitMs: settingsRef.current.autoSellRideMs,
            }).catch(() => {});
          }
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

  const apply = useCallback((next: ManualSettings) => {
    setSettings(next);
    void saveManualSettings(next);
  }, []);

  const minSize = market?.minimumOrderSize ?? 5;

  /**
   * What is already in the market, and what the guard will still allow.
   *
   * Positions at what they cost, plus buy orders still resting at what they
   * would cost — a limit that has not filled is committed money, and leaving it
   * out is how a stack of them quietly becomes the whole deposit.
   */
  /**
   * What *this* five minutes is holding.
   *
   * Only this market's own: a closed window's shares sit in the wallet until
   * the exchange marks them redeemable, which takes minutes, and counting them
   * meant a fresh window opened with the last one's money already spent.
   */
  const resting = useMemo(() => {
    if (!market) return 0;
    const tokens = new Set([market.upTokenId, market.downTokenId]);
    return orders
      .filter((o) => o.side === 'BUY' && tokens.has(o.assetId))
      .reduce((sum, o) => sum + orderCost(o.remaining, o.price), 0);
  }, [orders, market]);

  const committed = useMemo(() => {
    if (!market) return 0;
    const held = positions
      .filter(
        (p) => p.conditionId === market.conditionId && !p.redeemable && p.size > 0,
      )
      .reduce((sum, p) => sum + p.size * (p.avgPrice > 0 ? p.avgPrice : p.curPrice), 0);
    return held + resting;
  }, [positions, resting, market]);

  /**
   * The balance minus what is already promised to resting buys.
   *
   * The venue reports the wallet, not what is left of it: collateral for an
   * order that has not filled is still in the balance it reads, so sizing the
   * next order against that number spends the same dollars twice — and the
   * second order is the one that gets refused.
   */
  const freeCash = Math.max(0, (balance ?? 0) - resting);

  useEffect(() => {
    onCommitted?.(committed);
  }, [committed, onCommitted]);

  const exposure = useMemo(
    () => exposureFor(freeCash, committed),
    [freeCash, committed],
  );

  const guard = settings.exposureGuard && balance != null;

  /**
   * Where the current five-minute window opened, in GMX's own series. It is
   * the level the window turns on, so it belongs in the header next to the
   * price rather than only as a line on the chart.
   */

  // From the clock, not the market: the countdown must keep running even in the
  // seconds where the new window's market has not loaded yet.
  const secondsLeft = Math.max(0, windowStart + WINDOW_SEC - Math.floor(now / 1000));

  /**
   * How far into the window the desk is trading.
   *
   * Looking ahead, the window on the buttons has not started at all, so it is
   * at second zero — which is exactly the moment the early rules are for.
   */
  const elapsed = lookAhead ? 0 : WINDOW_SEC - secondsLeft;

  /** The dearest a buy may be right now. Above it nothing is placed or offered. */
  const ceiling = buyCeiling(elapsed);

  const place = useCallback(
    async (
      which: 'Up' | 'Down',
      action: 'BUY' | 'SELL',
      price: number,
      shares: number,
    ) => {
      const tokenId = which === 'Up' ? market?.upTokenId : market?.downTokenId;
      if (!market || !tokenId) {
        setNote('Рынок окна ещё не загружен');
        return;
      }
      if (market.windowStart != null && market.windowStart !== deskWindow) {
        // The window rolled and the new market has not arrived yet. Sending
        // this would buy into the window that just closed.
        setNote('Окно сменилось — ждём новый рынок');
        return;
      }
      if (!Number.isFinite(price) || price <= 0 || price >= 1) {
        setNote('Цена вне диапазона');
        return;
      }
      // The venue floors an order by share count and by value; at low prices
      // the dollar is the one that bites, and five shares at 5c is rejected.
      const floor = minShares(price, market.minimumOrderSize);
      if (!Number.isFinite(shares) || shares < floor - 1e-9) {
        setNote(
          `Минимум — ${floor.toFixed(floor % 1 ? 1 : 0)} долей ` +
            `(биржа не берёт заявку дешевле 1 $)`,
        );
        return;
      }
      // Nothing dear this early, by any route. A side that costs this much in
      // the first minutes is paying for a move with most of the window left to
      // undo it, and the rule is worth nothing if the price field can step
      // around it.
      if (action === 'BUY' && buyBarred(price, elapsed)) {
        setNote(
          `Первые ${elapsed < 60 ? '60 секунд' : '3 минуты'} — не дороже ` +
            `${cents(buyCeiling(elapsed))}`,
        );
        return;
      }

      // The day's stop comes before everything else: it is the one rule that
      // exists to end trading, not to shape it. Selling stays open — a stop
      // that stranded open positions would work against the win it protects.
      if (action === 'BUY' && locked) {
        setNote('Цель дня взята — покупки до полуночи заблокированы');
        return;
      }

      // The guard is on every buy, not only on the ones the buttons size:
      // limits, the order form and a quick tap all end up here, and a rule that
      // covers most of the ways to spend money is not a rule.
      if (action === 'BUY' && guard) {
        const cost = orderCost(shares, price);
        if (cost > exposure.room + 1e-9) {
          setNote(
            `Лимит окна: заявка на ${usd(cost)}, свободно ${usd(exposure.room)} ` +
              `(в рынке ${usd(exposure.committed)} из ${usd(exposure.cap)})`,
          );
          return;
        }
      }

      setBusy(true);
      try {
        // A tap to sell outranks whatever is already offered. The shares under
        // a resting sell are spoken for, so asking for them again is refused
        // for "not enough balance" — which is true and useless. Pull ours
        // first: the rule can put its own back afterwards, and the person
        // holding the phone has decided something the rule has not.
        if (action === 'SELL') {
          const mine = ordersRef.current.filter(
            (o) => o.assetId === tokenId && o.side === 'SELL',
          );
          for (const order of mine) {
            await PolyBot.cancelOrder({ orderId: order.id }).catch(() => {});
          }
          if (mine.length > 0) {
            const fresh = await PolyBot.getOpenOrders().catch(() => null);
            if (fresh) setOrders(fresh.orders);
          }
        }

        const r = await PolyBot.placeOrder({
          tokenId,
          conditionId: market.conditionId,
          side: action,
          price,
          size: shares,
          orderType: 'GTC',
        });
        setNote(
          r.success
            ? `${action === 'BUY' ? 'Куплено' : 'Продано'} ${which}: ${shares.toFixed(
                1,
              )} × ${cents(price)}`
            : (r.error ?? 'CLOB отклонил ордер'),
        );
        // And read the books back at once, so the order appears on the list
        // with its ✕ now rather than on the next four-second beat. The venue's
        // listing may not have indexed it yet; the app's own log has.
        if (r.success) {
          const [fresh, log, cash] = await Promise.all([
            PolyBot.getOpenOrders().catch(() => null),
            PolyBot.getOrderLog({ windowStart: deskWindow }).catch(() => null),
            // And what is free now. A sale releases its share of the reserve
            // the moment it fills — the native side counts the proceeds as
            // the run's while the venue is still transferring them — and the
            // size of the next entry is worked out from this number, so it
            // has to be this sale's number rather than the last poll's.
            PolyBot.getBalance().catch(() => null),
          ]);
          if (fresh) setOrders(fresh.orders);
          if (log) setLogged(log.orders);
          if (cash) {
            setBalance(cash.usdc);
            setReserve(cash.locked ?? 0);
          }
        }
      } catch (e) {
        setNote(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [market, deskWindow, guard, exposure, locked, elapsed],
  );

  /**
   * One tap on a position opens a sell for the whole of it, priced at the bid
   * that is there right now. The price stays editable — the tap is meant to
   * save the typing, not to decide the trade.
   */
  const marketSell = useCallback(
    async (which: 'Up' | 'Down', shares: number) => {
      if (!Number.isFinite(shares) || shares <= 0) {
        setNote('Нет объёма для продажи');
        return;
      }
      const bids = books[which].bids;
      if (bids.length === 0) {
        setNote(`Нет спроса по ${which}`);
        return;
      }

      let left = shares;
      let price = bids[0].price;
      for (const level of bids) {
        price = level.price;
        left -= level.size;
        if (left <= 0) break;
      }
      // A tick under the level that clears it, so rounding cannot leave the
      // last shares hanging above the book.
      const tick = market?.tickSize ?? 0.01;
      await place(which, 'SELL', Math.max(tick, price - tick), shares);
    },
    [books, market, place],
  );

  /**
   * Tapping a position opens the way out of it, priced.
   *
   * Selling on the tap itself was one gesture away from a whole position gone
   * at whatever the book happened to be — and the price you leave at is the
   * trade. So the tap opens a sheet over a dimmed desk with the bid already
   * under the thumb: one more tap sells at it, or the price moves first.
   */
  const sellPosition = useCallback((position: NativePosition) => {
    setNote(null);
    setClosing(position);
  }, []);

  /**
   * Sell the whole draft at market.
   *
   * Priced through the bids rather than at the top of them: the top is rarely
   * deep enough for a whole position, and an order that only fills against it
   * leaves the rest resting — which is not what "sell at market" means. Walking
   * the book gives a price that clears the size, and anything that does not
   * fill rests harmlessly at the bottom of it.
   */

  /**
   * The side a replacement for this row would be signed against.
   *
   * The live order's own token where the venue is listing it — that is the
   * only thing that is true whatever the row says. Failing that, the row's own
   * side, and only while this event is the one on screen: a past window's
   * "Down" is a different token from this window's, and signing the second
   * against the first is a new order on the wrong market.
   */
  const sideForEdit = useCallback(
    (row: TradeRow): 'Up' | 'Down' | null => {
      const live = orders.find((x) => x.id === row.orderId);
      if (live) return sideOfToken(live.assetId);
      return row.outcome === 'Up' || row.outcome === 'Down' ? row.outcome : null;
    },
    [orders, sideOfToken],
  );

  /** The ids the exchange itself is still listing, for anything that asks. */
  const liveIds = useMemo(
    () => new Set(orders.filter((o) => o.remaining > 1e-9).map((o) => o.id)),
    [orders],
  );

  /** Every limit buy still on the book, which is what "лимитки" means here. */
  const restingLimits = useMemo(
    () => orders.filter((o) => o.side === 'BUY' && o.remaining > 1e-9),
    [orders],
  );

  /**
   * Pull them all in one tap.
   *
   * Only buys: a resting sell is an exit the rule arranged, and cancelling
   * those would have it place them straight back. Each ✕ on a row still pulls
   * one sell individually.
   */
  const cancelLimits = useCallback(async () => {
    if (restingLimits.length === 0) return;
    setBusy(true);
    let done = 0;
    try {
      for (const order of restingLimits) {
        const r = await PolyBot.cancelOrder({ orderId: order.id }).catch(() => null);
        if (r?.cancelled) done += 1;
      }
      setNote(
        done === restingLimits.length
          ? `Снято лимиток: ${done}`
          : `Снято ${done} из ${restingLimits.length} — остальные уже неактивны`,
      );
      const fresh = await PolyBot.getOpenOrders().catch(() => null);
      if (fresh) setOrders(fresh.orders);
    } finally {
      setBusy(false);
    }
  }, [restingLimits]);

  /**
   * Change a resting order's price or size.
   *
   * Pulled and re-placed as one step, because that is all the venue offers: an
   * order's terms are what it was signed with.
   */
  const editOrder = useCallback(
    async (
      orderId: string,
      side: 'Up' | 'Down',
      /** A resting sell moves the same way a resting buy does. */
      action: 'BUY' | 'SELL',
      price: number,
      shares: number,
    ) => {
      const tokenId = side === 'Up' ? market?.upTokenId : market?.downTokenId;
      if (!market || !tokenId) {
        setNote('Рынок окна ещё не загружен');
        return;
      }
      const floor = minShares(price, market.minimumOrderSize);
      if (!Number.isFinite(price) || price <= 0 || price >= 1) {
        setNote('Цена вне диапазона');
        return;
      }
      if (!Number.isFinite(shares) || shares < floor - 1e-9) {
        setNote(`Минимум ${floor.toFixed(floor % 1 ? 1 : 0)} долей`);
        return;
      }

      setBusy(true);
      try {
        const r = await PolyBot.replaceOrder({
          orderId,
          tokenId,
          conditionId: market.conditionId,
          side: action,
          price,
          size: shares,
          orderType: 'GTC',
        });
        setNote(r.success ? `Изменено: ${shares} × ${cents(price)}` : r.error ?? 'Не вышло');
        const fresh = await PolyBot.getOpenOrders().catch(() => null);
        if (fresh) setOrders(fresh.orders);
      } catch (e) {
        setNote(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
        setEditing(null);
      }
    },
    [market],
  );

  const cancel = useCallback(async (orderId: string) => {
    setBusy(true);
    try {
      const r = await PolyBot.cancelOrder({ orderId });
      setNote(r.cancelled ? 'Ордер снят' : 'Ордер уже неактивен');
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  /** The offer on each side, which is what a limit is priced against. */
  const askUp = books.Up.asks[0]?.price ?? null;
  const askDown = books.Down.asks[0]?.price ?? null;

  /**
   * What a tap would close on this side: the position, or the size set above
   * it if that is smaller.
   */
  const limitPriceNum = Number(limitPrice.replace(',', '.')) / 100;
  // Five shares unless the venue's floor is higher at this price — which it is
  // under twenty cents, where five shares is under a dollar and refused.
  const limitDefaultSize = Math.max(
    DEFAULT_CLICK_SHARES,
    minShares(limitPriceNum, minSize),
  );
  const limitSizeNum = limitSize === '' ? limitDefaultSize : Number(limitSize.replace(',', '.'));

  /** What the size is priced at: the typed limit, or the ask it would default to. */
  const limitBasis =
    Number.isFinite(limitPriceNum) && limitPriceNum > 0
      ? limitPriceNum
      : (askUp ?? askDown ?? 0);

  /** What a share of the size is taken out of: the window's room, or the cash. */
  const sizeBudget = settings.exposureGuard ? exposure.room : freeCash;

  /**
   * The chosen share, re-answered whenever the question changes.
   *
   * A price moved by a cent changes how many shares half the room buys, and a
   * size left at the old answer is the one thing on this row that would be
   * quietly wrong. So the share is applied again on every change of price or
   * of what is free — until an amount in dollars is picked instead, which is
   * not a share of anything.
   */
  useEffect(() => {
    if (sizePct == null) return;
    if (!(limitBasis > 0) || !(sizeBudget > 0)) return;
    const shares = stakeShares(limitBasis, sizeBudget, sizePct / 100, minSize);
    if (shares != null) setLimitSize(String(shares));
  }, [sizePct, limitBasis, sizeBudget, minSize]);

  /**
   * What the order in the field would cost, and whether there is that much.
   *
   * The reserve is taken out of the balance before the desk ever sees it, so
   * this is the same money the native side will check against — but it is
   * checked here as well, because a button that sends an order the app is
   * going to refuse is a button that lies. Resting buys are already out of
   * [freeCash]: their money is spoken for even though nothing has filled.
   */
  const limitCost =
    Number.isFinite(limitPriceNum) &&
    limitPriceNum > 0 &&
    Number.isFinite(limitSizeNum) &&
    limitSizeNum > 0
      ? orderCost(limitSizeNum, limitPriceNum)
      : 0;

  /** Whether the terms in the field can actually be paid for. */
  const affordable = limitCost > 0 && limitCost <= freeCash + 1e-9;

  /** Whether the price in the field is one the early rule will not buy at. */
  const limitBarred =
    Number.isFinite(limitPriceNum) &&
    limitPriceNum > 0 &&
    buyBarred(limitPriceNum, elapsed);

  const nudgeLimit = (delta: number) => {
    const base = Number.isFinite(limitPriceNum) && limitPriceNum > 0
      ? Math.round(limitPriceNum * 100)
      : Math.round((askUp ?? 0.5) * 100);
    const top = Math.round(ceiling * 100);
    setLimitPrice(String(Math.min(top, Math.max(1, base + delta))));
  };

  /**
   * One limit, or a ladder of them stepping down from it.
   *
   * The rungs go out oldest-price-first and each is a normal order, so the
   * guard, the minimum and the log all treat them as what they are.
   */
  const placeLimit = async (which: 'Up' | 'Down') => {
    if (!Number.isFinite(limitPriceNum) || limitPriceNum <= 0) {
      setNote('Укажите цену лимитки');
      return;
    }

    const wanted = settings.limitLadder
      ? limitLadder(
          limitPriceNum,
          LIMIT_LADDER_COUNT,
          settings.limitLadderStep,
          market?.tickSize ?? 0.01,
        )
      : [limitPriceNum];

    // Only as many rungs as the container leaves room for. Sending all four
    // and letting the guard refuse the last two put two real orders on the
    // book and two error banners on the screen, which reads as a broken app
    // rather than a full container — and the rungs that did go out were the
    // dearest ones, because they go first.
    const size = limitSizeNum > 0 ? limitSizeNum : limitDefaultSize;
    const room = settings.exposureGuard ? exposure.room : freeCash;
    const rungs: number[] = [];
    let spend = 0;
    for (const price of wanted) {
      const cost = orderCost(size, price);
      if (rungs.length > 0 && spend + cost > room + 1e-9) break;
      rungs.push(price);
      spend += cost;
    }

    if (rungs.length < wanted.length) {
      setNote(
        `В контейнере хватает на ${rungs.length} из ${wanted.length} — ` +
          `ставлю ${rungs.length}`,
      );
    }

    for (const price of rungs) {
      await place(which, 'BUY', price, limitSizeNum);
    }
  };

  return (
    <>
      {/*
        One surface, not five cards with air between them. Everything above the
        dock is the same running window — the bots trading it, the events it
        follows, its price, its positions — and hairlines rather than gaps read
        as one instrument instead of a stack of unrelated panels.
      */}
      <div className="deck">

      {/*
        One rail of collapsed things: the session on the left, the bot on the
        right. Both are read a few times an hour and neither is worth a
        permanent row — but both want to be one tap away, so they fold rather
        than hide.
      */}
      <div className="rail">
        {/*
          The balance, and after the slash what the container is holding out of
          it. Two cells for one fact read as two facts; the slash says what it
          is — this much money, that much of it spoken for.
        */}
        {/*
          The balance, and after the slash what is still open to this five
          minutes. What has been spent can be read off the difference; what is
          left is the number a size is actually decided from.
        */}
        <button className="railbal" onClick={onOpenBalance}>
          <b>{balance === null ? '—' : balance.toFixed(2)}</b>
          <span className={exposure.full ? 'warn' : 'muted'}>
            /{exposure.room.toFixed(2)}
          </span>
          {/*
            And everything the run is worth, including what has been taken off
            the venue. The two numbers before it are what can be traded and
            what this window may still take; this one is the score.
          */}
          {savings > 0 && balance !== null && (
            <i className="railall">Σ{(balance + savings).toFixed(2)}</i>
          )}
          {/* And what is being held back, so a balance smaller than the
              wallet reads as a decision rather than as a missing sum. */}
          {reserve > 0 && <i className="raillock">🔒{Math.round(reserve)}</i>}
        </button>

        {/*
          Which coin. Three letters each, because the row has to sit next to
          the balance and the gear without pushing either off — and because
          the label is only there to say which of the three is lit.

          It is disabled while the switch is in flight: the feeds are being
          torn down and rebuilt behind it, and a second tap in that second
          would ask for a third coin the first switch has not finished leaving.
        */}
        {coins.length > 1 && (
          <div className="railcoins">
            {coins.map((c) => (
              <button
                key={c.id}
                className={c.id === coin ? 'on' : undefined}
                disabled={coinBusy}
                onClick={() => pickCoin(c.id)}
              >
                {c.label}
              </button>
            ))}
          </div>
        )}

        {/*
          Which way the window is leaning, and how much of the desk agrees.

          A hint, not an instruction: nothing on the desk acts on it. Four
          readings behind one arrow, with the count beside it — four out of
          four is everything agreeing, one is a lead with the rest arguing.
          Tapping it says which of them is arguing.
        */}
        <button
          className={`railhint ${hint?.side === 'Up' ? 'up' : ''}${
            hint?.side === 'Down' ? 'down' : ''
          }`}
          onClick={() =>
            setNote(
              hint?.against
                ? `${hint.side ?? 'Пока никак'}: ${hint.against}`
                : hint?.side
                  ? `${hint.side}: ход, импульс, объём и стакан заодно`
                  : 'Окно пока ничего не говорит',
            )
          }
          aria-label="Подсказка по окну"
        >
          <b>
            {hint?.side === 'Up' ? '▲' : hint?.side === 'Down' ? '▼' : '–'}
          </b>
          <i>{hint?.side ? `${hint.agree}/4` : '—'}</i>
        </button>

        {/*
          The way out of a window that went wrong, on one switch.

          On, the sell rule stops holding out for its rung and takes the first
          price that is a profit at all. It is for the moment a losing window
          comes back to break-even, which is a moment that does not last — so
          it fires once and takes itself off, and the switch reads its own
          state back from the rule rather than remembering what was pressed.
        */}
        <button
          className={`railany${autoSell.anyProfit ? ' on' : ''}`}
          onClick={() => {
            const next = !autoSell.anyProfit;
            setAutoSell({ ...autoSell, anyProfit: next });
            void PolyBot.autoSellUpdate({ anyProfit: next })
              .then(() => PolyBot.autoSellState())
              .then(setAutoSell)
              .catch(() => {});
          }}
          aria-label="Выход при любом плюсе"
          aria-pressed={autoSell.anyProfit ?? false}
        >
          +1¢
        </button>

        <div className="deskbtns">
          <button
            className={`gear${tab === 'settings' ? ' on' : ''}`}
            onClick={() => setTab(tab === 'settings' ? 'desk' : 'settings')}
            aria-label="Настройки"
          >
            ⚙
          </button>
        </div>
      </div>

      {tab !== 'settings' && (
          <div className="card tight">
            {/*
              The hours before this window, as Binance's own five-minute
              candles, with the prices the market keeps turning at drawn on
              them, and what each window made written over its own candle.
              Under it the book: what the next few dollars would cost.
            */}
            <CandlePanel
              interval="5m"
              height={150}
              picked={readWindow}
              results={results}
              digits={coinDigits}
              coin={coin}
              onPick={(t) => setReadWindow((v) => (v === t ? null : t))}
            />

            {/*
              What happened in the candle just tapped: the bot's own reading of
              that window and every order it holds. One candle is one window,
              so the two are the same question asked of the chart instead of
              the list.
            */}
            {readWindow != null && (
              <WindowRead
                windowStart={readWindow}
                orders={readOrders}
                onClose={() => setReadWindow(null)}
              />
            )}

            {/*
              And the same hour close up. The five-minute chart says which way
              the day is going; this one says what price is doing right now,
              which on a bet that lasts five minutes is the half that decides
              the side.
            */}
            <CandlePanel
              interval="1m"
              height={110}
              digits={coinDigits}
              coin={coin}
            />

            <DepthPanel digits={coinDigits} coin={coin} />

            {/*
              Only what is still working. A round that has closed is history and
              is filed as such below — leaving it here meant the list you scan
              for "what am I still exposed to" was mostly things you are not.
            */}
            {working.length > 0 && (
              <>
                {restingLimits.length > 0 && (
                  <div className="listhead bare">
                    <button
                      className="linkbtn"
                      disabled={busy}
                      onClick={() => void cancelLimits()}
                    >
                      снять лимитки ({restingLimits.length})
                    </button>
                  </div>
                )}
                {working.map((t) => {
                  const live = t.orderId
                    ? orders.find((x) => x.id === t.orderId)
                    : undefined;
                  // Which side to sign a replacement against. The live order's
                  // own token where the venue is listing it; otherwise the
                  // row's printed side, which is this event's — but only while
                  // this event is the one on screen, since a past window's
                  // "Down" is a different token from this window's.
                  const editSide = live
                    ? sideOfToken(live.assetId)
                    : t.outcome === 'Up' || t.outcome === 'Down'
                      ? t.outcome
                      : null;
                  // A resting sell is as much a price you might want to move
                  // as a resting buy — and it is the one you move in a hurry.
                  const editable = t.orderId != null && editSide != null;
                  const price = (t.status === 'buying' ? t.buyPrice : t.sellPrice) ?? 0;
                  return (
                    <div
                      className={`listrow static trade trade-${t.status}${
                        editable ? ' editable' : ''
                      }`}
                      key={t.key}
                      onClick={editable ? () => setEditing(t) : undefined}
                    >
                      <span
                        className={t.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}
                      >
                        {t.outcome}
                      </span>
                      {/* A working order is a price and a size. Nothing else. */}
                      <span className="listrow-main">
                        <span className="ordermain">
                          {cents(price)}
                          <i>×</i>
                          {t.shares.toFixed(t.shares % 1 ? 1 : 0)}
                        </span>
                      </span>
                      {/*
                        The ✕ needs an order id and nothing else. It used to
                        wait for the venue's listing to confirm the order, and
                        the listing lags a placement by a second or two — so
                        the one moment you most want to pull a limit back, just
                        after putting it out, was the one moment there was no
                        way to. Cancelling goes by id: the venue either pulls it
                        or says it is already inactive, and both are answers.
                      */}
                      {t.orderId ? (
                        <button
                          className="xbtn"
                          disabled={busy}
                          onClick={(e) => {
                            e.stopPropagation();
                            void cancel(t.orderId as string);
                          }}
                          aria-label="Снять ордер"
                        >
                          ✕
                        </button>
                      ) : (
                        <span className="orderdot" aria-hidden />
                      )}
                    </div>
                  );
                })}
              </>
            )}
          </div>
      )}

      </div>

      {tab === 'settings' ? (
        <>
          {/*
            The rules live here now. They are set once and then watched, and a
            permanent row of switches on the desk was five taps' worth of
            screen given to something changed a few times a day.
          */}
          <RuleBar
            state={autoSell}
            settings={settings}
            exposure={exposure}
            onChange={apply}
            onNote={setNote}
          />
          <ManualSettingsForm
            settings={settings}
            onChange={apply}
            onNote={setNote}
          />
          {appSettings}
        </>
      ) : (
        <>
          {(autoSell.rebuys.length > 0 ||
            (autoSell.rebuysDone?.length ?? 0) > 0) && (
            <RebuyCard state={autoSell} now={now} />
          )}

          {/*
            A past event is read, not traded. Its whole record is the orders
            that went through it, newest first — which is what "what happened
            at 14:35" actually means, and it belongs at the bottom, under the
            numbers it explains.
          */}
          {/*
            Everything this window has done, newest first — the current one as
            much as a past one. A filled limit lands here the moment it fills,
            which is the only list it still belongs in.
          */}
          <OrderHistory
            orders={logged}
            live={liveIds}
            realised={realisedPnl}
          />

        </>
      )}

      {lookAhead && !market && (
        <div className="banner warn">
          Следующее окно ещё не опубликовано — оно появляется незадолго до
          старта.
        </div>
      )}

      {closing && (
        <SellSheet
          position={closing}
          bid={
            books[closing.outcome === 'Up' ? 'Up' : 'Down'].bids[0]?.price ?? null
          }
          avg={
            (closing.outcome === 'Up' ? localAvg.Up : localAvg.Down) ??
            closing.avgPrice
          }
          tick={market?.tickSize ?? 0.01}
          busy={busy}
          secondsLeft={secondsLeft}
          onClose={() => setClosing(null)}
          onSell={(price) => {
            const which: 'Up' | 'Down' = closing.outcome === 'Up' ? 'Up' : 'Down';
            const size = sellableShares(closing.size);
            setClosing(null);
            void place(which, 'SELL', price, size);
          }}
          onMarket={() => {
            const which: 'Up' | 'Down' = closing.outcome === 'Up' ? 'Up' : 'Down';
            const size = sellableShares(closing.size);
            setClosing(null);
            void marketSell(which, size);
          }}
        />
      )}

      {editing && (
        <OrderEditor
          row={editing}
          tick={market?.tickSize ?? 0.01}
          busy={busy}
          /*
            The price that would trade now: a resting buy meets the offer, a
            resting sell meets the bid. That is what "current" means to an
            order you are moving because you want it done.

            The side comes from the live order's own token, not from the row's
            printed label — those agree on this market and only on this one.
          */
          marketPrice={(() => {
            const side = sideForEdit(editing);
            if (side == null) return null;
            const level =
              editing.status === 'buying'
                ? books[side].asks[0]?.price
                : books[side].bids[0]?.price;
            return level != null ? Math.round(level * 100) : null;
          })()}
          onSave={(price, shares) => {
            const side = sideForEdit(editing);
            if (side == null) {
              setNote('Этот ордер не с этого события — его можно только снять');
              return;
            }
            void editOrder(
              editing.orderId as string,
              side,
              editing.status === 'buying' ? 'BUY' : 'SELL',
              price,
              shares,
            );
          }}
          onCancelOrder={() => {
            void cancel(editing.orderId as string);
            setEditing(null);
          }}
          onClose={() => setEditing(null)}
        />
      )}

      {note && <div className="banner info">{note}</div>}

      {tab !== 'settings' && (
        <div
          className={`dockgap${side == null ? ' short' : ''}`}
          aria-hidden
        />
      )}

      {/*
        The trading row is pinned to the bottom edge of the screen, not to the
        end of the page. It is the one thing here that is used rather than
        read, and a row that drifts up when the window is quiet is a row you
        have to look for.
      */}
      <div className={`dock${tab === 'settings' ? ' away' : ''}`}>
        {/*
          Nothing to buy with until a side is chosen.

          The terms — price, size, and the button that sends them — are only
          answers to "which side", and a row of them sitting under an
          unanswered question is a row that has to be ignored on every glance.
          Tapping a quote below opens it; tapping the lit one again puts it
          away.
        */}
        {side != null && (
        <>
        {/*
          The size is picked, not typed: a share of what this window may still
          take, one tap, against a keypad that covers the book you are pricing
          against. Tapping a side below fills the field to the hilt, so these
          are the ways of asking for less than everything.
        */}
        {sizingLimit && (
          <div
            className="limitpcts pcts big"
            onMouseDown={(e) => e.preventDefault()}
          >
            {[25, 50, 100].map((pct) => {
              // A share of what this window may still take, not of the whole
              // wallet: a hundred percent that the guard then refuses is a
              // button that lies about what it does.
              const shares =
                sizeBudget > 0
                  ? stakeShares(limitBasis, sizeBudget, pct / 100, minSize)
                  : null;
              return (
                <button
                  key={pct}
                  className={sizePct === pct ? 'on' : undefined}
                  disabled={shares == null}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => setSizePct(pct)}
                >
                  {pct}%
                </button>
              );
            })}
          </div>
        )}

        {/*
          And the same size said in money, but only down where money is the
          natural unit. At twenty cents a dollar is the venue's own five-share
          minimum, and above it every one of these buttons asks for the same
          thing the minimum already gives — so they only appear under it,
          where "a dollar of this" is a real choice of size.
        */}
        {sizingLimit && limitPriceNum > 0 && limitPriceNum < 0.2 && (
          <div className="limitpcts pcts" onMouseDown={(e) => e.preventDefault()}>
            {[1, 2, 3].map((usdAmount) => {
              const shares =
                limitBasis > 0
                  ? Math.max(usdAmount / limitBasis, minShares(limitBasis, minSize))
                  : null;
              return (
                <button
                  key={usdAmount}
                  disabled={shares == null}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => {
                    if (shares == null) return;
                    setSizePct(null);
                    setLimitSize(String(Math.round(shares * 10) / 10));
                  }}
                >
                  {usdAmount} $
                </button>
              );
            })}
          </div>
        )}
        {/*
          Price and size, and nothing else. Which side and whether to send it
          are the two quotes up by the charts — this one is only the terms,
          left under the thumb where they are edited.
        */}
        <div className="limitrow">
          {/*
            The one thing to do, next to the terms it will do it on. The side
            comes from whichever quote below is lit; without one there is
            nothing to send and the button says so by being dead.
          */}
          <button
            className={`buygo${
              side && affordable ? ` on ${side === 'Up' ? 'up' : 'down'}` : ''
            }`}
            disabled={busy || locked || limitBarred || side == null || !affordable}
            onClick={() => side && void placeLimit(side)}
          >
            {/*
              Dead, and saying why. Without a side there is nothing to send;
              without the money there is nothing to send it with — and the
              second of those used to look identical to a working button, so
              an order went out against a balance that was entirely reserved
              and came back refused.
            */}
            {side == null
              ? 'Купить'
              : limitCost <= 0
                ? 'цена и объём'
                : affordable
                  ? 'Купить'
                  : `нет ${usd(limitCost - freeCash)}`}
          </button>
          <div className="limitmid">
            <div className="limitprice">
              <button className="step" onClick={() => nudgeLimit(-1)}>
                −
              </button>
              {/*
                A cent either side, or hold it and type.

                The wheel that used to open on a tap is gone: it covered the
                book it was pricing against, and the price wanted is nearly
                always a cent or two from the one already in the field. A hold
                empties the field and opens the keyboard, which is the case the
                steps are bad at — a price several cents away, known exactly.
              */}
              <input
                className={typingPrice ? 'typed' : undefined}
                type="text"
                inputMode={typingPrice ? 'decimal' : 'none'}
                readOnly={!typingPrice}
                autoFocus={typingPrice}
                placeholder={askUp != null ? String(Math.round(askUp * 100)) : '¢'}
                value={limitPrice}
                onPointerDown={() => {
                  if (typingPrice) return;
                  heldRef.current = false;
                  holdRef.current = window.setTimeout(() => {
                    heldRef.current = true;
                    // Cleared, not selected: a hold is "I know the number",
                    // and the old one is in the way of typing it.
                    setLimitPrice('');
                    setTypingPrice(true);
                  }, HOLD_MS);
                }}
                onPointerUp={() => clearHold()}
                onPointerLeave={() => clearHold()}
                onPointerCancel={() => clearHold()}
                onContextMenu={(e) => e.preventDefault()}
                onChange={(e) =>
                  setLimitPrice(e.target.value.replace(',', '.').slice(0, 3))
                }
                onBlur={() => setTypingPrice(false)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') e.currentTarget.blur();
                }}
              />
              <button className="step" onClick={() => nudgeLimit(1)}>
                +
              </button>
            </div>
            {typingSize ? (
              /*
                The same box, opened. A typed size is nobody's share of
                anything, so the chip that was lit is let go — otherwise the
                next tick of the price would overwrite what was just typed.
              */
              <input
                className="limitsize typed"
                type="text"
                inputMode="decimal"
                autoFocus
                value={limitSize}
                onChange={(e) => {
                  setSizePct(null);
                  setLimitSize(e.target.value.replace(',', '.'));
                }}
                onBlur={() => setTypingSize(false)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') e.currentTarget.blur();
                }}
              />
            ) : (
              <button
                className="limitsize"
                onPointerDown={() => {
                  heldRef.current = false;
                  holdRef.current = window.setTimeout(() => {
                    heldRef.current = true;
                    setSizePct(null);
                    // Emptied, like the price: a hold means the number is
                    // known and the old one is only in the way.
                    setLimitSize('');
                    setTypingSize(true);
                  }, HOLD_MS);
                }}
                onPointerUp={() => clearHold()}
                onPointerLeave={() => clearHold()}
                onPointerCancel={() => clearHold()}
                onContextMenu={(e) => e.preventDefault()}
                onClick={() => {
                  // The hold already did something; the tap it ends with must
                  // not also toggle the chips underneath.
                  if (heldRef.current) {
                    heldRef.current = false;
                    return;
                  }
                  setSizingLimit((v) => !v);
                }}
              >
                {limitSize || limitDefaultSize.toFixed(0)}
              </button>
            )}
          </div>
        </div>
        </>
        )}

            {/*
              The two sides: what each costs to buy, and what is held on it.

              Always here, even while an older window is being read below —
              the quotes are how a side is chosen, and the dock is the only
              place they now live. The positions on it are the live window's;
              a closed window's are gone from the exchange, and what it came
              to is the order history further down.
            */}
            <PositionPair
              positions={livePositions}
              bids={{
                Up: books.Up.bids[0]?.price ?? null,
                Down: books.Down.bids[0]?.price ?? null,
              }}
              asks={{
                Up: books.Up.asks[0]?.price ?? null,
                Down: books.Down.asks[0]?.price ?? null,
              }}
              localAvg={localAvg}
              secondsLeft={secondsLeft}
              windowStart={windowStart}
              digits={coinDigits}
              lookAhead={lookAhead}
              onLookAhead={() => setLookAhead((v) => !v)}
              elapsed={elapsed}
              ceiling={ceiling}
              chosen={side}
              onPick={(which, price) => {
                // Tapping the side already chosen puts the terms away again,
                // which is the only way to close them.
                if (which === side) {
                  setSide(null);
                  setTypingPrice(false);
                  setSizingLimit(false);
                  return;
                }
                setSide(which);
                setLimitPrice(String(Math.round(price * 100)));
                // And the size the field is about to spend: all of it at an
                // ordinary price, a dollar of it under a dime. The shares
                // wanted here are what the window still has room for, and
                // typing that out was the last thing that was typed by hand.
                const opening =
                  sizeBudget > 0
                    ? openingSize(price, sizeBudget, minSize)
                    : { shares: null, pct: 100 };
                setSizePct(opening.pct);
                if (opening.shares != null) setLimitSize(String(opening.shares));
              }}
              onSell={sellPosition}
            />
      </div>
    </>
  );
}

/** Closed positions, newest first, stamped with the window they belonged to. */
/**
 * A working limit, opened to change.
 *
 * Price and size, large enough to hit with a thumb, because those are the only
 * two things an order is. Saving pulls it and places it again — the venue has
 * no other way: an order's terms are what it was signed with.
 */
/**
 * Moving a resting order to another price.
 *
 * One number, as large as the screen allows, with a step either side and a
 * wheel of prices under it — and one button that moves the order there. The
 * size is left alone: an order that is out at the wrong price is almost always
 * the right size at the wrong price, and every extra field between the price
 * and the button is a second the book has to move in.
 */
function OrderEditor({
  row,
  tick,
  busy,
  marketPrice,
  onSave,
  onCancelOrder,
  onClose,
}: {
  row: TradeRow;
  tick: number;
  busy: boolean;
  /** Where the book is for this order's side, in cents, if it is known. */
  marketPrice: number | null;
  onSave: (price: number, shares: number) => void;
  onCancelOrder: () => void;
  onClose: () => void;
}) {
  // The price of the order being moved — which for a resting sell is the sell,
  // not the buy it sits over.
  const opened = Math.round(
    ((row.status === 'buying' ? row.buyPrice : row.sellPrice) ?? 0) * 100,
  );
  const [cents_, setCents] = useState(opened);
  const step = Math.max(1, Math.round(tick * 100));
  const nudge = (d: number) => setCents((c) => Math.min(99, Math.max(1, c + d)));

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>
            <span className={row.outcome === 'Up' ? 'up' : 'down'}>{row.outcome}</span>{' '}
            {row.shares.toFixed(row.shares % 1 ? 1 : 0)}
          </h2>
          <button className="xbtn" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        <div className="pricepick">
          <button className="step big" onClick={() => nudge(-step)}>
            −
          </button>
          <div className="pricepick-now">
            <PriceSpinner value={cents_} onPick={setCents} />
            <span className="muted">¢</span>
          </div>
          <button className="step big" onClick={() => nudge(step)}>
            +
          </button>
        </div>

        {/*
          What the book is at right now, and one tap to be there. Moving an
          order is usually not "to seventy-seven" but "to whatever it takes" —
          and by the time that has been scrolled to, it is a different number.
        */}
        {marketPrice != null && (
          <button
            className="primary wide"
            disabled={busy}
            onClick={() => onSave(marketPrice / 100, row.shares)}
          >
            сейчас {marketPrice}¢
          </button>
        )}
        <button
          className="ghost wide"
          style={{ marginTop: 8 }}
          disabled={busy || cents_ === opened}
          onClick={() => onSave(cents_ / 100, row.shares)}
        >
          {cents_ === opened ? `стоит на ${opened}¢` : `перенести на ${cents_}¢`}
        </button>
        <button
          className="danger wide"
          style={{ marginTop: 8 }}
          disabled={busy}
          onClick={onCancelOrder}
        >
          Снять
        </button>
      </div>
    </div>
  );
}

/**
 * The way out of a position, priced.
 *
 * Everything behind it is dimmed, because this is the one decision on the
 * screen while it is open — and it is a decision with a clock on it, so the
 * clock is in the sheet rather than behind it. Five minutes is short enough
 * that "how long have I got" is half of "what price should I ask".
 *
 * Two ways out, in the order they are wanted. A price, which is the whole
 * point of the panel: chosen on the spinner, with what it pays after the fee
 * written under it, because the fee is charged in money and a number that
 * ignores it is not the number arriving in the wallet. And under that, out at
 * the book's own price, on one button that says what that price is — it used
 * to be two controls, a chip that loaded the bid into the field and a button
 * that crossed the book, which are the same intention twice.
 */
function SellSheet({
  position,
  bid,
  avg,
  tick,
  busy,
  secondsLeft,
  onClose,
  onSell,
  onMarket,
}: {
  position: NativePosition;
  /** Top of the bid side: what closing pays right now. */
  bid: number | null;
  /** What the shares cost, from this window's own orders where it knows. */
  avg: number;
  tick: number;
  busy: boolean;
  /** How long this window has left, which is how long the price has. */
  secondsLeft: number;
  onClose: () => void;
  onSell: (price: number) => void;
  onMarket: () => void;
}) {
  const size = sellableShares(position.size);
  const step = Math.max(1, Math.round(tick * 100));
  const opened = Math.min(99, Math.max(1, Math.round((bid ?? avg ?? 0.5) * 100)));
  const [cents_, setCents] = useState(opened);

  const nudge = (d: number) => setCents((c) => Math.min(99, Math.max(1, c + d)));

  // What the chosen price would pay, less the fee the venue takes out of it.
  const pays = netSellPrice(cents_ / 100) * size;
  const cost = avg > 0 ? avg * size : 0;

  // What the book would actually pay for the lot right now, fee taken out —
  // the market button's own number, which is not the same as the asked price's.
  const marketPays = bid != null ? netSellPrice(bid) * size : 0;

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet sellsheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>
            <span className={position.outcome === 'Up' ? 'up' : 'down'}>
              {position.outcome}
            </span>{' '}
            {size.toFixed(size % 1 ? 1 : 0)}
            {avg > 0 && <em className="muted"> по {cents(avg)}</em>}
          </h2>
          <button className="xbtn" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        {/*
          How long the window has left.

          The price being chosen here is only worth what it is worth for as
          long as the market is open, and inside the last minute the whole
          question changes — so the clock is in the panel, coloured the way it
          is coloured on the desk.
        */}
        <div className={`sellclock ${clockTone(secondsLeft, false)}`}>
          <span className="muted">до конца события</span>
          <b>{clock(Math.max(0, secondsLeft))}</b>
        </div>

        {/* The price, and nothing beside it competing for the eye. */}
        <div className="sellprice">
          <div className="pricepick">
            <button className="step big" onClick={() => nudge(-step)}>
              −
            </button>
            <div className="pricepick-now">
              <PriceSpinner value={cents_} onPick={setCents} />
            </div>
            <button className="step big" onClick={() => nudge(step)}>
              +
            </button>
          </div>
          {/* What that price pays, and what it makes on what it cost. */}
          <div className="sellpays">
            <span className="muted">получишь</span>
            <b>{usd(pays)}</b>
            {cost > 0 && (
              <i className={pays >= cost ? 'up' : 'down'}>
                {signedUsd(pays - cost)}
              </i>
            )}
          </div>
        </div>

        <button
          className="primary wide"
          disabled={busy}
          onClick={() => onSell(cents_ / 100)}
        >
          Продать {size.toFixed(size % 1 ? 1 : 0)} по {cents_}¢
        </button>

        {/*
          And the other kind of exit: not a price at all, but out — through the
          book, past our own resting offers, whatever it takes. The price it
          would go at is on the button, because that is the only thing anyone
          wants to know before pressing it.
        */}
        <button
          className="sellmarket wide"
          disabled={busy || bid == null}
          onClick={onMarket}
        >
          <span>По рынку</span>
          <b>{bid != null ? cents(bid) : '—'}</b>
          {bid != null && <i className="muted">{usd(marketPays)}</i>}
        </button>
      </div>
    </div>
  );
}

/**
 * The price, scrolled.
 *
 * A list of prices under the number was two things saying the same thing, and
 * the number was the one being read. So the number is the list: flick it up or
 * down and it counts, snapped so a flick always lands on a price. The steps
 * either side are for the last cent.
 */
const SPIN_H = 68;

function PriceSpinner({
  value,
  onPick,
}: {
  value: number;
  onPick: (cents: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  /** Set while a scroll of ours is what moved the value, so it is left alone. */
  const spinning = useRef(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (spinning.current) {
      spinning.current = false;
      return;
    }
    el.scrollTop = (value - 1) * SPIN_H;
  }, [value]);

  return (
    <div
      className="pricespin"
      ref={ref}
      onScroll={() => {
        const el = ref.current;
        if (!el) return;
        const c = Math.min(99, Math.max(1, Math.round(el.scrollTop / SPIN_H) + 1));
        if (c === value) return;
        spinning.current = true;
        onPick(c);
      }}
    >
      {Array.from({ length: 99 }, (_, i) => i + 1).map((c) => (
        <div className="spinnum" key={c}>
          {c}
        </div>
      ))}
    </div>
  );
}


/**
 * The pulse bot's own panel.
 *
 * Four readings across the top, because the rule is a confluence and the only
 * useful thing to see is which of the four is currently disagreeing. Under
 * them the books, and under those what it is doing right now — or, more often,
 * why it is not.
 */
/**
 * A number you can actually retype.
 *
 * A controlled number input reads what is in it on every keystroke, and the
 * first keystroke of retyping is deleting what is there — which parses as
 * nothing, gets refused or read as zero, and puts the old value straight back.
 * The field then cannot be cleared at all: the only way to change 100 into 20
 * is to fumble with the caret.
 *
 * So the text belongs to the field while it is being edited and to the setting
 * the rest of the time. It commits when the field is left or Enter is pressed,
 * anything that is not a number is dropped, and focusing selects what is there
 * so that typing replaces it.
 */
function NumField({
  label,
  value,
  onCommit,
  className = 'field',
}: {
  label: string;
  value: number | string;
  onCommit: (value: number) => void;
  /** The rungs of the ladder wear their own, narrower, shape. */
  className?: string;
}) {
  const [text, setText] = useState<string | null>(null);

  const commit = () => {
    const raw = text;
    setText(null);
    if (raw == null) return;
    const parsed = Number(raw.replace(',', '.').trim());
    if (raw.trim() === '' || !Number.isFinite(parsed)) return;
    onCommit(parsed);
  };

  return (
    <label className={className}>
      <span>{label}</span>
      <input
        type="text"
        inputMode="decimal"
        value={text ?? String(value)}
        onFocus={(e) => {
          setText(String(value));
          e.currentTarget.select();
        }}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur();
        }}
      />
    </label>
  );
}

/**
 * One five-minute candle, opened.
 *
 * A candle and a window are the same thing seen two ways, so tapping one asks
 * what was done here — every order the app sent into that window, by hand or
 * by a rule, from its own log.
 */
function WindowRead({
  windowStart,
  orders,
  onClose,
}: {
  windowStart: number;
  orders: LoggedOrder[];
  onClose: () => void;
}) {
  const done = orders.filter((o) => o.matched > 1e-6);

  return (
    <div className="card tight windowread">
      <div className="counterhead">
        <span>Свеча {clockOf(windowStart)}</span>
        <button className="shut" onClick={onClose}>
          ✕
        </button>
      </div>

      {done.length > 0 ? (
        <div className="probelist">
          {done.map((o) => (
            <div className="proberow" key={o.id}>
              <span className="probewhen">
                {/* Milliseconds, as the log stamps them. Multiplying by a
                    thousand here put every order at an arbitrary hour. */}
                {new Date(o.placedAt).toLocaleTimeString('ru', {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </span>
              <span className={o.action === 'BUY' ? 'up' : 'down'}>
                {o.action === 'BUY' ? 'куп' : 'прод'}
              </span>
              <span className="muted">
                {o.outcome} {o.matched.toFixed(1)} ·{' '}
                {cents(o.fillPrice ?? o.price)}
              </span>
              <span className="probemark">·</span>
              <b className="muted">{o.auto ? 'бот' : 'рука'}</b>
            </div>
          ))}
        </div>
      ) : (
        <div className="muted" style={{ fontSize: 11 }}>
          Сделок в этом окне не было.
        </div>
      )}

    </div>
  );
}

/**
 * Every order that went through one event, as it happened.
 *
 * The rows above pair a buy with its sell and answer "what did this round
 * make". This answers the other question — what was actually sent, at what
 * price, and what became of it — which is the only way to see a limit that
 * never filled or a sale that went out in three pieces.
 */
function OrderHistory({
  orders,
  live,
  realised,
}: {
  orders: LoggedOrder[];
  /**
   * Ids the venue says are still on the book.
   *
   * The record's own word for an order can be behind the exchange's, and the
   * one direction that matters is this one: an order called "снят" while it is
   * resting and filling is the record saying the opposite of the truth. Where
   * the exchange still lists it, the exchange wins.
   */
  live: Set<string>;
  realised: number;
}) {
  const [open, setOpen] = useState(false);
  /*
    Only what happened and what is still happening.

    A pulled order moved no money and holds no risk — it is the record of a
    price that was asked for and then was not. The ladder pulls and re-places
    on every rung it climbs, so those rows are most of the list, and reading
    the list for "what did this window actually do" meant reading past them.
    A part-filled order that was then pulled is not one of them: it traded, so
    it stays.
  */
  const rows = [...orders]
    .filter((o) => stateOf(o, live) !== 'cancelled' || o.matched > 1e-9)
    .sort((a, b) => b.placedAt - a.placedAt);
  if (rows.length === 0) return null;

  return (
    <div className="hist">
      {/*
        Shut by default. It is the record of what happened, which is read after
        the fact and almost never while a window is running — and open it was
        half the screen between the price and the buttons.
      */}
      <button className="histhead" onClick={() => setOpen((v) => !v)}>
        <span className="muted">{rows.length}</span>
        <span className={realised >= 0 ? 'up sessum' : 'down sessum'}>
          {realised !== 0 ? signedUsd(realised) : ''}
        </span>
        <span className="foldarrow" aria-hidden>
          {open ? '−' : '+'}
        </span>
      </button>

      {open &&
        rows.map((o) => (
          <div className="histrow" key={o.id}>
            <span className={o.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
              {o.outcome || '—'}
            </span>
            <span className={o.action === 'BUY' ? 'hist-buy' : 'hist-sell'}>
              {cents(o.price)}
              <i>×</i>
              {(o.matched > 0 ? o.matched : o.size).toFixed(1)}
            </span>
            <span className="muted histtime">
              {new Date(o.placedAt).toLocaleTimeString('ru-RU', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
              })}
            </span>
            <span className={`histstate ${statusTone(stateOf(o, live))}`}>
              {statusWord(stateOf(o, live))}
            </span>
          </div>
        ))}
    </div>
  );
}

/**
 * The order's state, with the exchange's listing on top of the record.
 *
 * Only ever upgrades a settled row back to working: everything else the record
 * knows better, because it also knows what filled at what price.
 */
const stateOf = (o: LoggedOrder, live: Set<string>) =>
  o.orderId && live.has(o.orderId) && o.status === 'cancelled'
    ? o.matched > 1e-9
      ? 'partial'
      : 'resting'
    : o.status;

const statusWord = (status: string) =>
  status === 'filled'
    ? 'исполнен'
    : status === 'partial'
      ? 'частично'
      : status === 'cancelled'
        ? 'снят'
        : 'в стакане';

const statusTone = (status: string) =>
  status === 'filled' ? 'up' : status === 'cancelled' ? 'muted' : 'warn';

/**
 * What the buy-back is doing, while it is doing it.
 *
 * A rule that waits silently is indistinguishable from one that is broken, and
 * that ambiguity cost several rounds of guessing. So the wait is drawn: where
 * the price sold, where it has to fall to, where it is now, and how close it
 * has ever come. The seconds-since-checked tick every second, which is the only
 * honest proof that anything is still running.
 */
function RebuyCard({ state, now }: { state: AutoSellState; now: number }) {
  return (
    <div className="card tight">
      <div className="listhead">
        <span>Автодокуп</span>
        <span className="muted">
          {state.rebuys.length > 0 ? `ждёт ${state.rebuys.length}` : 'свободен'}
        </span>
      </div>

      {state.rebuys.map((r, i) => (
        <RebuyWait key={`${r.soldAt}-${i}`} rebuy={r} now={now} />
      ))}

      {(state.rebuysDone ?? []).slice(0, 3).map((d, i) => (
        <RebuyDoneRow key={`${d.at}-${i}`} done={d} />
      ))}
    </div>
  );
}

function RebuyWait({ rebuy, now }: { rebuy: AutoSellRebuy; now: number }) {
  const sold = rebuy.soldAt;
  const target = rebuy.trigger;
  const ask = rebuy.lastAsk ?? null;
  const best = rebuy.bestAsk ?? null;

  // How far the price has travelled from where it sold toward the target.
  const span = Math.max(sold - target, 1e-6);
  const pos = (v: number) => Math.min(100, Math.max(0, ((sold - v) / span) * 100));

  const stale = rebuy.lastCheckAt > 0 && now - rebuy.lastCheckAt > 15_000;
  const gap = ask != null ? ask - target : null;

  return (
    <div className="rebuy">
      <div className="rebuy-top">
        <span className={rebuy.outcome === 'Down' ? 'down' : 'up'}>
          {rebuy.outcome || '—'}
        </span>
        <span className="rebuy-size">
          {rebuy.remaining.toFixed(0)} долей · клип {Math.round(rebuy.lot)}
        </span>
        <span className={`rebuy-live ${rebuy.note ? 'down' : stale ? 'warn' : 'up'}`}>
          <i className="pulse" />
          {rebuy.note
            ? rebuy.note
            : rebuy.lastCheckAt === 0
              ? 'первая проверка…'
              : `${Math.max(0, Math.round((now - rebuy.lastCheckAt) / 1000))}с`}
        </span>
      </div>

      <div className="rebuy-track">
        {best != null && best < sold && (
          <i className="best" style={{ left: `${pos(best)}%` }} />
        )}
        {ask != null && <i className="now" style={{ left: `${pos(ask)}%` }} />}
      </div>

      <div className="rebuy-scale">
        <span>продано {cents(sold)}</span>
        <span className={gap != null && gap <= 0 ? 'up' : 'muted'}>
          {ask != null ? `сейчас ${cents(ask)}` : 'цены нет'}
          {gap != null && gap > 0 ? ` · ещё ${Math.round(gap * 100)}¢` : ''}
        </span>
        <span className="warn">купить {cents(target)}</span>
      </div>

      {best != null && (
        <div className="muted rebuy-best">
          ближе всего подходил к {cents(best)} · проверок {rebuy.checks}
        </div>
      )}
    </div>
  );
}

function RebuyDoneRow({ done }: { done: AutoSellRebuyDone }) {
  const bought = done.result === 'куплено';
  return (
    <div className="ledger">
      <span className={done.outcome === 'Down' ? 'down' : 'up'}>
        {done.outcome || '—'}
      </span>
      <span className="ledger-main">
        {done.shares.toFixed(0)} · {cents(done.soldAt)} → {cents(done.trigger)}
      </span>
      <span className={`ledger-note ${bought ? 'up' : 'warn'}`}>
        {done.result}
        {!bought && done.bestAsk != null ? ` · дошло до ${cents(done.bestAsk)}` : ''}
      </span>
    </div>
  );
}

/**
 * The rules, as switches only.
 *
 * What each rule *is* — the rungs, the retry interval, the percentages — lives
 * in the settings tab. Those are set once and left; the switches are flipped
 * while trading, so they are the only part that earns space on the desk.
 */
function RuleBar({
  state,
  settings,
  exposure,
  onChange,
  onNote,
}: {
  state: AutoSellState;
  settings: ManualSettings;
  exposure: Exposure;
  onChange: (next: ManualSettings) => void;
  onNote: (text: string | null) => void;
}) {
  const push = useCallback(
    (next: ManualSettings) => {
      onChange(next);
      void PolyBot.autoSellUpdate({
        enabled: next.autoSellEnabled,
        ladder: next.autoSellLadder,
        retryEverySec: next.autoSellRetrySec,
        watchSec: next.autoSellWatchSec,
        chime: next.chime,
        dipRescue: next.autoSellDipRescue,
        ladderLeadSec: next.autoSellLeadSec,
        ladderStepSec: next.autoSellStepSec,
        percentMode: next.autoSellPercentMode,
        profitPct: next.autoSellProfitPct,
        sliceGapSec: next.autoSellSliceGapSec,
        panicSec: next.autoSellPanicSec,
        closeFloor: next.autoSellCloseFloor,
        lateFloor: next.autoSellLateFloor,
        lateBandSec: next.autoSellLateBandSec,
        ride: next.autoSellRide,
        rideWaitMs: next.autoSellRideMs,
      }).catch((e) => onNote(e instanceof Error ? e.message : String(e)));
    },
    [onChange, onNote],
  );

  const covered = state.rows.filter((r) => r.status === 'покрыто').length;
  /** A position the rule has not managed to cover, if there is one. */
  const stuck = state.rows.find(
    (r) => r.status !== 'покрыто' && r.status !== 'выставлено' && r.status !== 'ждёт шага',
  );
  const rung = state.rows.length > 0 ? Math.max(...state.rows.map((r) => r.target)) : null;
  /** The auto-sell's own line: on but idle and on but stuck look alike. */
  const sellNote = !settings.autoSellEnabled
    ? 'выключена'
    : state.lastFault
      ? state.lastFault
      : !state.running
        ? 'запускается…'
        : state.rows.length === 0
          ? 'ждём покупку'
          : stuck
            ? // A position the rule cannot cover says why, right here. "Ждём
              // покупку" while a bought lot sat with no sell was the least
              // useful thing this line could have said.
              `${stuck.outcome}: ${stuck.status}`
            : `${rung != null ? `${Math.round(rung * 100)}¢ · ` : ''}` +
              `покрыто ${covered}/${state.rows.length} · ${ago(state.lastSweepAt)}`;

  return (
    <div className="card tight">
      {/*
        Five switches across, not five rows down. Each is a rule that is either
        on or off with one number attached, and a full row apiece cost a third
        of the screen to say so.
      */}
      <div className="rules-bar">
        <button
          className={`ruletile${settings.autoSellEnabled ? ' on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellEnabled: !settings.autoSellEnabled })
          }
        >
          <span className={`switch mini ${settings.autoSellEnabled ? 'on' : ''}`} />
          <b>продажа</b>
          <i>
            {settings.autoSellPercentMode
              ? `+${Math.round(settings.autoSellProfitPct * 100)}%`
              : 'лесенкой'}
          </i>
        </button>

        <button
          className={`ruletile${settings.autoSellDipRescue ? ' on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellDipRescue: !settings.autoSellDipRescue })
          }
        >
          <span className={`switch mini ${settings.autoSellDipRescue ? 'on' : ''}`} />
          <b>спасение</b>
          <i>было &lt;33¢ → 1-я ступень</i>
        </button>

        <button
          className={`ruletile${settings.chime ? ' on' : ''}`}
          onClick={() => push({ ...settings, chime: !settings.chime })}
        >
          <span className={`switch mini ${settings.chime ? 'on' : ''}`} />
          <b>звук</b>
          <i>вверх · вниз · монета</i>
        </button>

        <button
          className={`ruletile${settings.limitLadder ? ' on' : ''}`}
          onClick={() => onChange({ ...settings, limitLadder: !settings.limitLadder })}
        >
          <span className={`switch mini ${settings.limitLadder ? 'on' : ''}`} />
          <b>лесенка</b>
          <i>
            +{LIMIT_LADDER_COUNT} · {Math.round(settings.limitLadderStep * 100)}¢
          </i>
        </button>

        {/*
          What happens when the book reaches the rung: sell it, or follow it.

          Resting an offer on the rung is what the ladder has always done, and
          it is also what caps the run at the rung. Riding watches the bid
          instead and sells once the climb has stopped — the slider below is
          how long "stopped" is.
        */}
        <button
          className={`ruletile${settings.autoSellRide ? ' on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellRide: !settings.autoSellRide })
          }
        >
          <span className={`switch mini ${settings.autoSellRide ? 'on' : ''}`} />
          <b>веду цену</b>
          <i>
            {settings.autoSellRide
              ? `пауза ${(settings.autoSellRideMs / 1000).toFixed(1)} с`
              : 'лимиткой на ступени'}
          </i>
        </button>

        <button
          className={`ruletile${settings.exposureGuard ? ' on' : ''}`}
          onClick={() =>
            onChange({ ...settings, exposureGuard: !settings.exposureGuard })
          }
        >
          <span className={`switch mini ${settings.exposureGuard ? 'on' : ''}`} />
          <b>{(exposure.pct * 100).toFixed(exposure.pct < 0.1 ? 1 : 0)}% на окно</b>
          <i className={settings.exposureGuard && exposure.full ? 'warn' : undefined}>
            {!settings.exposureGuard
              ? 'выкл'
              : exposure.full
                ? 'лимит'
                : usd(exposure.room)}
          </i>
        </button>

      </div>

      {/*
        How long the climb has to stand still before it counts as over.

        A slider because the right answer is not a fact about the market but a
        taste: half a second sells into every pause in the tape, ten seconds
        holds through the pause that was the top. The two prices that override
        it are not adjustable — ninety-eight is taken at once, and ninety-three
        while there is still more than half a minute to lose it in.
      */}
      {settings.autoSellRide && (
        <label className="rideslider">
          <span className="muted">
            держу, пока растёт · пауза {(settings.autoSellRideMs / 1000).toFixed(1)} с
          </span>
          <input
            type="range"
            min={500}
            max={10000}
            step={250}
            value={settings.autoSellRideMs}
            onChange={(e) =>
              push({ ...settings, autoSellRideMs: Number(e.target.value) })
            }
          />
          <span className="muted rideends">
            <i>0,5</i>
            <i>98¢ сразу · 93¢ если больше 35 с · дешёвые без паузы</i>
            <i>10</i>
          </span>
        </label>
      )}

      {/* The sell rule is the one that can be quietly stuck, so it still talks. */}
      {settings.autoSellEnabled && (
        <div className={`rules-note ${state.lastFault || stuck ? 'warn' : 'muted'}`}>
          {sellNote}
        </div>
      )}

      {state.rows.length > 0 && (
        <div className="autosell-rows">
          {state.rows.map((r) => (
            <div className="ledger" key={r.asset}>
              <span className={r.outcome === 'Up' ? 'up' : 'down'}>{r.outcome}</span>
              <span className="ledger-main">
                {r.size.toFixed(1)} → {Math.round(r.target * 100)}¢
              </span>
              <span
                className={`ledger-note ${
                  r.status === 'покрыто'
                    ? 'up'
                    : r.status === 'выставлено' || r.status === 'у бота'
                      ? 'muted'
                      : 'warn'
                }`}
                title={r.lastError ?? undefined}
              >
                {r.status}
                {r.attempts > 0 && r.status !== 'покрыто' ? ` ×${r.attempts}` : ''}
                {r.lastTryAt > 0 && r.status !== 'покрыто'
                  ? ` · ${ago(r.lastTryAt)}`
                  : ''}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * The window's two sides, and what it comes to.
 *
 * Up on the left, Down on the right, each a share count over what it cost, and
 * between them the money. Two numbers live there: what selling both right now
 * would pay after the fee, and — when both sides are held — what doing nothing
 * pays instead, which is one of exactly two figures because only one side can
 * settle at a dollar. When the worse of those two is still positive the window
 * is already won, and the panel says so rather than leaving it to be worked
 * out from two rows of percentages.
 */
/**
 * What Polymarket prints over its own chart, over our clock instead.
 *
 * Two prices and an arrow: where this five minutes opened — the number the
 * market settles against — where the price is now, and the whole-dollar
 * distance between them. That distance *is* the bet, so it sits directly
 * above the countdown, which is the other half of the same decision.
 *
 * It updates four times a second. The device holds both numbers in memory —
 * the open cannot change once the window has started, and the live end comes
 * off the socket that carries the same sixty-second average once a second —
 * so asking this often costs nothing and the readout never lags the tick by
 * more than a quarter of a second.
 */
function WindowMark({
  windowStart,
  digits = 0,
}: {
  windowStart: number;
  /** How finely this coin's price is printed. */
  digits?: number;
}) {
  const [mark, setMark] = useState<{
    target?: number | null;
    price?: number | null;
  } | null>(null);

  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.polyMark({ windowStart })
        .then((m) => {
          if (!cancelled) setMark(m);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 250);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [windowStart]);

  const move = openMark(mark?.target, mark?.price, digits);

  return (
    <div className="pairmark">
      <span className="pairmarkline">
        {bigPrice(mark?.target, digits)} → {bigPrice(mark?.price, digits)}
      </span>
      <span className={`pairmarkmove ${move ? move.way : 'muted'}`}>
        {move ? `${move.arrow} ${move.text}` : '—'}
      </span>
    </div>
  );
}

function PositionPair({
  positions,
  bids,
  asks,
  localAvg,
  secondsLeft,
  windowStart,
  digits,
  lookAhead,
  onLookAhead,
  elapsed,
  ceiling,
  chosen,
  onPick,
  onSell,
}: {
  positions: NativePosition[];
  /** Top of the bid side per outcome, for pricing what a close would pay. */
  bids: { Up: number | null; Down: number | null };
  /** And the offers, which are what a buy on that side would pay. */
  asks: { Up: number | null; Down: number | null };
  /** What this window's own orders say each side cost, when the API lags. */
  localAvg: { Up: number | null; Down: number | null };
  secondsLeft: number;
  /** The live window, which is the one the readout above the clock is about. */
  windowStart: number;
  /** How finely this coin's price is printed over the clock. */
  digits: number;
  lookAhead: boolean;
  onLookAhead: () => void;
  /** How far into the window the desk is trading, for the early ceiling. */
  elapsed: number;
  /** The dearest a buy may be right now; a quote over it loads this instead. */
  ceiling: number;
  /** The side currently chosen to buy, which is lit. */
  chosen: 'Up' | 'Down' | null;
  onPick: (which: 'Up' | 'Down', price: number) => void;
  onSell: (position: NativePosition) => void;
}) {
  const leg = (name: 'Up' | 'Down') => {
    const mine = positions.filter((p) => p.outcome === name);
    if (mine.length === 0) return null;
    const bid = bids[name];
    const size = mine.reduce((a, p) => a + p.size, 0);
    const cost = mine.reduce((a, p) => a + p.size * p.avgPrice, 0);
    // The bid, and only the bid. This number answers "what does closing pay",
    // and closing pays what someone is bidding — the data API's own price is a
    // minute behind, which on a five-minute window is the difference between
    // "up eighty cents" and a red number from before the move, and with no bid
    // at all it kept showing a comfortable loss for shares nobody would take.
    const now = bid ?? 0;
    // And what it cost: the app's own record of this window's buys, which is
    // true the instant they fill, falling back to the API's average for a
    // position it did not place itself.
    const avg = cost > 0 ? cost / size : (localAvg[name] ?? 0);

    const standing = positionPnl(size, avg, now);

    return {
      position: mine[0],
      size,
      avg,
      // What selling it right now would pay, less the taker fee — the money,
      // not the mark the exchange shows.
      pnl: standing.pnl,
      // And the money itself. The change alone answers "how is it going" and
      // not "how much is there", which is the number a decision to close is
      // actually made on.
      net: standing.net,
      // The cost is the half that cannot be guessed; without it there is no
      // profit to show, only a price. No bid is not the same as no answer:
      // nothing bid means closing pays nothing, and that is the answer.
      priced: avg > 0,
    };
  };

  const up = leg('Up');
  const down = leg('Down');

  /*
    One side, one column: what it costs to buy now, and — when there is one —
    the position on it.

    The two meanings are two buttons rather than one. The price picks the side
    and loads the terms into the dock, which is the ordinary thing to do here;
    the position under it sells, at the book, past our own resting offers. A
    single button carrying both was ambiguous exactly when it mattered.
  */
  const side = (name: 'Up' | 'Down', held: ReturnType<typeof leg>) => {
    const ask = asks[name];
    // A side quoting over the early ceiling still has a price worth loading —
    // the highest one the rule allows. Refusing the tap left the field empty
    // and the decision unmade; this leaves a bid in it, which is what a buyer
    // at a capped price would place anyway.
    const barred = ask != null && buyBarred(ask, elapsed);
    const wanted = barred ? ceiling : ask;

    return (
      <div
        className={`pairleg ${name === 'Up' ? 'up' : 'down'}${
          chosen === name ? ' on' : ''
        }`}
      >
        <button
          className="pairpick"
          disabled={ask == null}
          onClick={() => wanted != null && onPick(name, wanted)}
        >
          <b>{name}</b>
          <span className="pairask">{ask != null ? cents(ask) : '—'}</span>
          {barred && <span className="pairover">→ {cents(ceiling)}</span>}
        </button>

        {held && (
          <button className="pairsell" onClick={() => onSell(held.position)}>
            <span className="pairhold">
              {held.size.toFixed(1)} · {held.avg > 0 ? cents(held.avg) : '…'}
            </span>
            {/* What closing it pays, and what that is against what it cost. */}
            <span className={`pairpnl ${held.pnl >= 0 ? 'up' : 'down'}`}>
              <i>{usd(held.net)}</i>
              {held.priced ? signedUsd(held.pnl) : '…'}
            </span>
          </button>
        )}
      </div>
    );
  };

  return (
    <div className="pair">
      {side('Up', up)}

      {/*
        The clock and the open sit between the two sides, where the decision is
        actually made: this side or that one, and how long have I got.
      */}
      <div className="pairmid">
        <WindowMark windowStart={windowStart} digits={digits} />
        {/*
          The clock is the switch between this window and the next. They are
          the same question — which five minutes am I trading — so it is one
          control, and the countdown is the obvious thing to press.
        */}
        <button
          className={`pairclock ${clockTone(secondsLeft, lookAhead)}`}
          onClick={onLookAhead}
        >
          {/*
            Looking ahead, the number is how long until that event opens —
            which is this window's own remainder, since one starts where the
            other ends. It used to read a flat 5:00, the next window's length,
            which is true of every window and answers nothing: the question
            you press this button to ask is "when can I trade it".
          */}
          {clock(secondsLeft)}
        </button>
        {lookAhead && <span className="pairnext">до начала</span>}
      </div>

      {side('Down', down)}
    </div>
  );
}

function ManualSettingsForm({
  settings,
  onChange,
  onNote,
}: {
  settings: ManualSettings;
  onChange: (next: ManualSettings) => void;
  onNote: (text: string | null) => void;
}) {
  /** Rule settings have to reach the native side, not just the store. */
  const push = (next: ManualSettings) => {
    onChange(next);
    void PolyBot.autoSellUpdate({
      enabled: next.autoSellEnabled,
      ladder: next.autoSellLadder,
      retryEverySec: next.autoSellRetrySec,
      watchSec: next.autoSellWatchSec,
      chime: next.chime,
      dipRescue: next.autoSellDipRescue,
      ladderLeadSec: next.autoSellLeadSec,
      ladderStepSec: next.autoSellStepSec,
      percentMode: next.autoSellPercentMode,
      profitPct: next.autoSellProfitPct,
      sliceGapSec: next.autoSellSliceGapSec,
      panicSec: next.autoSellPanicSec,
      closeFloor: next.autoSellCloseFloor,
      lateFloor: next.autoSellLateFloor,
      lateBandSec: next.autoSellLateBandSec,
      ride: next.autoSellRide,
      rideWaitMs: next.autoSellRideMs,
    }).catch((e) => onNote(e instanceof Error ? e.message : String(e)));
  };

  const setRung = (index: number, value: string) => {
    const cents = Number(value.replace(',', '.'));
    if (!Number.isFinite(cents)) return;
    push({
      ...settings,
      autoSellLadder: settings.autoSellLadder.map((r, i) =>
        i === index ? cents / 100 : r,
      ),
    });
  };

  return (
    <>
    <Fold
      title="Автопродажа"
      note={
        settings.autoSellEnabled
          ? settings.autoSellPercentMode
            ? `+${Math.round(settings.autoSellProfitPct * 100)}%`
            : 'лесенкой'
          : 'выключена'
      }
    >
      {/*
        Two ways to price an exit. The ladder asks a fixed price at a fixed
        minute, which ignores what the position cost — 84¢ is a good sale bought
        at 60¢ and a poor one bought at 82¢. Percent prices off the buy instead.
      */}
      <div className="segmented" style={{ marginBottom: 10 }}>
        <button
          className={settings.autoSellPercentMode ? '' : 'active'}
          onClick={() => push({ ...settings, autoSellPercentMode: false })}
        >
          Лесенкой
        </button>
        <button
          className={settings.autoSellPercentMode ? 'active' : ''}
          onClick={() => push({ ...settings, autoSellPercentMode: true })}
        >
          По проценту
        </button>
      </div>

      {settings.autoSellPercentMode ? (
        <div className="fields">

          <NumField
            label="плюс, %"
            value={Math.round(settings.autoSellProfitPct * 100)}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellProfitPct: n / 100,
                })
            }
          />

          <NumField
            label="пауза, с"
            value={settings.autoSellSliceGapSec}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellSliceGapSec: n,
                })
            }
          />

          <NumField
            label="финал, ¢"
            value={Math.round(settings.autoSellCloseFloor * 100)}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellCloseFloor:
                  n / 100,
                })
            }
          />

          <NumField
            label="до финала, ¢"
            value={Math.round(settings.autoSellLateFloor * 100)}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellLateFloor: n / 100,
                })
            }
          />

          <NumField
            label="полоса, с"
            value={settings.autoSellLateBandSec}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellLateBandSec: n,
                })
            }
          />

          <NumField
            label="финал за, с"
            value={settings.autoSellPanicSec}
            onCommit={(n) =>
              push({
                  ...settings,
                  autoSellPanicSec: n,
                })
            }
          />
        </div>
      ) : (
        <>
      <div className="fields">
      <NumField
        label="шаг лимиток, ¢"
        value={Math.round(settings.limitLadderStep * 100)}
        onCommit={(n) =>
          onChange({
              ...settings,
              limitLadderStep: n / 100,
            })
        }
      />

      <NumField
        label="упреждение, с"
        value={settings.autoSellLeadSec}
        onCommit={(n) =>
          push({
              ...settings,
              autoSellLeadSec: n,
            })
        }
      />

      {/*
        How long one rung holds. A minute spends the five over the whole
        window; thirty seconds spends them by the halfway mark, asking the
        higher prices while there is still time to reach them.
      */}
      <NumField
        label="ступень, с"
        value={settings.autoSellStepSec}
        onCommit={(n) =>
          push({
              ...settings,
              autoSellStepSec: n,
            })
        }
      />
      </div>

      <div className="rungs">
        {settings.autoSellLadder.map((price, i) => (
          <NumField
            key={i}
            className="rung"
            label={`${i + 1}`}
            value={Math.round(price * 100)}
            onCommit={(n) => setRung(i, String(n))}
          />
        ))}
      </div>

        </>
      )}

    </Fold>
    </>
  );
}
