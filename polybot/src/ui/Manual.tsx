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
  minShares,
  type Exposure,
  type ManualSettings,
} from '../core/manual';
import { pairOrders, realised, type TradeRow } from '../core/trades';
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
  type TakeState,
  type PulseState,
  type ProbeState,
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
  rebuyEnabled: false,
  rebuyDropPct: 0.2,
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
  const [sessionPnl, setSessionPnl] = useState(0);
  /** A closed window being looked at, instead of the one being traded. */
  const [viewWindow, setViewWindow] = useState<number | null>(null);
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
  const [pulseOpen, setPulseOpen] = useState(false);
  const [takeBot, setTakeBot] = useState<TakeState | null>(null);
  const [takeOpen, setTakeOpen] = useState(false);
  const [probeBot, setProbeBot] = useState<ProbeState | null>(null);
  const [probeOpen, setProbeOpen] = useState(false);
  /** The event strip is folded away until it is asked for. */
  const [sessionOpen, setSessionOpen] = useState(false);
  /** A resting order opened for editing. */
  const [editing, setEditing] = useState<TradeRow | null>(null);
  /** A position opened to be closed, with the price still to be chosen. */
  const [closing, setClosing] = useState<NativePosition | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

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
      if (stored.autoSellEnabled || stored.autoRebuyEnabled) {
        void PolyBot.autoSellUpdate({
          enabled: stored.autoSellEnabled,
          ladder: stored.autoSellLadder,
          retryEverySec: stored.autoSellRetrySec,
          rebuyEnabled: stored.autoRebuyEnabled,
          rebuyDropPct: stored.autoRebuyDropPct,
          watchSec: stored.autoSellWatchSec,
          rebuySlicePauseSec: stored.autoRebuySlicePauseSec,
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
          setSessionPnl(r.session);
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

  // A window that has closed is history; looking at one is not a mode to be
  // stuck in when the next one opens.
  useEffect(() => {
    setViewWindow(null);
  }, [windowStart]);

  /** What the round makes if it goes our way — the header's second number. */
  const potential = useMemo(
    () => potentialProfit(positions.filter((p) => !p.redeemable)),
    [positions],
  );

  useEffect(() => {
    onSummary?.(potential);
  }, [potential, onSummary]);

  /** The window's orders, paired into trades: a buy and the sell that closed it. */
  const trades = useMemo(() => pairOrders(logged), [logged]);

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

  const working = useMemo(
    () => trades.filter((t) => t.status === 'buying' || t.status === 'pending'),
    [trades],
  );
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
          // The next window is published shortly before it opens; until then
          // there is nothing to show and the desk says so.
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
      void PolyBot.takeState()
        .then((s) => {
          if (!cancelled) setTakeBot(s);
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
  // so a slow poll is enough.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getBalance()
        .then((r) => {
          if (!cancelled) setBalance(r.usdc);
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
      void PolyBot.getOrderLog({ windowStart: viewWindow ?? deskWindow })
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
  }, [deskWindow, viewWindow]);

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
            settingsRef.current.autoSellEnabled || settingsRef.current.autoRebuyEnabled;
          if (
            loadedRef.current &&
            (s.enabled !== settingsRef.current.autoSellEnabled ||
              s.rebuyEnabled !== settingsRef.current.autoRebuyEnabled ||
              (wanted && !s.running))
          ) {
            void PolyBot.autoSellUpdate({
              enabled: settingsRef.current.autoSellEnabled,
              ladder: settingsRef.current.autoSellLadder,
              retryEverySec: settingsRef.current.autoSellRetrySec,
              rebuyEnabled: settingsRef.current.autoRebuyEnabled,
              rebuyDropPct: settingsRef.current.autoRebuyDropPct,
              watchSec: settingsRef.current.autoSellWatchSec,
              rebuySlicePauseSec: settingsRef.current.autoRebuySlicePauseSec,
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
        </button>

        {/*
          Two marks, not two figures. Both of these are read a few times an
          hour and their numbers are inside them anyway — carried on the rail
          they only competed with the balance, which is the one number here
          that is always worth reading.
        */}
        <button
          className={`railmark${sessionOpen ? ' on' : ''}`}
          onClick={() => setSessionOpen((v) => !v)}
          aria-label="Сессия"
        >
          <span className={sessionPnl >= 0 ? 'up' : 'down'}>◷</span>
        </button>

        {takeBot && (
          <button
            className={`railmark${takeOpen ? ' on' : ''}`}
            onClick={() => setTakeOpen((v) => !v)}
            aria-label="Забираю плюс"
          >
            {/* Its own mark: a gain taken off the top, lit while it watches. */}
            <svg viewBox="0 0 16 16" aria-hidden>
              <path
                d="M2 11l4-4 3 3 5-6M13 4h-3M13 4v3"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            <i className={`raildot${takeBot.running ? ' live' : ''}`} aria-hidden />
          </button>
        )}

        {pulseBot && (
          <button
            className={`railmark${pulseOpen ? ' on' : ''}`}
            onClick={() => setPulseOpen((v) => !v)}
            aria-label="Пульс"
          >
            {/* Its own mark: a heartbeat, and lit while it is running. */}
            <svg viewBox="0 0 16 16" aria-hidden>
              <path
                d="M1 8h3l2-4 3 8 2-4h4"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            <i className={`raildot${pulseBot.running ? ' live' : ''}`} aria-hidden />
          </button>
        )}

        {probeBot && (
          <button
            className={`railmark${probeOpen ? ' on' : ''}`}
            onClick={() => setProbeOpen((v) => !v)}
            aria-label="Проба"
          >
            {/* Its own mark: a flask, because this one is an experiment. */}
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
            <i className={`raildot${probeBot.running ? ' live' : ''}`} aria-hidden />
          </button>
        )}

        <div className="deskbtns">
          <button
            className={`gear${lookAhead ? ' on' : ''}`}
            onClick={() => setLookAhead((v) => !v)}
            aria-label="Следующее окно"
            title="Следующее окно"
          >
            {lookAhead ? '↩' : '»'}
          </button>
          <button
            className={`gear${tab === 'settings' ? ' on' : ''}`}
            onClick={() => setTab(tab === 'settings' ? 'desk' : 'settings')}
            aria-label="Настройки"
          >
            ⚙
          </button>
        </div>
      </div>

      {sessionOpen && (
        <EventStrip
          events={events}
          windowStart={windowStart}
          selected={viewWindow}
          onSelect={(w) => {
            setViewWindow(w);
            setSessionOpen(false);
          }}
        />
      )}

      {takeOpen && takeBot && (
        <TakeCard
          state={takeBot}
          onEnable={(enabled) => {
            void PolyBot.takeUpdate({ enabled })
              .then(() => PolyBot.takeState())
              .then(setTakeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onGain={(gain) => {
            if (!Number.isFinite(gain) || gain <= 0) return;
            void PolyBot.takeUpdate({ gain })
              .then(() => PolyBot.takeState())
              .then(setTakeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
        />
      )}

      {pulseOpen && pulseBot && (
        <PulseCard
          state={pulseBot}
          onEnable={(enabled) => {
            void PolyBot.pulseUpdate({ enabled })
              .then(() => PolyBot.pulseState())
              .then(setPulseBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
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
          onDemo={(demo) => {
            void PolyBot.pulseUpdate({ demo })
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

      {probeOpen && probeBot && (
        <ProbeCard
          state={probeBot}
          onEnable={(enabled) => {
            void PolyBot.probeUpdate({ enabled })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onStake={(usdAmount) => {
            if (!Number.isFinite(usdAmount) || usdAmount <= 0) return;
            void PolyBot.probeUpdate({ stakeUsd: usdAmount })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onLead={(sec) => {
            if (!Number.isFinite(sec) || sec <= 0) return;
            void PolyBot.probeUpdate({ leadSec: Math.round(sec) })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onRoom={(share) => {
            if (!Number.isFinite(share) || share < 0) return;
            void PolyBot.probeUpdate({ roomShare: share })
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onDemo={(demo) => {
            void PolyBot.probeUpdate({ demo })
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
          onReset={() => {
            void PolyBot.probeReset()
              .then(() => PolyBot.probeState())
              .then(setProbeBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
        />
      )}

      {tab !== 'settings' && (
          <div className="card tight">
            {viewWindow != null && (
              <div className="listhead">
                <span>Событие {clockOf(viewWindow)}</span>
                <button className="linkbtn" onClick={() => setViewWindow(null)}>
                  к текущему
                </button>
              </div>
            )}

            {/*
              The hours before this window, as Binance's own five-minute
              candles, with the prices the market keeps turning at drawn on
              them. Under it the book: what the next few dollars would cost.
            */}
            {viewWindow == null && <CandlePanel interval="5m" height={150} />}

            {/*
              And the same hour close up. The five-minute chart says which way
              the day is going; this one says what price is doing right now,
              which on a bet that lasts five minutes is the half that decides
              the side.
            */}
            {viewWindow == null && <CandlePanel interval="1m" height={110} />}

            {viewWindow == null && <DepthPanel />}

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
                  // A resting sell is as much a price you might want to move
                  // as a resting buy — and it is the one you move in a hurry.
                  const editable = live != null;
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
                      {live ? (
                        <button
                          className="xbtn"
                          disabled={busy}
                          onClick={(e) => {
                            e.stopPropagation();
                            void cancel(live.id);
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
          <OrderHistory orders={logged} realised={realisedPnl} />

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
          */
          marketPrice={(() => {
            const side = editing.outcome === 'Up' ? 'Up' : 'Down';
            const level =
              editing.status === 'buying'
                ? books[side].asks[0]?.price
                : books[side].bids[0]?.price;
            return level != null ? Math.round(level * 100) : null;
          })()}
          onSave={(price, shares) =>
            void editOrder(
              editing.orderId as string,
              editing.outcome === 'Up' ? 'Up' : 'Down',
              editing.status === 'buying' ? 'BUY' : 'SELL',
              price,
              shares,
            )
          }
          onCancelOrder={() => {
            void cancel(editing.orderId as string);
            setEditing(null);
          }}
          onClose={() => setEditing(null)}
        />
      )}

      {note && <div className="banner info">{note}</div>}

      {tab !== 'settings' && <div className="dockgap" aria-hidden />}

      {/*
        The trading row is pinned to the bottom edge of the screen, not to the
        end of the page. It is the one thing here that is used rather than
        read, and a row that drifts up when the window is quiet is a row you
        have to look for.
      */}
      <div className={`dock${tab === 'settings' ? ' away' : ''}`}>
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
              elapsed={elapsed}
              ceiling={ceiling}
              chosen={side}
              onPick={(which, price) => {
                setSide(which);
                setLimitPrice(String(Math.round(price * 100)));
                // And the size the field is about to spend: all of it. The
                // shares wanted at this price are what the window still has
                // room for, and typing that out was the last thing here that
                // was typed by hand.
                setSizePct(100);
                const full =
                sizeBudget > 0
                  ? stakeShares(price, sizeBudget, 1, minSize)
                  : null;
                if (full != null) setLimitSize(String(full));
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
 * How the last few five-minute events went.
 *
 * A window is the unit this app trades, so it is the unit worth scoring: which
 * side it closed on, and what the round made. The colour is the side — green
 * for Up, red for Down — and the money keeps its own sign, because a round can
 * be lost on the side that won and won on the side that lost.
 *
 * Tapping one shows that window's orders below. The running window is the way
 * back, and the strip scrolls itself there whenever it changes.
 */
function EventStrip({
  events,
  windowStart,
  selected,
  onSelect,
}: {
  events: EventSummary[];
  windowStart: number;
  selected: number | null;
  onSelect: (windowStart: number | null) => void;
}) {
  const past = events.filter((e) => e.windowStart !== windowStart);
  const live = events.find((e) => e.windowStart === windowStart);

  return (
    <div className="card tight eventcard">
      <div className="eventstrip">
        <button
          className={`eventchip now${selected === null ? ' on' : ''}`}
          onClick={() => onSelect(null)}
        >
          <span className="eventchip-time">сейчас</span>
          <span
            className={
              live == null ? 'muted' : live.pnl >= 0 ? 'up' : 'down'
            }
          >
            {live == null ? '—' : signedUsd(live.pnl)}
          </span>
        </button>
        {past.map((e) => (
          <button
            key={e.windowStart}
            className={`eventchip${selected === e.windowStart ? ' on' : ''}${
              e.winner === 'Up' ? ' win-up' : e.winner === 'Down' ? ' win-down' : ''
            }`}
            onClick={() => onSelect(selected === e.windowStart ? null : e.windowStart)}
            title={`${clockOf(e.windowStart)} · закрытие ${e.winner || '—'}`}
          >
            <span className="eventchip-time">
              {clockOf(e.windowStart)}
              {e.winner && <b>{e.winner === 'Up' ? '↑' : '↓'}</b>}
            </span>
            <span className={e.pnl >= 0 ? 'up' : 'down'}>{signedUsd(e.pnl)}</span>
          </button>
        ))}
        {past.length === 0 && (
          <span className="muted empty">Сыгранных событий ещё нет</span>
        )}
      </div>
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
 * The rule that takes a gain the standing offer will not reach.
 *
 * One switch and one number, and under them what it is watching: what the
 * position cost, what the bids are paying, and how far apart those are. That
 * last figure is the whole rule — when it crosses the threshold the position
 * is sold into the book.
 */
function TakeCard({
  state,
  onEnable,
  onGain,
}: {
  state: TakeState;
  onEnable: (enabled: boolean) => void;
  onGain: (gain: number) => void;
}) {
  const [why, setWhy] = useState(false);
  const pct = Math.round(state.gain * 100);

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>Забираю плюс</span>
        <WhyButton open={why} onClick={() => setWhy((v) => !v)} />
        <button
          className={`switch ${state.enabled ? 'on' : ''}`}
          onClick={() => onEnable(!state.enabled)}
        />
      </div>

      {why && (
      <div className="counterrule muted">
        Смотрит не на свою лимитку, а на то, сколько дают в стакане. Как только
        покупатель платит больше +{pct}% к цене покупки — с учётом комиссии —
        снимает наши продажи по этой стороне и продаёт по рынку. Для случая,
        когда цена сходила в плюс, но до лимитки не дотянулась.
      </div>
      )}

      <div className="fields botbank">
        <label className="field">
          <span>продавать от, %</span>
          <input
            type="number"
            step="1"
            value={String(pct)}
            onChange={(e) =>
              onGain(Number(e.target.value.replace(',', '.')) / 100)
            }
          />
        </label>
      </div>

      {state.watching.length > 0 ? (
        <div className="counterlive">
          {state.watching.map((w, i) => (
            <div key={i}>
              <span className={w.outcome === 'Up' ? 'up' : 'down'}>{w.outcome}</span>{' '}
              {w.shares.toFixed(1)} по {cents(w.cost)} · дают{' '}
              {w.bid > 0 ? cents(w.bid) : '—'}{' '}
              <b className={w.gain >= state.gain ? 'up' : 'muted'}>
                {w.gain >= 0 ? '+' : '−'}
                {Math.abs(Math.round(w.gain * 100))}%
              </b>
            </div>
          ))}
        </div>
      ) : (
        <div className="counterlive muted">
          {state.enabled ? 'позиций нет' : 'выключено'}
        </div>
      )}

      {state.takes > 0 && (
        <div className="countergrid">
          <div>
            <span className="muted">забрал</span>
            <b>{state.takes}</b>
          </div>
          <div>
            <span className="muted">долей</span>
            <b>{state.shares.toFixed(1)}</b>
          </div>
          <div>
            <span className="muted">на</span>
            <b className="up">{usd(state.got)}</b>
          </div>
        </div>
      )}

      {state.lastFault && <div className="banner warn">{state.lastFault}</div>}
    </div>
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
  onEnable,
  onStake,
  onLead,
  onRoom,
  onDemo,
  onBank,
  onReset,
}: {
  state: ProbeState;
  onEnable: (enabled: boolean) => void;
  onStake: (usd: number) => void;
  onLead: (sec: number) => void;
  onRoom: (share: number) => void;
  onDemo: (demo: boolean) => void;
  onBank: (usd: number) => void;
  onReset: () => void;
}) {
  const [why, setWhy] = useState(false);
  const all = summarise(state.rounds);
  const sides = bySide(state.rounds);
  const line = state.trend;
  const way = line?.way ?? '';
  // Whether the rule is currently standing aside for the level, said in the
  // same words it says it in: the note it publishes starts with "у разворота".
  const near = (state.note ?? '').startsWith('у разворота');
  const tone = all.pnl > 0 ? 'up' : all.pnl < 0 ? 'down' : 'muted';

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>Проба</span>
        <WhyButton open={why} onClick={() => setWhy((v) => !v)} />
        <button
          className={`switch ${state.enabled ? 'on' : ''}`}
          onClick={() => onEnable(!state.enabled)}
        />
      </div>

      {why && (
      <div className="counterrule muted">
        {state.demo
          ? 'На бумаге: читает тот же живой стакан, берёт те же предложения по тем же ценам, платит ту же комиссию и выходит по тем же ступеням — только деньги ненастоящие и на биржу ничего не уходит. '
          : 'На реальные деньги. '}
        За {state.leadSec} с до начала пятиминутки берёт {usd(state.stakeUsd)}{' '}
        той стороны, куда показывает линия тренда на минутном графике, и
        выходит обычной лесенкой продаж. Не входит, если до уровня, от которого
        ждём разворот, осталось меньше{' '}
        {Math.round(state.roomShare * 100)}% обычного хода пятиминутки — тренд,
        упирающийся в стену, кончается на ней. Всё, что наторгует, ниже по окнам.
      </div>
      )}

      <div className="probeline">
        <span className="muted">линия 1м</span>
        <b className={way === 'Up' ? 'up' : way === 'Down' ? 'down' : 'muted'}>
          {way === 'Up' ? '↑ вверх' : way === 'Down' ? '↓ вниз' : '— вбок'}
        </b>
        {line && (
          <span className="muted">
            {line.perHour >= 0 ? '+' : '−'}
            {usd(Math.abs(line.perHour))}/ч · совпадение{' '}
            {Math.round(line.fit * 100)}%
          </span>
        )}
      </div>

      <div className="probeline">
        <span className="muted">разворот у</span>
        <b>{bigPrice(state.levelAhead)}</b>
        {state.roomToLevel != null && (
          <span className={near ? 'down' : 'muted'}>
            запас {bigPrice(state.roomToLevel)}
            {near ? ' — близко' : ''}
          </span>
        )}
      </div>

      {/*
        Paper or real, and what the paper account is worth. The switch is the
        first thing on the card after the name because it is the thing that
        decides what every number under it means.
      */}
      <div className="proberow demorow">
        <button
          className={`demoflag${state.demo ? ' on' : ''}`}
          onClick={() => onDemo(!state.demo)}
        >
          {state.demo ? 'демо' : 'реально'}
        </button>
        {state.demo ? (
          <>
            <span className="muted">счёт</span>
            <b className={state.bank >= state.bankUsd ? 'up' : 'down'}>
              {usd(state.bank)}
            </b>
            <span className="muted">из {usd(state.bankUsd)}</span>
          </>
        ) : (
          <span className="muted">торгует на деньги кошелька</span>
        )}
      </div>

      <div className="fields probefields">
        <label className="field">
          <span>ставка, $</span>
          <input
            type="number"
            step="1"
            value={String(state.stakeUsd)}
            onChange={(e) => onStake(Number(e.target.value.replace(',', '.')))}
          />
        </label>
        <label className="field">
          <span>за сколько, с</span>
          <input
            type="number"
            step="1"
            value={String(state.leadSec)}
            onChange={(e) => onLead(Number(e.target.value.replace(',', '.')))}
          />
        </label>
        <label className="field">
          <span>запас до уровня, %</span>
          <input
            type="number"
            step="10"
            value={String(Math.round(state.roomShare * 100))}
            onChange={(e) =>
              onRoom(Number(e.target.value.replace(',', '.')) / 100)
            }
          />
        </label>
        {state.demo && (
          <label className="field">
            <span>тестовый счёт, $</span>
            <input
              type="number"
              step="10"
              value={String(state.bankUsd)}
              onChange={(e) => onBank(Number(e.target.value.replace(',', '.')))}
            />
          </label>
        )}
      </div>

      <div className="counterlive muted">
        {state.riding.length > 0
          ? state.riding.map((r) => (
              <div key={r.windowStart}>
                <span className={r.side === 'Up' ? 'up' : 'down'}>{r.side}</span>{' '}
                {r.shares.toFixed(1)} по {cents(r.price)} — окно{' '}
                {clockOf(r.windowStart)} идёт
              </div>
            ))
          : state.note || (state.enabled ? 'жду окна' : 'выключено')}
      </div>

      {state.rounds.length > 0 ? (
        <>
          <div className="listhead second">
            <span>
              Итог за {all.rounds} окон
              {state.rounds.length > all.rounds
                ? ` · пропущено ${state.rounds.length - all.rounds}`
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
            {state.rounds.map((r) => (
              <ProbeRow key={r.windowStart} round={r} />
            ))}
          </div>
        </>
      ) : (
        <div className="counterlive muted">
          Пока ни одного закрытого окна. Первая запись появится через пять
          минут после открытия — покупкой или причиной, по которой её не было.
        </div>
      )}

      {state.lastFault && <div className="banner warn">{state.lastFault}</div>}
    </div>
  );
}

/** One window, as a line: what was taken, and what it came to. */
function ProbeRow({ round }: { round: ProbeRound }) {
  // A window it stood out of still gets a line, with the reason in place of
  // the numbers — that is the whole point of writing them down.
  if (!traded(round)) {
    return (
      <div className="proberow skipped">
        <span className="probewhen">{clockOf(round.windowStart)}</span>
        <span className="muted">—</span>
        <span className="muted">пропуск: {round.note || 'без причины'}</span>
        <span className="probemark">·</span>
        <b className="muted">—</b>
      </div>
    );
  }

  const money = pnlOf(round);
  const tone = money > 0.005 ? 'up' : money < -0.005 ? 'down' : 'muted';
  // What the shares averaged on the way out, sale and settlement together,
  // which is the number worth comparing with what they cost.
  const out =
    round.shares > 0 ? (round.proceeds + round.settled) / round.shares : 0;

  return (
    <div className="proberow">
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
    </div>
  );
}

function PulseCard({
  state,
  onEnable,
  onBank,
  onShares,
  onDemo,
  onReset,
}: {
  state: PulseState;
  onEnable: (enabled: boolean) => void;
  onBank: (usd: number) => void;
  onShares: (shares: number) => void;
  onDemo: (demo: boolean) => void;
  onReset: () => void;
}) {
  const [why, setWhy] = useState(false);
  const tone = state.pnl > 0 ? 'up' : state.pnl < 0 ? 'down' : 'muted';
  const read = state.read;
  const lot = state.lot;
  // Which side each reading is pointing at, so a glance says "three of four".
  const side = read ? (read.lead >= 0 ? 'up' : 'down') : 'muted';

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>Пульс</span>
        <WhyButton open={why} onClick={() => setWhy((v) => !v)} />
        <button
          className={`switch ${state.enabled ? 'on' : ''}`}
          onClick={() => onEnable(!state.enabled)}
        />
      </div>

      {why && (
      <div className="counterrule muted">
        Берёт {state.shares.toFixed(0)} долей стороны, за которую разом
        высказались четыре вещи: ход окна от его открытия, импульс минуток,
        объём под ним и перевес в стакане Binance. Выходит по{' '}
        +{Math.round(state.takePct * 100)}% лимиткой, режет по рынку, если
        перевес развернулся, и не продаёт вовсе, если к концу окна ведёт —
        расчёт платит доллар без комиссии.
        {state.demo &&
          ' В демо к этому добавлена лесенка продаж: продаёт по тому, до чего' +
            ' стакан дойдёт раньше — до своей цели или до ступени, — так что' +
            ' позиция не остаётся висеть.'}
      </div>
      )}

      {/*
        Paper or real. It decides what every number under it means, so it is
        the first thing after the name — and paper is the default, because a
        rule is worth watching for a day before it is trusted with anything.
      */}
      <div className="proberow demorow">
        <button
          className={`demoflag${state.demo ? ' on' : ''}`}
          onClick={() => onDemo(!state.demo)}
        >
          {state.demo ? 'демо' : 'реально'}
        </button>
        {state.demo ? (
          <>
            <span className="muted">счёт</span>
            <b className={state.cash >= state.bankUsd ? 'up' : 'down'}>
              {usd(state.cash)}
            </b>
            <span className="muted">из {usd(state.bankUsd)}</span>
          </>
        ) : (
          <span className="muted">торгует на деньги кошелька</span>
        )}
      </div>

      {read && (
        <div className="pulsegrid">
          <div>
            <span className="muted">ход</span>
            <b className={side}>
              {read.lead >= 0 ? '+' : '−'}
              {Math.abs(read.lead).toFixed(0)}$
            </b>
          </div>
          <div>
            <span className="muted">импульс</span>
            <b className={read.momentum >= 0 ? 'up' : 'down'}>
              {read.momentum >= 0 ? '+' : '−'}
              {Math.abs(read.momentum).toFixed(0)}$
            </b>
          </div>
          <div>
            <span className="muted">объём</span>
            <b className={read.volume >= 1 ? 'up' : 'muted'}>
              ×{read.volume.toFixed(2)}
            </b>
          </div>
          <div>
            <span className="muted">стакан</span>
            <b className={read.lean >= 0.5 ? 'up' : 'down'}>
              {Math.round(read.lean * 100)}%
            </b>
          </div>
        </div>
      )}

      <div className="fields botbank">
        <label className="field">
          <span>контейнер, $</span>
          <input
            type="number"
            step="1"
            value={String(state.bankUsd)}
            onChange={(e) => onBank(Number(e.target.value.replace(',', '.')))}
          />
        </label>
        <label className="field">
          <span>долей за раз</span>
          <input
            type="number"
            step="1"
            value={String(state.shares)}
            onChange={(e) => onShares(Number(e.target.value.replace(',', '.')))}
          />
        </label>
      </div>

      <div className="countergrid">
        <div>
          <span className="muted">свободно</span>
          <b>{usd(state.cash)}</b>
        </div>
        <div>
          <span className="muted">итог</span>
          <b className={tone}>{signedUsd(state.pnl)}</b>
        </div>
        <div>
          <span className="muted">кругов · +/−</span>
          <b>
            {state.rounds} · {state.wins}/{state.losses}
          </b>
        </div>
      </div>

      {lot ? (
        <div className="counterlive">
          <span className={lot.outcome === 'Up' ? 'up' : 'down'}>{lot.outcome}</span>{' '}
          {lot.shares.toFixed(1)} по {cents(lot.price)}
          {lot.sellPrice > 0 ? ` → ${cents(lot.sellPrice)}` : ''}
          {lot.note ? ` · ${lot.note}` : ''}
        </div>
      ) : (
        <div className="counterlive muted">{state.note ?? 'ждёт совпадения'}</div>
      )}

      {state.lastFault && <div className="banner warn">{state.lastFault}</div>}

      <div className="listhead bare">
        <button className="linkbtn" onClick={onReset}>
          обнулить счёт
        </button>
      </div>
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
  realised,
}: {
  orders: LoggedOrder[];
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
            <span className={`histstate ${statusTone(o.status)}`}>
              {statusWord(o.status)}
            </span>
          </div>
        ))}
    </div>
  );
}

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
        rebuyEnabled: next.autoRebuyEnabled,
        rebuyDropPct: next.autoRebuyDropPct,
        watchSec: next.autoSellWatchSec,
        rebuySlicePauseSec: next.autoRebuySlicePauseSec,
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
          className={`ruletile${settings.autoRebuyEnabled ? ' on' : ''}`}
          onClick={() =>
            push({ ...settings, autoRebuyEnabled: !settings.autoRebuyEnabled })
          }
        >
          <span className={`switch mini ${settings.autoRebuyEnabled ? 'on' : ''}`} />
          <b>докуп</b>
          <i>
            {state.rebuys.length > 0
              ? `ждёт ${state.rebuys.length}`
              : `−${Math.round(settings.autoRebuyDropPct * 100)}%`}
          </i>
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
        <b className={clockTone(secondsLeft, lookAhead)}>
          {`${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`}
        </b>
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
      rebuyEnabled: next.autoRebuyEnabled,
      rebuyDropPct: next.autoRebuyDropPct,
      watchSec: next.autoSellWatchSec,
      rebuySlicePauseSec: next.autoRebuySlicePauseSec,
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

          <label className="field">
            <span>плюс, %</span>
            <input
              type="number"
              value={String(Math.round(settings.autoSellProfitPct * 100))}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellProfitPct: Number(e.target.value.replace(',', '.')) / 100,
                })
              }
            />
          </label>

          <label className="field">
            <span>пауза, с</span>
            <input
              type="number"
              value={String(settings.autoSellSliceGapSec)}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellSliceGapSec: Number(e.target.value.replace(',', '.')),
                })
              }
            />
          </label>

          <label className="field">
            <span>финал, ¢</span>
            <input
              type="number"
              value={String(Math.round(settings.autoSellCloseFloor * 100))}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellCloseFloor:
                    Number(e.target.value.replace(',', '.')) / 100,
                })
              }
            />
          </label>

          <label className="field">
            <span>до финала, ¢</span>
            <input
              type="number"
              value={String(Math.round(settings.autoSellLateFloor * 100))}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellLateFloor: Number(e.target.value.replace(',', '.')) / 100,
                })
              }
            />
          </label>

          <label className="field">
            <span>полоса, с</span>
            <input
              type="number"
              value={String(settings.autoSellLateBandSec)}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellLateBandSec: Number(e.target.value.replace(',', '.')),
                })
              }
            />
          </label>

          <label className="field">
            <span>финал за, с</span>
            <input
              type="number"
              value={String(settings.autoSellPanicSec)}
              onChange={(e) =>
                push({
                  ...settings,
                  autoSellPanicSec: Number(e.target.value.replace(',', '.')),
                })
              }
            />
          </label>
        </div>
      ) : (
        <>
      <div className="fields">
      <label className="field">
        <span>шаг лимиток, ¢</span>
        <input
          type="number"
          value={String(Math.round(settings.limitLadderStep * 100))}
          onChange={(e) =>
            onChange({
              ...settings,
              limitLadderStep: Number(e.target.value.replace(',', '.')) / 100,
            })
          }
        />
      </label>

      <label className="field">
        <span>упреждение, с</span>
        <input
          type="number"
          value={String(settings.autoSellLeadSec)}
          onChange={(e) =>
            push({
              ...settings,
              autoSellLeadSec: Number(e.target.value.replace(',', '.')),
            })
          }
        />
      </label>

      {/*
        How long one rung holds. A minute spends the five over the whole
        window; thirty seconds spends them by the halfway mark, asking the
        higher prices while there is still time to reach them.
      */}
      <label className="field">
        <span>ступень, с</span>
        <input
          type="number"
          step="15"
          value={String(settings.autoSellStepSec)}
          onChange={(e) =>
            push({
              ...settings,
              autoSellStepSec: Number(e.target.value.replace(',', '.')),
            })
          }
        />
      </label>
      </div>

      <div className="rungs">
        {settings.autoSellLadder.map((price, i) => (
          <label className="rung" key={i}>
            <span>{i + 1}м</span>
            <input
              type="number"
              inputMode="numeric"
              value={String(Math.round(price * 100))}
              onChange={(e) => setRung(i, e.target.value)}
            />
          </label>
        ))}
      </div>
        </>
      )}

    </Fold>
    </>
  );
}
