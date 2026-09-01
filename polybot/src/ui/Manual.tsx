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
  breakEvenPrice,
  limitLadder,
  netSellPrice,
  positionPnl,
  potentialProfit,
  signedUsd,
  targetPrice,
  usd,
} from '../core/money';
import { bySide, pnlOf, summarise, traded } from '../core/probe';
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
  type NativeMarket,
  type PulseRound,
  type PulseState,
  type ProbeState,
  type ProbeOffer,
  type ProbeRound,
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

/**
 * The window's phase, as a colour on its clock.
 *
 * Amber while the first minute settles, green through the middle where a
 * position has room to work, red in the last minute — where the rule stops
 * holding out for a margin and the only thing left to do is get out.
 */
/**
 * Which of the three entries the trend rule is set to, for the account that
 * is trading. The two accounts keep their own, so a tab is lit as live only
 * when the account actually buying is on that entry.
 */
const probeMode = (
  state: { inside: boolean; fade: boolean; realInside?: boolean;
           realFade?: boolean; live: boolean } | null,
): 'line' | 'fade' | 'inside' => {
  if (!state) return 'line';
  const inside = state.live ? (state.realInside ?? false) : state.inside;
  const fade = state.live ? (state.realFade ?? false) : state.fade;
  return inside ? 'inside' : fade ? 'fade' : 'line';
};

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
  const [pickingPrice, setPickingPrice] = useState(false);
  /**
   * The side the dock is about to buy.
   *
   * Chosen by tapping one of the two quotes, which is the moment the decision
   * is actually made; the button between them then only has to say "buy".
   */
  const [side, setSide] = useState<'Up' | 'Down' | null>(null);
  const [pulseBot, setPulseBot] = useState<PulseState | null>(null);
  /** The same rule with its gates opened up, on its own money. */
  const [softBot, setSoftBot] = useState<PulseState | null>(null);
  /**
   * The bot deck: whether it is open, which rule is on it, and whose money.
   *
   * Three questions in that order, and the order is the point. Every control
   * under them belongs to one rule and one account, so asking anything else
   * first — as four separate cards each with their own pickers did — meant
   * reading a setting without knowing whose it was.
   */
  const [botOpen, setBotOpen] = useState(false);
  const [botTab, setBotTab] = useState<
    'line' | 'fade' | 'inside' | 'pulse' | 'pulse2'
  >('line');
  const [botLive, setBotLive] = useState(false);
  const [probeBot, setProbeBot] = useState<ProbeState | null>(null);
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
        }).catch(() => {});
      }
    });
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

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
  }, [windowStart]);

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
  }, [readWindow]);

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
  }, [deskWindow, lookAhead, marketStale]);

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

  // The bots run on their own money in the service; the panel reads them.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.pulseState()
        .then((s) => {
          if (!cancelled) setPulseBot(s);
        })
        .catch(() => {});
      void PolyBot.pulseState({ soft: true })
        .then((s) => {
          if (!cancelled) setSoftBot(s);
        })
        .catch(() => {});
      void PolyBot.probeState()
        .then((s) => {
          if (!cancelled) setProbeBot(s);
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
    const timer = window.setInterval(read, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

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
  }, [deskWindow]);

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
          const [fresh, log] = await Promise.all([
            PolyBot.getOpenOrders().catch(() => null),
            PolyBot.getOrderLog({ windowStart: deskWindow }).catch(() => null),
          ]);
          if (fresh) setOrders(fresh.orders);
          if (log) setLogged(log.orders);
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

  /**
   * Three cents under the dearer side.
   *
   * A hand-placed limit here is nearly always a bid just below the favourite,
   * waiting for a dip that the five minutes usually provides. With no book to
   * read yet it falls back to the middle of the range.
   */
  const wheelCenter = (() => {
    const dearest = Math.max(askUp ?? 0, askDown ?? 0);
    if (!(dearest > 0)) return 50;
    return Math.min(99, Math.max(1, Math.round(dearest * 100) - 3));
  })();

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
          The session used to be a mark here, opening a strip of chips — one
          per event, each with its result. The chart below is already a row of
          five-minute events in the same order, so the strip was the same list
          drawn twice, and the mark was a tap in the way of it. The results
          went onto the candles instead: the window is the candle.
        */}
        {/*
          One mark for all four rules. They used to have one each, so the rail
          asked "which of these four icons is the one I want" before anything
          could be read or changed — and three of the four were always the
          wrong answer. Now it opens a deck: pick the rule, pick the account,
          and everything under that belongs to the one you picked.
        */}
        {(pulseBot || probeBot) && (
          <button
            className={`railmark${botOpen ? ' on' : ''}`}
            onClick={() => {
              // Opening lands on whatever is actually running, which is the
              // thing you opened it to look at.
              if (!botOpen) {
                if (probeBot?.live) {
                  setBotLive(true);
                  setBotTab(probeMode(probeBot));
                } else if (pulseBot?.live) {
                  setBotLive(true);
                  setBotTab('pulse');
                } else if (softBot?.live) {
                  setBotLive(true);
                  setBotTab('pulse2');
                }
              }
              setBotOpen((v) => !v);
            }}
            aria-label="Боты"
          >
            {/* A flask: all four of these are still experiments. */}
            <svg viewBox="0 0 16 16" aria-hidden>
              <path
                d="M6.5 1.5v4L2.5 12a1.6 1.6 0 001.4 2.5h8.2A1.6 1.6 0 0013.5 12l-4-6.5v-4M5.5 1.5h5"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            {/* Lit when real money is on the table, not merely when a rule
                is counting: they all count, always. */}
            <i
              className={`raildot${
                (probeBot?.running && probeBot.live) ||
                (pulseBot?.running && pulseBot.live) ||
                (softBot?.running && softBot.live)
                  ? ' live'
                  : ''
              }`}
              aria-hidden
            />
          </button>
        )}

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

      {botOpen && (
        <>
          {/*
            Which rule, and then whose money. The three trend entries are one
            engine with three ways of choosing a side, so exactly one of them
            runs at a time and picking a tab moves it — the dot says which one
            is live. The pulse is a separate rule and runs alongside any of
            them.
          */}
          <div className="botbar bottabs">
            {(
              [
                ['line', 'линия'],
                ['fade', 'свеча'],
                ['inside', 'цена'],
                ['pulse', 'пульс'],
                ['pulse2', 'пульс 2'],
              ] as const
            ).map(([key, label]) => {
              // The dot is real money. Everything runs on paper all the
              // time now, so "is it running" marks all four and says
              // nothing; what is worth a mark is what is spending.
              const live =
                key === 'pulse'
                  ? (pulseBot?.running ?? false) && (pulseBot?.live ?? false)
                  : key === 'pulse2'
                    ? (softBot?.running ?? false) && (softBot?.live ?? false)
                    : (probeBot?.running ?? false) &&
                      (probeBot?.live ?? false) &&
                      probeMode(probeBot) === key;
              return (
                <button
                  key={key}
                  className={`demoflag${botTab === key ? ' on' : ''}`}
                  onClick={() => {
                    setBotTab(key);
                    // Picking a trend tab is picking that entry: the engine
                    // reads one at a time, and the lead moves with it.
                    if (key !== 'pulse' && key !== 'pulse2' && probeBot) {
                      void PolyBot.probeUpdate({
                        real: botLive,
                        inside: key === 'inside',
                        fade: key === 'fade',
                        leadSec: key === 'fade' ? 15 : 50,
                      })
                        .then(() => PolyBot.probeState())
                        .then(setProbeBot)
                        .catch((e) =>
                          setNote(e instanceof Error ? e.message : String(e)),
                        );
                    }
                  }}
                >
                  {label}
                  <i className={`raildot${live ? ' live' : ''}`} aria-hidden />
                </button>
              );
            })}
          </div>

          {/*
            And whose money. It decides what every number below means — the
            balance, the stake, the run, the record — and for the trend rule
            the settings too, since the two accounts keep their own.
          */}
          <div className="botbar accounts">
            <button
              className={`demoflag${!botLive ? ' on' : ''}`}
              onClick={() => setBotLive(false)}
            >
              счёт: демо
            </button>
            <button
              className={`demoflag real${botLive ? ' on' : ''}`}
              onClick={() => setBotLive(true)}
            >
              счёт: реально
            </button>
          </div>
        </>
      )}

      {botOpen && botTab === 'pulse2' && softBot && (
        <PulseCard
          state={softBot}
          seen={botLive}
          soft
          onBank={(usd) => {
            if (!Number.isFinite(usd) || usd < 0) return;
            void PolyBot.pulseUpdate({ soft: true, bankUsd: usd })
              .then(() => PolyBot.pulseState({ soft: true }))
              .then(setSoftBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onShares={(n) => {
            if (!Number.isFinite(n) || n <= 0) return;
            void PolyBot.pulseUpdate({ soft: true, shares: n })
              .then(() => PolyBot.pulseState({ soft: true }))
              .then(setSoftBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onDemo={(live) => {
            void PolyBot.pulseUpdate({ soft: true, live })
              .then(() => PolyBot.pulseState({ soft: true }))
              .then(setSoftBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onReset={() => {
            void PolyBot.pulseReset({ soft: true })
              .then(() => PolyBot.pulseState({ soft: true }))
              .then(setSoftBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
        />
      )}

      {botOpen && botTab === 'pulse' && pulseBot && (
        <PulseCard
          state={pulseBot}
          seen={botLive}
          onBank={(usd) => {
            if (!Number.isFinite(usd) || usd < 0) return;
            void PolyBot.pulseUpdate({ bankUsd: usd })
              .then(() => PolyBot.pulseState())
              .then(setPulseBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onShares={(n) => {
            if (!Number.isFinite(n) || n <= 0) return;
            void PolyBot.pulseUpdate({ shares: n })
              .then(() => PolyBot.pulseState())
              .then(setPulseBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onDemo={(live) => {
            void PolyBot.pulseUpdate({ live })
              .then(() => PolyBot.pulseState())
              .then(setPulseBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onReset={() => {
            void PolyBot.pulseReset()
              .then(() => PolyBot.pulseState())
              .then(setPulseBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
        />
      )}

      {botOpen && botTab !== 'pulse' && botTab !== 'pulse2' && probeBot && (
        <ProbeCard
          state={probeBot}
          seen={botLive}
          onStake={(usdAmount, real) => {
            if (!Number.isFinite(usdAmount) || usdAmount <= 0) return;
            void PolyBot.probeUpdate({ stakeUsd: usdAmount, real })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onLead={(sec, real) => {
            if (!Number.isFinite(sec) || sec <= 0) return;
            void PolyBot.probeUpdate({ leadSec: Math.round(sec), real })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onRoom={(share, real) => {
            if (!Number.isFinite(share) || share < 0) return;
            void PolyBot.probeUpdate({ roomShare: share, real })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onRound={(band, real) => {
            if (!Number.isFinite(band) || band < 0) return;
            void PolyBot.probeUpdate({ roundBand: band, real })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onLive={(live) => {
            void PolyBot.probeUpdate({ live })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onBank={(bankUsd) => {
            if (!Number.isFinite(bankUsd) || bankUsd <= 0) return;
            void PolyBot.probeUpdate({ bankUsd })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onEdge={(edgeUsd, real) => {
            if (!Number.isFinite(edgeUsd) || edgeUsd <= 0) return;
            void PolyBot.probeUpdate({ edgeUsd, real })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onReset={() => {
            void PolyBot.probeReset({ real: botLive, mode: botTab })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
        />
      )}

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
                round={
                  probeBot
                    ? [...probeBot.riding, ...probeBot.rounds].find(
                        (r) => r.windowStart === readWindow,
                      ) ?? null
                    : null
                }
                onClose={() => setReadWindow(null)}
              />
            )}

            {/*
              And the same hour close up. The five-minute chart says which way
              the day is going; this one says what price is doing right now,
              which on a bet that lasts five minutes is the half that decides
              the side.
            */}
            <CandlePanel interval="1m" height={110} />

            <DepthPanel />

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
          Tapping the price opens a wheel rather than the keyboard. A limit
          price here is two digits chosen in a second, and a numeric keypad
          covers half the screen to enter them.
        */}
        {pickingPrice && (
          <PriceWheel
            center={wheelCenter}
            max={Math.round(ceiling * 100)}
            value={
              Number.isFinite(limitPriceNum) && limitPriceNum > 0
                ? Math.round(limitPriceNum * 100)
                : null
            }
            onPick={(c) => {
              setLimitPrice(String(c));
              setPickingPrice(false);
            }}
          />
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
            className={`buygo${side ? ` on ${side === 'Up' ? 'up' : 'down'}` : ''}`}
            disabled={busy || locked || limitBarred || side == null}
            onClick={() => side && void placeLimit(side)}
          >
            Купить
          </button>
          <div className="limitmid">
            <div className="limitprice">
              <button className="step" onClick={() => nudgeLimit(-1)}>
                −
              </button>
              <input
                type="text"
                inputMode="none"
                readOnly
                placeholder={askUp != null ? String(Math.round(askUp * 100)) : '¢'}
                value={limitPrice}
                onClick={() => setPickingPrice((v) => !v)}
              />
              <button className="step" onClick={() => nudgeLimit(1)}>
                +
              </button>
            </div>
            <button
              className="limitsize"
              onClick={() => setSizingLimit((v) => !v)}
            >
              {limitSize || limitDefaultSize.toFixed(0)}
            </button>
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
                  setPickingPrice(false);
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
 * screen while it is open. The bid is already under the thumb — that is where
 * a sale almost always goes — and the row of prices above it is the two other
 * answers anyone actually wants: out at cost, or out at a gain. The wheel is
 * for everything else.
 *
 * The button says what it will do and what it will pay, after the taker fee,
 * because the fee on a sale is charged in money and a number that ignores it
 * is not the number arriving in the wallet.
 */
function SellSheet({
  position,
  bid,
  avg,
  tick,
  busy,
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
  onClose: () => void;
  onSell: (price: number) => void;
  onMarket: () => void;
}) {
  const size = sellableShares(position.size);
  const step = Math.max(1, Math.round(tick * 100));
  const opened = Math.min(99, Math.max(1, Math.round((bid ?? avg ?? 0.5) * 100)));
  const [cents_, setCents] = useState(opened);

  const nudge = (d: number) => setCents((c) => Math.min(99, Math.max(1, c + d)));
  const at = (price: number) =>
    setCents(Math.min(99, Math.max(1, Math.round(price * 100))));

  // What the chosen price would pay, less the fee the venue takes out of it.
  const pays = netSellPrice(cents_ / 100) * size;
  const cost = avg > 0 ? avg * size : 0;

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
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
          The three prices worth one tap: what the book is bidding, what gets
          the money back, and what the round was opened for.
        */}
        <div className="pcts sellpicks">
          <button disabled={bid == null} onClick={() => bid != null && at(bid)}>
            рынок {bid != null ? cents(bid) : '—'}
          </button>
          <button
            disabled={!(avg > 0)}
            onClick={() => avg > 0 && at(breakEvenPrice(avg, tick))}
          >
            в ноль
          </button>
          <button
            disabled={!(avg > 0)}
            onClick={() => avg > 0 && at(targetPrice(avg, 0.25, tick))}
          >
            +25%
          </button>
        </div>

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

        <button
          className="primary wide"
          disabled={busy}
          onClick={() => onSell(cents_ / 100)}
        >
          Продать {size.toFixed(size % 1 ? 1 : 0)} по {cents_}¢
        </button>
        <div className="sellpays muted">
          {usd(pays)}
          {cost > 0 && <b className={pays >= cost ? 'up' : 'down'}> {signedUsd(pays - cost)}</b>}
        </div>

        {/*
          And the other kind of exit: not a price at all, but out — through the
          book, past our own resting offers, whatever it takes.
        */}
        <button
          className="ghost wide"
          disabled={busy || bid == null}
          onClick={onMarket}
        >
          продать по рынку
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
 * The one button every bot's explanation lives behind.
 *
 * Each of these panels had a paragraph across the top saying what the rule
 * does. It is worth having and worth reading once; it is not worth the height
 * every time the panel is opened to look at a number. So it folds away behind
 * a question mark, which is where an explanation belongs once it has been
 * read.
 */
function WhyButton({
  open,
  onClick,
}: {
  open: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`rulehint${open ? ' on' : ''}`}
      onClick={onClick}
      aria-label="Как это работает"
      aria-expanded={open}
    >
      ?
    </button>
  );
}


/**
 * The test bot's report.
 *
 * The bot is one sentence long, so the panel is mostly the record: what it
 * did, and then what that came to. The two halves of the answer are kept
 * apart on purpose — how often the line called the direction right, and how
 * much money following it made — because a run can be right most windows and
 * still lose, and a report that averaged the two would hide exactly that.
 */
function ProbeCard({
  state,
  seen,
  onStake,
  onLead,
  onRoom,
  onRound,
  onLive,
  onBank,
  onEdge,
  onReset,
}: {
  state: ProbeState;
  /**
   * Which account is on screen, chosen a row above this card.
   *
   * The picker used to live inside here, next to a second picker for the
   * entry mode, on a card that already had a switch of its own — four
   * controls answering "which bot, whose money, is it on" before a single
   * setting. They are one ladder now, and this is the rung below the top.
   */
  seen: boolean;
  onStake: (usd: number, real: boolean) => void;
  onLead: (sec: number, real: boolean) => void;
  onRoom: (share: number, real: boolean) => void;
  onRound: (band: number, real: boolean) => void;
  onLive: (live: boolean) => void;
  onBank: (usd: number) => void;
  onEdge: (usd: number, real: boolean) => void;
  onReset: () => void;
}) {
  const [why, setWhy] = useState(false);
  // Every dial belongs to the account being looked at.
  const dial = {
    stakeUsd: seen ? (state.realStakeUsd ?? state.stakeUsd) : state.stakeUsd,
    leadSec: seen ? (state.realLeadSec ?? state.leadSec) : state.leadSec,
    roomShare: seen ? (state.realRoomShare ?? state.roomShare) : state.roomShare,
    roundBand: seen ? (state.realRoundBand ?? state.roundBand) : state.roundBand,
    inside: seen ? (state.realInside ?? false) : state.inside,
    fade: seen ? (state.realFade ?? false) : state.fade,
    edgeUsd: seen ? (state.realEdgeUsd ?? state.edgeUsd) : state.edgeUsd,
  };

  /**
   * The record on screen: this account's, on this entry.
   *
   * Both halves matter. The two accounts trade the same windows and come
   * apart on execution; the three entries are three different strategies and
   * come apart on everything. A list mixing either pair is an average of
   * things that were never one thing.
   */
  const mode = dial.inside ? 'inside' : dial.fade ? 'fade' : 'line';
  const shown = state.rounds.filter(
    (r) => r.demo !== seen && (r.mode ?? 'line') === mode,
  );
  const all = summarise(shown);
  const sides = bySide(shown);
  const purse = seen ? (state.wallet ?? 0) : state.bank;
  const staking = seen ? (state.stakeNowLive ?? state.stakeNow) : state.stakeNow;
  const run = seen ? (state.streakLive ?? 0) : state.streak;
  const sinking = seen ? (state.losingLive ?? false) : (state.losing ?? false);
  const line = state.trend;
  const way = line?.way ?? '';
  // Both charts have to point the same way before anything is bought, so both
  // arrows are on the card and the cell says so when they do not.
  const wideWay = state.wide?.way ?? '';
  const agree = way !== '' && way === wideWay;
  const arrow = (w: string) => (w === 'Up' ? '↑' : w === 'Down' ? '↓' : '—');
  const arrowTone = (w: string) =>
    w === 'Up' ? 'up' : w === 'Down' ? 'down' : 'muted';
  // Which way the closing candle went, and whether that is the way the line
  // is pointing. Both are on the card because one of them stops the entry.
  const candleTone =
    state.candleBody > 0 ? 'up' : state.candleBody < 0 ? 'down' : 'muted';
  const minuteTone =
    state.minuteBody > 0 ? 'up' : state.minuteBody < 0 ? 'down' : 'muted';
  // Whether either candle is going the other way. It no longer stops an
  // entry — the closing candle stopped voting — so this is said plainly
  // rather than in the red of something being refused.
  const disagrees = (body: number) =>
    way !== '' && body !== 0 && (way === 'Up') !== (body > 0);
  const against = disagrees(state.candleBody) || disagrees(state.minuteBody);
  const atRound =
    state.roundBand > 0 &&
    state.roomToRound != null &&
    state.roomToRound <= state.roundBand;
  const tone = all.pnl > 0 ? 'up' : all.pnl < 0 ? 'down' : 'muted';

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>{dial.inside ? 'Цена' : dial.fade ? 'Свеча' : 'Линия'}</span>
        <WhyButton open={why} onClick={() => setWhy((v) => !v)} />
        {/*
          One switch, and only on the real page. Paper has none: it always
          runs. A rule switched off keeps no record, and the record is the
          only thing paper money is for — "is this worth real money" cannot be
          answered by a rule that was not running while nobody was watching,
          and the switch was there to be left off by accident.
        */}
        {seen ? (
          <button
            className={`switch ${state.live ? 'on' : ''}`}
            onClick={() => onLive(!state.live)}
          />
        ) : (
          <b className="muted small">всегда считает</b>
        )}
      </div>

      {why && (
      <div className="counterrule muted">
        {state.live
          ? 'Оба счёта сразу. Одно и то же правило, те же окна и тот же стакан, ' +
            'но деньги разные: бумажный счёт берёт предложение всегда, а на ' +
            'реальном ещё должна налиться заявка — вся разница между двумя ' +
            'историями в этом. Настройки у счетов свои: переключатель выше ' +
            'выбирает, чьи ставка, запас и режим входа показаны, и чья ' +
            'история ниже. '
          : 'На бумаге, и так всегда: читает тот же живой стакан, берёт те же ' +
            'предложения по тем же ценам, платит ту же комиссию и выходит по ' +
            'тем же ступеням — только деньги ненастоящие и на биржу ничего не ' +
            'уходит. Выключателя у бумаги нет: правило, которое не работало, ' +
            'пока никто не смотрел, не отвечает на вопрос, ради которого оно ' +
            'считает. '}
        {state.inside ? (
          <>
            {'Сторона не угадывается заранее. Первые полминуты окно оставлено ' +
              'в покое, а дальше на каждом тике считается, чего каждая из ' +
              'сторон стоит на самом деле: насколько цена уже ушла от цены ' +
              'открытия, в долях обычного хода пятиминутки, и сколько времени ' +
              'осталось, чтобы этот ход отыграли назад. Ход в половину ' +
              'обычного за две минуты до конца — это уже далеко не половина ' +
              'шансов. Формула откалибрована на 31 756 наблюдениях за 28 дней ' +
              'и проверена на тех днях, которых не видела. '}
            {'Дальше читается стакан. Берётся та сторона, за которую просят ' +
              'меньше, чем она стоит, — с учётом комиссии 7% от p·(1−p), — и ' +
              'меньше не на копейку, а хотя бы на '}
            {Math.round(state.edgeUsd * 100)}¢ на голос.{' '}
            {'Если обе стороны в стакане оценены честно или дороже честного — ' +
              'окно пропускается целиком, и это нормальный исход: платить ' +
              'справедливую цену не за что. Дороже 95¢ не берёт ничего: там ' +
              'вся прибыль — округление, а весь риск на месте. '}
            {'Выходит только лесенкой продаж — той, на которую настроен стол; ' +
              'ничего не закрывается «по ощущению». '}
            Всё, что наторгует, ниже по окнам.
          </>
        ) : (
          <>
          {'После выигранного окна ставит следующую на четверть выигрыша больше, ' +
            'и так по нарастающей, пока не проиграет — тогда снова с базовой. ' +
            'Каждое удвоение счёта поднимает базовую ставку в полтора раза. '}
          За {dial.leadSec} с до начала пятиминутки берёт {usd(dial.stakeUsd)}{' '}
          той стороны, куда показывает линия тренда на минутном графике — по
          рынку: берёт то, что стоит в стакане, пока это не дороже 56¢. Окно
          длится пять минут, и заявка, ждущая цену, которая уже на экране,
          тратит их на ожидание. Исключение одно: сторона открылась дороже
          максимума, брать нечего — тогда ставит лимитку по 58¢, то есть по
          самой дорогой цене, которую и так был готов заплатить. И дальше
          следит за стаканом весь лид: как только просят 58¢ или меньше —
          снимает заявку и берёт по рынку. Пятьдесят секунд — это долго для
          книги, которая переоценивается каждый тик, и цена, прочитанная один
          раз, ничего не решает. Не налили и не подешевело — заявка снимается
          через минуту, чтобы деньги не висели до конца окна. За 10 с до
          начала перечитывает картину и снимает заявку, если сторона
          поменялась. И это единственная покупка за окно: докупок по 42¢ и 33¢
          больше нет, откупа после проданной ступени тоже — оба клали деньги в
          окно, которое уже шло. Купленное продаётся
          только лесенкой, и лесенкой в чистом виде. Ступени стоят
          лимитками на книге всё окно, а не сторожатся: цена дошла до ступени
          — заявка исполнилась по ней. Ступень выбирается по часам и по
          максимуму цены за окно, вниз не откатывается. Одна поправка на
          испуг: если нашу сторону в этом окне отдавали дешевле 33¢, окно
          признаётся спасательным — лесенка целиком складывается в первую
          ступень и стоит там всё время, а на 93¢ переставляется только в
          последние 30 секунд. Сторона, за которую просили треть доллара,
          прошла мимо расчёта и вернулась; ждать от неё верхних ступеней —
          это ждать второй раз то, что уже один раз не случилось, а первая
          ступень выше цены входа и закрывает окно в плюс. Правило одно на
          всех — обоих ботов и руками поставленные позиции — и снимается
          плиткой «спасение». Больше выйти не по
          чему: ни удвоения входа, ни продажи у уровня, ни фиксации на
          развернувшейся минутке — каждое из них продавало по своей цене и по
          своему поводу, и вместе они дотягивали до ступени в меньшинстве
          окон. Единственное исключение — проигранное окно. Лесенка выводит
          только выигравшую сторону: все её ступени выше цены входа, и сторона,
          которая проигрывает, до них не доходит никогда — раньше такое окно
          просто доезжало до расчёта и не платило ничего, то есть на свою
          ошибку правило не отвечало вообще. За 10 секунд до конца отвечает:
          если по расчётной цене Полимаркета мы по другую сторону от цены
          открытия, остаток продаётся по рынку. Расчёт идёт по 60-секундному
          среднему на закрытии, так что к этому моменту пять шестых его уже
          история — то, что ещё дают за сторону, это последние деньги, а не
          шанс. Что лесенка не продала и что не успели забрать — заберёт
          расчёт.{' '}
          Это описание входа «по линии». «По свече» — другой вход, и
          единственный здесь, чьи числа проверялись на данных, которых поиск
          не видел: свеча делает экстремум за 20 свечей и закрывается в
          дальней четверти собственного размаха, то есть импульс выдохся —
          берётся противоположная сторона. На 8 месяцах пятиминуток: 59.2%
          на обучении и 56.4% на отложенных для новых максимумов, 58.2% и
          57.6% для минимумов. Тот же перебор по перемешанным меткам выдаёт
          «паттерны» на 57–58% там, где он их искал, и они рассыпаются до 51%
          на новых данных; эти два не рассыпаются. Считать это готовым
          заработком нельзя: на 58¢ комиссия поднимает безубыток примерно до
          60%, а тут 57% — эдж живёт только в дешёвом конце стакана. Вход по
          свече идёт за 15 секунд до открытия, а не за 50: он читает
          пятиминутку, которая закрывается вместе с открытием окна, и чем
          позже смотрит, тем больше её видел.{' '}
          Сторону во входе «по линии» задаёт линия: пятиминутная возражает лишь
          тогда, когда сама называет направление. А если минутная говорит
          «вбок» — окно больше не пропускается. Плоская линия значит, что
          рынок последнюю четверть часа шёл никуда, а рынок, идущий никуда,
          возвращается к своей середине чаще, чем уходит от неё: цена ниже
          средней за 30 минут — берём вверх, выше — вниз. Читается это за 15
          секунд до открытия, а не за 50: это не направление, которое рынок
          держит, а то, где цена сейчас стоит относительно того, где она была,
          — значит смотреть надо как можно позже. Средняя берётся за 30 минут,
          вдвое больше, чем длина самой линии, и это намеренно: средняя ровно
          по тому же куску — это середина той же линии, а у неё ответа нет.
          Останавливает вход теперь одно, и это про закрывающуюся пятиминутку:
          если её размах втрое больше среднего за последний час и она идёт в
          нашу сторону — не входим. Свеча такого размера это не тренд, а
          реакция: прострел, ликвидации, новость, — и пока она закрывалась,
          сторону уже переоценили под неё; покупается ход, который уже
          состоялся, по цене, которую он и сделал.{' '}
          Ни уровень впереди, ни круглые числа, ни хвост свечи вход не
          останавливают — они по очереди отсеивали окна и ни одно из них не
          отделяло выигрышные от проигрышных. Дольше всех держался отбой от
          уровня: свеча дотянулась до цены впереди и закрылась обратно — значит
          туда не пускают. Но фитиль в уровень и закрытие под ним — это ровно
          то, как выглядит ход, который через уровень идёт, и запрет резал не
          проигрыши, а окна, где линия называла сторону верно и её никто не
          спрашивал. Уровни всё ещё считаются за целые сутки, раз в минуту,
          только с добавлением новых, так что линии не прыгают, — но нужны они
          теперь для графика, а не для того, чтобы решать, куда входить. Всё, что наторгует, ниже по окнам.
          </>
        )}
      </div>
      )}

      {/*
        Everything the rule is looking at, in one row of four. Each is a label
        and a number: the line it follows, the candles closing with the window,
        the round number nearest the open and the level ahead — and under them,
        only when there is one, the single word that says why the side is not
        simply the line's.
      */}
      <div className="botreads">
        <div>
          <span>линия 1м / 5м</span>
          <b>
            <i className={arrowTone(way)}>{arrow(way)}</i>
            <i className={arrowTone(wideWay)}>{arrow(wideWay)}</i>
          </b>
          <em className={agree ? undefined : 'down'}>
            {agree ? `${Math.round(Math.abs(line?.perHour ?? 0))}/ч` : 'спорят'}
          </em>
        </div>
        <div>
          <span>свеча 5м / 1м</span>
          <b>
            <i className={candleTone}>
              {state.candleBody > 0 ? '▲' : state.candleBody < 0 ? '▼' : '—'}
              {Math.round(Math.abs(state.candleBody))}
            </i>
            <i className={minuteTone}>
              {state.minuteBody > 0 ? '▲' : state.minuteBody < 0 ? '▼' : '—'}
              {Math.round(Math.abs(state.minuteBody))}
            </i>
          </b>
          <em className="muted">{against ? 'против' : 'по линии'}</em>
        </div>
        <div>
          <span>круглый</span>
          <b className={atRound ? 'down' : undefined}>
            {bigPrice(state.roundNear)}
          </b>
          <em className={atRound ? 'down' : undefined}>
            {state.roomToRound == null
              ? '—'
              : atRound
                ? 'на нём'
                : `+${Math.round(state.roomToRound)}`}
          </em>
        </div>
        <div>
          <span>разворот</span>
          <b>{bigPrice(state.levelAhead)}</b>
          {/* The room there is, against the room the setting would demand.
              Neither number stops an entry any more — the level ahead is
              here to be looked at, not to decide. */}
          <em>
            {state.roomToLevel == null ? '—' : `+${Math.round(state.roomToLevel)}`}
            {state.roomNeed != null && ` / ${Math.round(state.roomNeed)}`}
          </em>
        </div>
      </div>

      {state.chose && <div className="botwhy warn">{state.chose}</div>}

      {/*
        Paper or real, and what the paper account is worth. The switch is the
        first thing on the card after the name because it is the thing that
        decides what every number under it means.
      */}
      {/*
        Two accounts, two switches. They used to be one — watching the rule on
        paper meant not running it, and running it meant losing the record
        that says whether it is worth running. Now either, both or neither.
      */}
      {seen && state.walletOut && (
        <div className="botbar">
          <b className="down">кошелёк не подключён</b>
        </div>
      )}

      <div className="botbar">
        {seen ? (
          <b className="muted">
            кошелёк
            {purse > 0 && <em> {usd(purse)}</em>}
          </b>
        ) : (
          <b className={state.bank >= state.bankUsd ? 'up' : 'down'}>
            {usd(state.bank)}
            <em> / {usd(state.bankUsd)}</em>
          </b>
        )}
        {/* What the next window will actually stake, when it is not the base. */}
        <b className={run > 0 && !sinking ? 'up pushright' : 'pushright'}>
          {usd(staking)}
          {/* A run riding on a window that is already losing is over: the
              stake falls back to base before the next entry, not after. */}
          {run > 0 && !sinking && <em> серия +{usd(run)}</em>}
          {run > 0 && sinking && <em className="down"> серия сброшена</em>}
        </b>
      </div>

      <div className="botbar">
        <b className="muted small">
          {dial.inside
            ? 'ждёт внутри окна и берёт недооценённую сторону'
            : dial.fade
              ? 'против свечи, сделавшей экстремум за 20 и закрывшейся в дальней четверти'
              : 'выбирает сторону до открытия по графику'}
        </b>
      </div>

      <div className="fields botfields">
        <NumField
          label="ставка $"
          value={dial.stakeUsd}
          onCommit={(n) => onStake(n, seen)}
        />
        {dial.inside ? (
          <NumField
            label="запас ¢"
            value={Math.round(dial.edgeUsd * 100)}
            onCommit={(n) => onEdge(n / 100, seen)}
          />
        ) : (
          <>
            <NumField
              label="за скол. с"
              value={dial.leadSec}
              onCommit={(n) => onLead(n, seen)}
            />
            <NumField
              label="запас %"
              value={Math.round(dial.roomShare * 100)}
              onCommit={(n) => onRoom(n / 100, seen)}
            />
            <NumField
              label="круглые $"
              value={Math.round(dial.roundBand)}
              onCommit={(n) => onRound(n, seen)}
            />
          </>
        )}
        {/* The paper account's opening balance is the desk's one answer, not
            a dial, so it shows on the paper page only. */}
        {!seen && (
          <NumField
            label="счёт $"
            value={state.bankUsd}
            onCommit={(n) => onBank(n)}
          />
        )}
      </div>

      {shown.length > 0 ? (
        <>
          <div className="listhead second">
            <span>
              Итог за {all.rounds} окон
              {shown.length > all.rounds
                ? ` · пропущено ${shown.length - all.rounds}`
                : ''}
            </span>
            <button className="linkbtn" onClick={onReset}>
              очистить
            </button>
          </div>

          {/*
            The money only when there was any. A run that stood out of every
            window has nothing to average, and the list below still says why.
          */}
          {all.rounds > 0 && (
          <>
          <div className="countergrid">
            <div>
              <span className="muted">итог</span>
              <b className={tone}>
                {all.pnl >= 0 ? '+' : '−'}
                {usd(Math.abs(all.pnl))}
              </b>
            </div>
            <div>
              <span className="muted">угадал</span>
              <b>
                {all.hitRate === null
                  ? '—'
                  : `${Math.round(all.hitRate * 100)}%`}
              </b>
            </div>
            <div>
              <span className="muted">плюс/минус</span>
              <b>
                <span className="up">{all.wins}</span>
                <span className="muted">/</span>
                <span className="down">{all.losses}</span>
              </b>
            </div>
            <div>
              <span className="muted">за окно</span>
              <b className={(all.average ?? 0) >= 0 ? 'up' : 'down'}>
                {(all.average ?? 0) >= 0 ? '+' : '−'}
                {usd(Math.abs(all.average ?? 0))}
              </b>
            </div>
            <div>
              <span className="muted">лучшее</span>
              <b className="up">+{usd(Math.abs(all.best ?? 0))}</b>
            </div>
            <div>
              <span className="muted">худшее</span>
              <b className="down">−{usd(Math.abs(all.worst ?? 0))}</b>
            </div>
            <div>
              <span className="muted">вложено</span>
              <b>{usd(all.spent)}</b>
            </div>
            <div>
              <span className="muted">лесенкой</span>
              <b>{all.byLadder}</b>
            </div>
            <div>
              <span className="muted">до расчёта</span>
              <b>{all.toSettlement}</b>
            </div>
          </div>

          <div className="probesides">
            <div>
              <span className="up">Up</span> {sides.up.rounds} ·{' '}
              <b className={sides.up.pnl >= 0 ? 'up' : 'down'}>
                {sides.up.pnl >= 0 ? '+' : '−'}
                {usd(Math.abs(sides.up.pnl))}
              </b>
            </div>
            <div>
              <span className="down">Down</span> {sides.down.rounds} ·{' '}
              <b className={sides.down.pnl >= 0 ? 'up' : 'down'}>
                {sides.down.pnl >= 0 ? '+' : '−'}
                {usd(Math.abs(sides.down.pnl))}
              </b>
            </div>
          </div>
          </>
          )}

          <div className="listhead second">
            <span>По пятиминуткам</span>
            <span className="muted">свежие сверху</span>
          </div>
          <div className="probelist">
            {/* What is open sits at the top of the same list it will join. */}
            {state.riding.map((r) => (
              <ProbeRow
                key={`${r.windowStart}-${r.leg}`}
                round={r}
                offer={offerFor(state.offers, r)}
              />
            ))}
            {shown.map((r) => (
              <ProbeRow key={`${r.windowStart}-${r.leg}`} round={r} />
            ))}
          </div>
        </>
      ) : (
        <div className="botbar muted">
          {state.riding.length > 0 ? (
            state.riding.map((r) => (
              <ProbeRow
                key={`${r.windowStart}-${r.leg}`}
                round={r}
                offer={offerFor(state.offers, r)}
              />
            ))
          ) : (
            <b className="muted">{state.note || 'ждёт окна'}</b>
          )}
        </div>
      )}

      {state.lastFault && <div className="banner warn">{state.lastFault}</div>}
    </div>
  );
}

/**
 * One closed window in a pulse's record, and what it looks like opened.
 *
 * Shut it is a line: when, which side, the size, the result. That answers
 * "how did it go" and nothing else — and the questions that follow are always
 * the same two, what it cost and where the money came from, because a round
 * that made forty cents on settlement is a different round from one that made
 * forty cents on a sale even though the line is identical. So the row opens.
 */
function PulseRow({ round: r }: { round: PulseRound }) {
  const [open, setOpen] = useState(false);
  const spent = r.spent && r.spent > 0 ? r.spent : r.shares * r.price;
  const avg = r.shares > 0 ? spent / r.shares : r.price;
  // What the sales themselves went at, which is not the ask they were placed
  // at once a bid jumped past it.
  const soldShares = r.settled > 0 ? r.shares - r.settled : r.shares;
  const soldAt = soldShares > 0.01 && r.proceeds > 0 ? r.proceeds / soldShares : null;

  return (
    <>
      <div
        className={`probesum${open ? ' on' : ''}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="muted">{clockOf(r.windowStart)}</span>
        <b className={r.outcome === 'Up' ? 'up' : 'down'}>{r.outcome}</b>
        <span className="muted">
          {r.shares.toFixed(1)} × {cents(r.price)}
        </span>
        <b className={r.pnl >= 0 ? 'up pushright' : 'down pushright'}>
          {signedUsd(r.pnl)}
        </b>
        <em className="muted">{open ? '−' : '+'}</em>
      </div>
      {open && (
        <div className="probedetail">
          <div>
            <span className="muted">вход</span>
            <b>{cents(r.price)}</b>
          </div>
          {/* Only when a bid under the entry filled and moved it. */}
          {Math.abs(avg - r.price) > 0.005 && (
            <div>
              <span className="muted">средняя</span>
              <b>{cents(avg)}</b>
            </div>
          )}
          <div>
            <span className="muted">доли</span>
            <b>{r.shares.toFixed(1)}</b>
          </div>
          <div>
            <span className="muted">вложено</span>
            <b>{usd(spent)}</b>
          </div>
          {soldAt != null && (
            <div>
              <span className="muted">продано по</span>
              <b>{cents(soldAt)}</b>
            </div>
          )}
          {r.proceeds > 0 && (
            <div>
              <span className="muted">с продаж</span>
              <b>{usd(r.proceeds)}</b>
            </div>
          )}
          {r.settled > 0 && (
            <div>
              <span className="muted">расчёт</span>
              <b>{usd(r.settled)}</b>
            </div>
          )}
          <div>
            <span className="muted">закрытие</span>
            <b className={r.winner === 'Up' ? 'up' : r.winner === 'Down' ? 'down' : undefined}>
              {r.winner || '—'}
            </b>
          </div>
          <div>
            <span className="muted">итог</span>
            <b className={r.pnl >= 0 ? 'up' : 'down'}>{signedUsd(r.pnl)}</b>
          </div>
          {r.note && (
            <div className="wide">
              <span className="muted">как вышли</span>
              <b>{r.note}</b>
            </div>
          )}
        </div>
      )}
    </>
  );
}

/**
 * One five-minute candle, opened.
 *
 * A candle and a window are the same thing seen two ways, so tapping one asks
 * the question the history answers in a list: what was done here, and what the
 * rule was looking at when it decided. Orders come from the log for that
 * window; the reading comes from the round the bot filed for it, traded or
 * skipped alike.
 */
function WindowRead({
  windowStart,
  orders,
  round,
  onClose,
}: {
  windowStart: number;
  orders: LoggedOrder[];
  round: ProbeRound | null;
  onClose: () => void;
}) {
  const facts = (round?.why ?? '').trim();
  const done = orders.filter((o) => o.matched > 1e-6);
  const money = round && traded(round) ? pnlOf(round) : null;

  return (
    <div className="card tight windowread">
      <div className="counterhead">
        <span>Свеча {clockOf(windowStart)}</span>
        {money != null && (
          <b className={money >= 0 ? 'up' : 'down'}>
            {money >= 0 ? '+' : '−'}
            {usd(Math.abs(money))}
          </b>
        )}
        <button className="shut" onClick={onClose}>
          ✕
        </button>
      </div>

      {/* What the bot did with this window, in its own words. */}
      <div className="probefact">
        <span className="muted">бот</span>
        <b>
          {round == null
            ? 'не смотрел'
            : traded(round)
              ? `${round.side} ${round.shares.toFixed(1)} · ${cents(round.price)}`
              : round.side
                ? `хотел ${round.side}, но ${round.note || 'без причины'}`
                : round.note || 'пропуск'}
        </b>
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

      {facts && (
        <div className="probewhy">
          {facts.split('\n').map((line) => {
            const at = line.indexOf(':');
            const name = at > 0 ? line.slice(0, at) : '';
            const value = at > 0 ? line.slice(at + 1).trim() : line;
            return (
              <div className="probefact" key={line}>
                <span className="muted">{name}</span>
                <b>{value}</b>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** The offer standing over a position, matched by window and leg. */
function offerFor(
  offers: ProbeOffer[] | undefined,
  round: ProbeRound,
): ProbeOffer | undefined {
  return (offers ?? []).find(
    (o) => o.windowStart === round.windowStart && o.leg === round.leg,
  );
}

/**
 * One window, as a line: what was taken, and what it came to.
 *
 * Tapping it opens what the rule was looking at when it chose the side. A
 * history that only says "Down, lost" cannot be argued with; the round carries
 * its whole reading, so the line can show it.
 */
function ProbeRow({ round, offer }: { round: ProbeRound; offer?: ProbeOffer }) {
  const [open, setOpen] = useState(false);
  const why = (round.why ?? '').trim();

  // Only a row with something to say is worth tapping.
  const body = (inner: React.ReactNode, cls: string) =>
    why ? (
      <>
        <button
          type="button"
          className={`proberow tappable ${cls}${open ? ' opened' : ''}`}
          onClick={() => setOpen(!open)}
        >
          {inner}
        </button>
        {open && (
          <div className="probewhy">
            {why.split('\n').map((line) => {
              const at = line.indexOf(':');
              const name = at > 0 ? line.slice(0, at) : '';
              const value = at > 0 ? line.slice(at + 1).trim() : line;
              return (
                <div className="probefact" key={line}>
                  <span className="muted">{name}</span>
                  <b>{value}</b>
                </div>
              );
            })}
          </div>
        )}
      </>
    ) : (
      <div className={`proberow ${cls}`}>{inner}</div>
    );

  // A window still running shows what is held, where it is aiming, and — the
  // part nothing else on the screen shows in demo — the offer standing over it.
  if (round.open) {
    return body(
      <>
        <span className="probewhen">{clockOf(round.windowStart)}</span>
        <span className={round.side === 'Up' ? 'up' : 'down'}>{round.side}</span>
        <span className="muted">
          {round.shares > 0
            ? `${round.shares.toFixed(1)} · ${cents(round.price)}`
            : `ждёт ${cents(round.resting || 0)}`}
          {round.adds > 0 ? ` ×${round.adds + 1}` : ''}
          {round.leg > 0 ? ' откуп' : ''}
          {/* Up to the last minute the rung is watched, not offered: the
              shares go into the bid the moment it reaches up, so the price is
              a floor. In the last minute the offer rests at it. */}
          {offer
            ? offer.resting
              ? ` · лимитка ${cents(offer.price)}`
              : ` · ждёт ${cents(offer.price)}+`
            : round.target > 0
              ? ` → ${bigPrice(round.target)}`
              : ''}
        </span>
        <span className="probemark">·</span>
        <b className="warn">идёт</b>
      </>,
      'live',
    );
  }

  // A window it stood out of still gets a line, with the reason in place of
  // the numbers — that is the whole point of writing them down. And with the
  // side it was about to buy: "у уровня 78700" is only half a reason until
  // it says which way the money was going.
  if (!traded(round)) {
    return body(
      <>
        <span className="probewhen">{clockOf(round.windowStart)}</span>
        <span className={round.side === 'Up' ? 'up' : round.side === 'Down' ? 'down' : 'muted'}>
          {round.side || '—'}
        </span>
        <span className="muted">
          {round.side ? 'хотел, но ' : 'пропуск: '}
          {round.note || 'без причины'}
        </span>
        <span className="probemark">·</span>
        <b className="muted">—</b>
      </>,
      'skipped',
    );
  }

  const money = pnlOf(round);
  const tone = money > 0.005 ? 'up' : money < -0.005 ? 'down' : 'muted';
  // What the shares averaged on the way out, sale and settlement together,
  // which is the number worth comparing with what they cost.
  const out =
    round.shares > 0 ? (round.proceeds + round.settled) / round.shares : 0;

  return body(
    <>
      <span className="probewhen">{clockOf(round.windowStart)}</span>
      <span className={round.side === 'Up' ? 'up' : 'down'}>{round.side}</span>
      <span className="muted">
        {round.shares.toFixed(1)} · {cents(round.price)} → {cents(out)}
      </span>
      <span className="probemark">
        {round.winner ? (round.right ? '✓' : '✕') : '·'}
      </span>
      <b className={tone}>
        {money >= 0 ? '+' : '−'}
        {usd(Math.abs(money))}
      </b>
    </>,
    '',
  );
}

function PulseCard({
  state,
  seen,
  soft = false,
  onBank,
  onShares,
  onDemo,
  onReset,
}: {
  state: PulseState;
  /** Which account is on screen — and, for this rule, the one it runs on. */
  seen: boolean;
  /** The variant with its gates opened up, which is a different rule's card. */
  soft?: boolean;
  onBank: (usd: number) => void;
  onShares: (shares: number) => void;
  onDemo: (demo: boolean) => void;
  onReset: () => void;
}) {
  const [why, setWhy] = useState(false);
  /**
   * Whose record is on screen. Both accounts run on the same reads and the
   * same windows, so everything above this line is shared and everything
   * below it — the lot, the totals, the money — belongs to one of them.
   */
  const book = seen
    ? {
        pnl: state.livePnl,
        rounds: state.liveRounds,
        wins: state.liveWins,
        losses: state.liveLosses,
      }
    : {
        pnl: state.pnl,
        rounds: state.rounds,
        wins: state.wins,
        losses: state.losses,
      };
  const tone = book.pnl > 0 ? 'up' : book.pnl < 0 ? 'down' : 'muted';
  const history = (seen ? state.liveRoundList : state.roundList) ?? [];
  const read = state.read;
  const lot = seen ? state.liveLot : state.lot;
  // Which side each reading is pointing at, so a glance says "three of four".
  const side = read ? (read.lead >= 0 ? 'up' : 'down') : 'muted';

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>{soft ? 'Пульс 2' : 'Пульс'}</span>
        <WhyButton open={why} onClick={() => setWhy((v) => !v)} />
        {/*
          The switch is real money, and there is none on the paper page: this
          rule always runs, on its own bank, whatever the wallet is doing.
        */}
        {seen ? (
          <button
            className={`switch ${state.live ? 'on' : ''}`}
            onClick={() => onDemo(!state.live)}
          />
        ) : (
          <b className="muted small">всегда считает</b>
        )}
      </div>

      {why && (
      <div className="counterrule muted">
        Берёт {state.shares.toFixed(0)} долей стороны, за которую разом
        высказались четыре вещи: ход окна от его открытия, импульс минуток,
        объём под ним и перевес в стакане Binance. Выходит по{' '}
        {state.ladder
          ? 'лесенке продаж — той же, на которую настроен стол: цена начинается ' +
            'высоко и идёт вниз по часам, так что берётся то, что окно на ' +
            'самом деле даёт, а не то, на что рассчитывал вход. Фиксированная ' +
            'цель ждёт одну цену и выигрывает целиком, когда стакан до неё ' +
            'дошёл, и ничего, когда он остановился в центре от неё; правилу, ' +
            'которое входит часто и на тонком, ступени подходят больше — у ' +
            'него почти все позиции это небольшие ходы, а не один крупный. '
          : `+${Math.round(state.takePct * 100)}% лимиткой — и это пол, а не
        пожелание: правило переходит спред, чтобы взять сторону, за которую
        высказались четверо, то есть уже заплатило за это согласие в аске, и
        дешевле пятнадцати процентов круг превращается в подброс монеты с
        комиссией. `}
        Режет по рынку, если перевес развернулся, и не продаёт
        вовсе, если к концу окна ведёт — расчёт платит доллар без комиссии.
        {' Потолок цены входа поднимается к концу окна: до 83¢ в последние две' +
          ' минуты и до 86¢ в последнюю. Цена и оставшееся время двигаются' +
          ' вместе — сторона за 80¢ с четырьмя минутами впереди берёт почти' +
          ' доллар за ход, который ещё не случился, а с одной минутой та же' +
          ' цена берёт за ход, который уже почти закончен.'}
        Входит в любую секунду окна, включая последнюю минуту: поздней покупке
        уже не дойти до своей цели, но выигрывающая сторона доезжает до
        расчёта, а он платит целый доллар.
        {soft &&
          ' Это тот же вопрос, заданный четырежды, но ответы засчитываются' +
            ' раньше: половина перевеса, стакан, который просто не против' +
            ' стороны, а не за неё, объём, который просто не мёртвый, и шире' +
            ' полоса котировок. Строгое правило редко проходит все четыре' +
            ' ворота, а когда проходит — сторону уже переоценили; это входит в' +
            ' разы чаще на доказательствах в разы тоньше. Какое из двух право,' +
            ' спорить незачем: они идут по одним и тем же окнам на своих' +
            ' деньгах, и через несколько дней записи ответят.'}
        {' Вместе со входом под ним встают три заявки — на 6, 12 и 18 центов' +
          ' дешевле, каждая на тот же размер. Правило покупает в момент, когда' +
          ' четверо согласились, то есть когда сторона дороже всего, а окно,' +
          ' которое сперва идёт против и возвращается, — обычная его форма, а' +
          ' не исключение. Каждая исполнившаяся заявка берёт ту же' +
          ' убеждённость дешевле. Цена выхода при этом не двигается: она' +
          ' считается от первого входа, поэтому нижние лоты зарабатывают' +
          ' больше на той же продаже. Незакрытые заявки снимаются вместе с' +
          ' окном.'}
        {!seen &&
          ' Лесенка продаж отсюда убрана: её ступени абсолютные — 77¢ в начале' +
            ' окна, сколько бы доли ни стоили, — и на стороне, взятой по 85¢,' +
            ' она просила 77¢, то есть продавала в убыток нарочно. Позиция,' +
            ' не дошедшая до своей цены, теперь доезжает до расчёта.'}
        {seen &&
          ' Реальный счёт идёт рядом с бумажным на тех же чтениях и тех же' +
            ' окнах: одно решение, две покупки, две записи. Расходятся они там,' +
            ' где расходится исполнение — бумага берёт предложение всегда, а' +
            ' здесь ещё должна налиться заявка.'}
      </div>
      )}

      {seen && !state.live && (
        <div className="botbar">
          <b className="muted small">
            на реальные не запущен — бумажный счёт считает своё
          </b>
        </div>
      )}

      <div className="botbar">
        {seen ? (
          <b className="muted">
            кошелёк<em> {usd(state.liveCash)}</em>
          </b>
        ) : (
          <b className={state.cash >= state.bankUsd ? 'up' : 'down'}>
            {usd(state.cash)}
            <em> / {usd(state.bankUsd)}</em>
          </b>
        )}
        <b className={tone}>{signedUsd(book.pnl)}</b>
        <em className="muted">
          {book.rounds} кругов · {book.wins}/{book.losses}
        </em>
      </div>

      {/*
        Window by window, for the account on screen. The totals above answer
        "how has it done"; a run of forty rounds that nets a dollar looks the
        same there whether it was steady or wild, and only one of those is
        worth stopping.
      */}
      {history.length > 0 && (
        <>
          <div className="listhead second">
            <span>По пятиминуткам</span>
            <span className="muted">свежие сверху</span>
          </div>
          <div className="probelist">
            {history.slice(0, 40).map((r) => (
              <PulseRow key={`${r.windowStart}-${r.outcome}`} round={r} />
            ))}
          </div>
        </>
      )}

      {read && (
        <div className="botreads">
          <div>
            <span>ход</span>
            <b className={side}>
              {read.lead >= 0 ? '+' : '−'}
              {Math.abs(read.lead).toFixed(0)}$
            </b>
          </div>
          <div>
            <span>импульс</span>
            <b className={read.momentum >= 0 ? 'up' : 'down'}>
              {read.momentum >= 0 ? '+' : '−'}
              {Math.abs(read.momentum).toFixed(0)}$
            </b>
          </div>
          <div>
            <span>объём</span>
            <b className={read.volume >= 1 ? 'up' : 'muted'}>
              ×{read.volume.toFixed(2)}
            </b>
          </div>
          <div>
            <span>стакан</span>
            <b className={read.lean >= 0.5 ? 'up' : 'down'}>
              {Math.round(read.lean * 100)}%
            </b>
          </div>
        </div>
      )}

      <div className="fields botfields">
        <NumField
          label="контейнер, $"
          value={state.bankUsd}
          onCommit={(n) => onBank(n)}
        />
        <NumField
          label="долей за раз"
          value={state.shares}
          onCommit={(n) => onShares(n)}
        />
      </div>

      {/* What it holds, or why it holds nothing — and the way to start over. */}
      <div className="botbar">
        {lot ? (
          <>
            <span className={lot.outcome === 'Up' ? 'up' : 'down'}>
              {lot.outcome}
            </span>
            <b>
              {lot.shares.toFixed(1)} · {cents(lot.price)}
              {lot.sellPrice > 0 ? ` → ${cents(lot.sellPrice)}` : ''}
            </b>
            {lot.note && <em className="muted">{lot.note}</em>}
          </>
        ) : (
          <b className="muted">{state.note ?? 'ждёт совпадения'}</b>
        )}
        <button className="linkbtn pushright" onClick={onReset}>
          обнулить
        </button>
      </div>

      {state.lastFault && <div className="banner warn">{state.lastFault}</div>}
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
  const rows = [...orders].sort((a, b) => b.placedAt - a.placedAt);
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
function WindowMark({ windowStart }: { windowStart: number }) {
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

  const move = openMark(mark?.target, mark?.price);

  return (
    <div className="pairmark">
      <span className="pairmarkline muted">
        {bigPrice(mark?.target)} → {bigPrice(mark?.price)}
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

    return {
      position: mine[0],
      size,
      avg,
      // What selling it right now would pay, less the taker fee — the money,
      // not the mark the exchange shows.
      pnl: positionPnl(size, avg, now).pnl,
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
            {/* What it is worth to close at the price on the screen. */}
            <span className={`pairpnl ${held.pnl >= 0 ? 'up' : 'down'}`}>
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
        <WindowMark windowStart={windowStart} />
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

/**
 * A wheel of cents, opening where the next order probably belongs.
 *
 * Every price here is a whole number between one and ninety-nine, which is a
 * list — so it is shown as one and flicked through, rather than typed on a
 * keypad that covers the book you are pricing against.
 *
 * It opens three cents under whichever side is currently dearer. A limit here
 * is nearly always a bid just under the favourite, so that is the number under
 * the thumb when the wheel appears; fifty was the middle of the range and
 * almost never the middle of the decision.
 */
function PriceWheel({
  value,
  center,
  max,
  onPick,
}: {
  value: number | null;
  /** Where the wheel lands when it opens, in cents. */
  center: number;
  /** The dearest cent the wheel will offer — the early ceiling, when buying. */
  max: number;
  onPick: (cents: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);

  /** Bring a price to the middle of the strip. */
  const scrollTo = (cents: number, smooth: boolean) => {
    const el = ref.current;
    if (!el) return;
    const at = el.querySelector<HTMLElement>(`[data-cents="${cents}"]`);
    if (!at) return;
    const left = at.offsetLeft - el.clientWidth / 2 + at.offsetWidth / 2;
    if (smooth) el.scrollTo({ left, behavior: 'smooth' });
    else el.scrollLeft = left;
  };

  useEffect(() => {
    scrollTo(Math.min(center, max), false);
    // Opening is the only time it is positioned; after that it is the user's.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    /*
      Two ends pinned to the sides. Ninety-nine prices is a long strip to flick
      through, and the two worth reaching in one tap are the cheap end you buy
      a dip at and the dear end you sell a decided window at — so they stay put
      and carry the wheel to themselves.
    */
    <div className="wheelwrap">
      <button
        className="wheeljump"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => scrollTo(WHEEL_LOW, true)}
      >
        {WHEEL_LOW}
      </button>

      <div className="wheel" ref={ref}>
        <div className="wheelpad" />
        {Array.from({ length: Math.max(1, max) }, (_, i) => i + 1).map((c) => (
          <button
            key={c}
            data-cents={c}
            className={`wheelnum${c === value ? ' on' : ''}${
              c === center ? ' half' : ''
            }`}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onPick(c)}
          >
            {c}
          </button>
        ))}
        <div className="wheelpad" />
      </div>

      <button
        className="wheeljump"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => scrollTo(Math.min(WHEEL_HIGH, max), true)}
      >
        {Math.min(WHEEL_HIGH, max)}
      </button>
    </div>
  );
}

/** The two ends of the strip that are worth one tap. */
const WHEEL_LOW = 15;
const WHEEL_HIGH = 85;

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
