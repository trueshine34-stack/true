import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  DEFAULT_MANUAL_SETTINGS,
  balanceShares,
  cappedShares,
  exposureFor,
  LIMIT_LADDER_COUNT,
  orderCost,
  sellableShares,
  stakeShares,
  limitShares,
  minShares,
  sharesFor,
  spendableBalance,
  type Exposure,
  type ManualSettings,
} from '../core/manual';
import { pairOrders, realised, type TradeRow } from '../core/trades';
import {
  SELL_GAINS,
  limitLadder,
  limitUpside,
  potentialProfit,
  signedUsd,
  targetPrice,
  usd,
} from '../core/money';
import { loadManualSettings, saveManualSettings } from '../core/storage';
import { Fold, SwitchFold } from './Fold';
import { ContainerCard } from './ContainerCard';
import type { Container, ContainerSplit } from '../core/container';
import {
  PolyBot,
  type AutoSellState,
  type BookLevels,
  type AutoSellRebuy,
  type AutoSellRebuyDone,
  type EventSummary,
  type LoggedOrder,
  type NativeMarket,
  type LadderState,
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

type Draft = {
  side: 'Up' | 'Down';
  action: 'BUY' | 'SELL';
  price: string;
  shares: string;
  /** What the position being sold cost, so a gain can be priced off it. */
  avg?: number;
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
  containerLocked = 0,
  onOpenBalance,
  container,
  containerSplit,
  onContainer,
  appSettings,
  locked,
}: {
  /** Potential profit of the round, for the header. */
  onSummary?: (potential: number) => void;
  /** What is in the market, so the container can split the whole deposit. */
  onCommitted?: (usd: number) => void;
  /** What the container holds back, in dollars. */
  containerLocked?: number;
  /** Opens the balance sheet; the balance lives on the desk's own rail now. */
  onOpenBalance?: () => void;
  /** The container itself, so its own settings live with its switch. */
  container?: Container;
  containerSplit?: ContainerSplit;
  onContainer?: (next: Container) => void;
  /** The app-wide settings, folded in under the desk's own. */
  appSettings?: ReactNode;
  /** The day's goal is met: no new exposure until midnight. */
  locked?: boolean;
}) {
  const [settings, setSettings] = useState<ManualSettings>(DEFAULT_MANUAL_SETTINGS);
  const [tab, setTab] = useState<'desk' | 'settings'>('desk');
  /** Binance's candle in progress: where the five minutes opened, and now. */
  const [btc, setBtc] = useState<{ open: number; last: number } | null>(null);
  const [market, setMarket] = useState<NativeMarket | null>(null);
  const [books, setBooks] = useState<Record<'Up' | 'Down', BookLevels>>({
    Up: { bids: [], asks: [] },
    Down: { bids: [], asks: [] },
  });
  const [draft, setDraft] = useState<Draft | null>(null);
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
  /** The size field is being edited: that is when sizing by wallet is useful. */
  const [sizingLimit, setSizingLimit] = useState(false);
  const [pickingPrice, setPickingPrice] = useState(false);
  const [ladderBot, setLadderBot] = useState<LadderState | null>(null);
  const [ladderOpen, setLadderOpen] = useState(false);
  /** The event strip is folded away until it is asked for. */
  const [sessionOpen, setSessionOpen] = useState(false);
  /** A resting order opened for editing. */
  const [editing, setEditing] = useState<TradeRow | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

  // Read inside pollers that must not re-subscribe every time a setting changes.
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

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
  // The window's opening price and where BTC is against it, from Binance —
  // the price everyone is actually watching. Polymarket settles Up or Down on
  // its own thirty-second TWAP, so this is a reference to trade by eye
  // against, not the number the window is decided on.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.binancePrice()
        .then((r) => {
          if (!cancelled && r.open > 0) setBtc({ open: r.open, last: r.last });
        })
        .catch(() => {});
    };
    read();
    // The move against the open is the number being watched second by second,
    // and it costs one small request.
    const timer = window.setInterval(read, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

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
    // Two full books every two seconds is a lot of requests for a number that
    // moves in cents, and the venue's patience is shared with the rules — the
    // buy-back was being refused a price while this polled.
    const timer = window.setInterval(read, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [tokenFor]);

  // The ladder bot runs on its own money in the service; the panel reads it.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.ladderState()
        .then((s) => {
          if (!cancelled) setLadderBot(s);
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
  const committed = useMemo(() => {
    const held = positions
      .filter((p) => !p.redeemable && p.size > 0)
      .reduce((sum, p) => sum + p.size * (p.avgPrice > 0 ? p.avgPrice : p.curPrice), 0);
    const resting = orders
      .filter((o) => o.side === 'BUY')
      .reduce((sum, o) => sum + orderCost(o.remaining, o.price), 0);
    return held + resting;
  }, [positions, orders]);

  useEffect(() => {
    onCommitted?.(committed);
  }, [committed, onCommitted]);

  const exposure = useMemo(
    // The bots' stakes are already inside what the container holds back —
    // they are reserves like any other, merged in one level up so the header
    // and the guard cannot disagree about the same dollar.
    () => exposureFor(balance ?? 0, committed, containerLocked),
    [balance, committed, containerLocked],
  );

  const guard = settings.exposureGuard && balance != null;

  /**
   * What one tap on a buy button does, worked out once so the label and the
   * order can never disagree. `short` marks the case where the wallet share
   * cannot reach the venue's floor: the order is still possible, but it spends
   * more than the rule allows and the button says so.
   */
  const quickFor = (which: 'Up' | 'Down') => {
    const ask = books[which].asks[0]?.price ?? null;
    if (ask == null) return null;

    // The size chosen in the row above wins. Picking ten shares and then
    // tapping a button that buys five is the panel disagreeing with itself,
    // and the tap is the faster of the two ways to buy — so it follows.
    const chosen = Number(limitSize.replace(',', '.'));
    const wanted = Number.isFinite(chosen) && chosen > 0
      ? chosen
      : settings.useBalanceShare && balance != null
        ? balanceShares(ask, balance, settings.balanceSharePct, minSize)
        : sharesFor(ask, settings, minSize);
    const short = wanted == null;
    // Whatever was asked for, never under what the venue will take: five
    // shares at ten cents is fifty cents and is simply refused.
    const size = Math.max(wanted ?? 0, minShares(ask, minSize));

    // The guard trims rather than refuses: a tap that buys a little less still
    // works, and the button says what it will actually do.
    if (!guard) return { ask, shares: size, short, capped: false };
    const allowed = cappedShares(size, ask, exposure.room, minSize);
    if (allowed == null) return { ask, shares: 0, short, capped: true, blocked: true };
    return { ask, shares: allowed, short, capped: allowed < size - 1e-9 };
  };

  /**
   * Where the current five-minute window opened, in GMX's own series. It is
   * the level the window turns on, so it belongs in the header next to the
   * price rather than only as a line on the chart.
   */
  const windowOpen = btc?.open ?? null;
  const drift = btc != null ? btc.last - btc.open : null;

  // From the clock, not the market: the countdown must keep running even in the
  // seconds where the new window's market has not loaded yet.
  const secondsLeft = Math.max(0, windowStart + WINDOW_SEC - Math.floor(now / 1000));

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
            `Контейнер: заявка на ${usd(cost)}, свободно ${usd(exposure.room)} ` +
              `(в рынке ${usd(exposure.committed)} из ${usd(exposure.cap)})`,
          );
          return;
        }
      }

      setBusy(true);
      try {
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
        if (r.success) setDraft(null);
      } catch (e) {
        setNote(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [market, deskWindow, guard, exposure, locked],
  );

  /**
   * One tap on a position opens a sell for the whole of it, priced at the bid
   * that is there right now. The price stays editable — the tap is meant to
   * save the typing, not to decide the trade.
   */
  const sellPosition = useCallback(
    (position: NativePosition) => {
      const which: 'Up' | 'Down' = position.outcome === 'Up' ? 'Up' : 'Down';
      const bid = books[which].bids[0]?.price ?? position.curPrice ?? 0.5;
      setDraft({
        side: which,
        action: 'SELL',
        price: String(Math.round(bid * 100)),
        // The whole position. Tapping it means "close this", and a size that
        // quietly leaves a few shares behind is a position still open.
        shares: String(sellableShares(position.size)),
        avg: position.avgPrice > 0 ? position.avgPrice : undefined,
      });
      setNote(null);
    },
    [books],
  );

  /**
   * Sell the whole draft at market.
   *
   * Priced through the bids rather than at the top of them: the top is rarely
   * deep enough for a whole position, and an order that only fills against it
   * leaves the rest resting — which is not what "sell at market" means. Walking
   * the book gives a price that clears the size, and anything that does not
   * fill rests harmlessly at the bottom of it.
   */
  const marketSell = useCallback(
    async (d: Draft) => {
      const shares = Number(d.shares.replace(',', '.'));
      if (!Number.isFinite(shares) || shares <= 0) {
        setNote('Нет объёма для продажи');
        return;
      }
      const bids = books[d.side].bids;
      if (bids.length === 0) {
        setNote(`Нет спроса по ${d.side}`);
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
      void place(d.side, 'SELL', Math.max(tick, price - tick), shares);
    },
    [books, market, place],
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

  const quickUp = quickFor('Up');
  const quickDown = quickFor('Down');

  const quickBuy = (which: 'Up' | 'Down') => {
    const quick = which === 'Up' ? quickUp : quickDown;
    if (!quick) {
      setNote(`Стакан ${which} пуст`);
      return;
    }
    if (quick.blocked) {
      setNote(
        `Контейнер: в рынке уже ${usd(exposure.committed)} из ${usd(exposure.cap)}`,
      );
      return;
    }
    if (quick.short) {
      setNote(
        `Минимум — ${minShares(quick.ask, minSize).toFixed(0)} долей, это больше ` +
          `${Math.round(settings.balanceSharePct * 100)}% баланса`,
      );
    }
    if (quick.capped) {
      setNote(
        `Контейнер: свободно ${usd(exposure.room)}, беру ${quick.shares.toFixed(1)} долей`,
      );
    }
    void place(which, 'BUY', quick.ask, quick.shares);
  };

  /**
   * The limit row seeds itself from whichever side is on the button, and the
   * size follows the price: under 20¢ five shares is under a dollar of
   * exposure, so cheap prices are sized by money instead.
   */
  const limitPriceNum = Number(limitPrice.replace(',', '.')) / 100;
  const limitDefaultSize = limitShares(limitPriceNum, minSize);
  const limitSizeNum = limitSize === '' ? limitDefaultSize : Number(limitSize.replace(',', '.'));

  /** What the size is priced at: the typed limit, or the ask it would default to. */
  const limitBasis =
    Number.isFinite(limitPriceNum) && limitPriceNum > 0
      ? limitPriceNum
      : (quickUp?.ask ?? quickDown?.ask ?? 0);

  /**
   * Three cents under the dearer side.
   *
   * A hand-placed limit here is nearly always a bid just below the favourite,
   * waiting for a dip that the five minutes usually provides. With no book to
   * read yet it falls back to the middle of the range.
   */
  const wheelCenter = (() => {
    const dearest = Math.max(quickUp?.ask ?? 0, quickDown?.ask ?? 0);
    if (!(dearest > 0)) return 50;
    return Math.min(99, Math.max(1, Math.round(dearest * 100) - 3));
  })();

  const nudgeLimit = (delta: number) => {
    const base = Number.isFinite(limitPriceNum) && limitPriceNum > 0
      ? Math.round(limitPriceNum * 100)
      : Math.round((quickUp?.ask ?? 0.5) * 100);
    setLimitPrice(String(Math.min(99, Math.max(1, base + delta))));
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
    const room = settings.exposureGuard ? exposure.room : (balance ?? 0);
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
        <button className="railbal" onClick={onOpenBalance}>
          <b>{balance === null ? '—' : balance.toFixed(2)}</b>
          {containerLocked > 0 && (
            <span className="muted">/{containerLocked.toFixed(2)}</span>
          )}
        </button>

        <button
          className={`railchip${sessionOpen ? ' on' : ''}`}
          onClick={() => setSessionOpen((v) => !v)}
        >
          <span className="muted">сессия</span>
          <b className={sessionPnl >= 0 ? 'up' : 'down'}>{signedUsd(sessionPnl)}</b>
        </button>

        {ladderBot && (
          <button
            className={`railchip right${ladderOpen ? ' on' : ''}`}
            onClick={() => setLadderOpen((v) => !v)}
          >
            <span className="muted">
              <i className={`raildot${ladderBot.running ? ' live' : ''}`} aria-hidden />
              лесенка
            </span>
            <b className={ladderBot.pnl > 0 ? 'up' : ladderBot.pnl < 0 ? 'down' : 'muted'}>
              {signedUsd(ladderBot.pnl)}
            </b>
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

      {ladderOpen && ladderBot && (
        <LadderCard
          state={ladderBot}
          onEnable={(enabled) => {
            void PolyBot.ladderUpdate({ enabled })
              .then(() => PolyBot.ladderState())
              .then(setLadderBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onBank={(usd) => {
            if (!Number.isFinite(usd) || usd < 0) return;
            void PolyBot.ladderUpdate({ bankUsd: usd })
              .then(() => PolyBot.ladderState())
              .then(setLadderBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onShares={(n) => {
            if (!Number.isFinite(n) || n <= 0) return;
            void PolyBot.ladderUpdate({ shares: n })
              .then(() => PolyBot.ladderState())
              .then(setLadderBot)
              .catch((e) => setNote(e instanceof Error ? e.message : String(e)));
          }}
          onReset={() => {
            void PolyBot.ladderReset()
              .then(() => PolyBot.ladderState())
              .then(setLadderBot)
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
              A closed window's positions are gone from the exchange; what it
              came to is the order history below, which is the thing actually
              worth reading afterwards.
            */}
            {viewWindow == null && (
              <PositionPair
                positions={livePositions}
                secondsLeft={secondsLeft}
                lookAhead={lookAhead}
                windowOpen={windowOpen}
                drift={drift}
                onSell={sellPosition}
              />
            )}

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
            balance={balance}
            exposure={exposure}
            ask={
              quickUp && quickDown
                ? Math.min(quickUp.ask, quickDown.ask)
                : (quickUp?.ask ?? quickDown?.ask ?? null)
            }
            onChange={apply}
            onNote={setNote}
          />
          <ManualSettingsForm
            settings={settings}
            container={container}
            containerSplit={containerSplit}
            onContainer={onContainer}
            onChange={apply}
            onNote={setNote}
          />
          {appSettings}
        </>
      ) : (
        <>
          {/*
            Selling a position: one price, chosen the way the limit editor
            chooses one. Buying from here made no sense — the desk has two
            buttons for that an inch below — and every field between the
            position and the sale is a second the book has to move in.
          */}
          {draft && (
            <SellSheet
              draft={draft}
              tick={market?.tickSize ?? 0.01}
              busy={busy}
              bid={books[draft.side].bids[0]?.price ?? null}
              onPrice={(cents_) => setDraft({ ...draft, price: String(cents_) })}
              onSell={() =>
                void place(
                  draft.side,
                  'SELL',
                  Number(draft.price) / 100,
                  Number(draft.shares),
                )
              }
              onMarket={() => void marketSell(draft)}
              onClose={() => setDraft(null)}
            />
          )}

          {!draft &&
            (autoSell.rebuys.length > 0 ||
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
          The size is picked, not typed. A limit here is either a share of the
          wallet or one of three standing clip sizes, and both are one tap —
          against a keypad that covers the book you are pricing against.
        */}
        {sizingLimit && (
          <div className="limitpcts pcts" onMouseDown={(e) => e.preventDefault()}>
            {[25, 50, 100].map((pct) => {
              const shares =
                balance != null && balance > 0
                  ? stakeShares(limitBasis, balance, pct / 100, minSize)
                  : null;
              return (
                <button
                  key={pct}
                  disabled={shares == null}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => {
                    if (shares == null) return;
                    setLimitSize(String(shares));
                    setSizingLimit(false);
                  }}
                >
                  {pct}%
                </button>
              );
            })}
            {[5, 10, 15].map((n) => (
              <button
                key={n}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => {
                  setLimitSize(String(n));
                  setSizingLimit(false);
                }}
              >
                {n}
              </button>
            ))}
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
        {limitSizeNum > 0 && limitBasis > 0 && (
          <div className="limitmeta">
            <span className="up">
              +
              {usd(
                limitUpside(limitSizeNum, limitBasis) *
                  (settings.limitLadder ? LIMIT_LADDER_COUNT + 1 : 1),
              )}
            </span>
          </div>
        )}
        <div className="limitrow">
          <button
            className="limit up"
            disabled={busy || locked}
            onClick={() => void placeLimit('Up')}
          >
            Up
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
                placeholder={quickUp ? String(Math.round(quickUp.ask * 100)) : '¢'}
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
          <button
            className="limit down"
            disabled={busy || locked}
            onClick={() => void placeLimit('Down')}
          >
            Down
          </button>
        </div>

        <div className="buybar">
          <button
            className="buy up"
            disabled={busy || !quickUp || quickUp.blocked || locked}
            onClick={() => quickBuy('Up')}
          >
            <b>{quickUp ? cents(quickUp.ask) : '—'}</b>
            <s>
              {!quickUp
                ? 'стакан пуст'
                : quickUp.blocked
                  ? 'контейнер'
                  : `${quickUp.shares.toFixed(0)} долей${quickUp.capped ? ' ·огр' : ''}`}
            </s>
          </button>
          <button
            className="buy down"
            disabled={busy || !quickDown || quickDown.blocked || locked}
            onClick={() => quickBuy('Down')}
          >
            <b>{quickDown ? cents(quickDown.ask) : '—'}</b>
            <s>
              {!quickDown
                ? 'стакан пуст'
                : quickDown.blocked
                  ? 'контейнер'
                  : `${quickDown.shares.toFixed(0)} долей${quickDown.capped ? ' ·огр' : ''}`}
            </s>
          </button>
        </div>
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
 * Selling what is held, at a price picked the same way an order is moved.
 *
 * One number, a step either side, the wheel under it, and the gains the sale
 * could be asked for — each of which is a price, solved so that what arrives
 * after the fee is the gain it names. The size is whatever the position is:
 * tapping it means "close this", and a field to argue with that was a field
 * nobody used.
 */
function SellSheet({
  draft,
  tick,
  busy,
  bid,
  onPrice,
  onSell,
  onMarket,
  onClose,
}: {
  draft: Draft;
  tick: number;
  busy: boolean;
  /** The top of the bid side: what selling right now would get. */
  bid: number | null;
  onPrice: (cents: number) => void;
  onSell: () => void;
  onMarket: () => void;
  onClose: () => void;
}) {
  const at = Math.max(1, Math.min(99, Math.round(Number(draft.price) || 0)));
  const step = Math.max(1, Math.round(tick * 100));
  const shares = Number(draft.shares.replace(',', '.')) || 0;

  return (
    <div className="card tight sellsheet">
      <div className="sheet-head">
        <h2>
          <span className={draft.side === 'Up' ? 'up' : 'down'}>{draft.side}</span>{' '}
          {shares.toFixed(shares % 1 ? 1 : 0)}
        </h2>
        <button className="xbtn" onClick={onClose} aria-label="Закрыть">
          ✕
        </button>
      </div>

      <div className="pricepick">
        <button className="step big" onClick={() => onPrice(Math.max(1, at - step))}>
          −
        </button>
        <div className="pricepick-now">
          <PriceSpinner value={at} onPick={onPrice} />
          <span className="muted">¢</span>
        </div>
        <button className="step big" onClick={() => onPrice(Math.min(99, at + step))}>
          +
        </button>
      </div>

      {/*
        The gain, not the price. Selling asks "how much more than it cost", and
        the fee comes out of the proceeds — so each chip solves for the price
        whose net is the gain it names.
      */}
      {draft.avg != null && (
        <div className="draftpcts pcts">
          {SELL_GAINS.map((gain) => {
            const price = targetPrice(draft.avg as number, gain, tick);
            return (
              <button
                key={gain}
                className={Math.round(price * 100) === at ? 'on' : undefined}
                onClick={() => onPrice(Math.round(price * 100))}
              >
                +{Math.round(gain * 100)}%
              </button>
            );
          })}
        </div>
      )}

      <div className="draftrow">
        <button className="primary compact" disabled={busy} onClick={onSell}>
          Продать {at}¢
        </button>
        {/* Not "market" but the number it is: that is the decision. */}
        <button
          className="danger compact"
          disabled={busy}
          onClick={onMarket}
          title="Продать всё по рынку"
        >
          {bid != null ? `сейчас ${Math.round(bid * 100)}¢` : 'Рынок'}
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
 * The ladder bot's account, and the rule it follows.
 *
 * Its whole strategy is two numbers compared once a minute — what the
 * favourite costs against what the ladder will ask for it — so the card shows
 * exactly those two, next to what the comparison has been worth.
 */
function LadderCard({
  state,
  onEnable,
  onBank,
  onShares,
  onReset,
}: {
  state: LadderState;
  onEnable: (enabled: boolean) => void;
  onBank: (usd: number) => void;
  onShares: (shares: number) => void;
  onReset: () => void;
}) {
  const round = state.round;
  const tone = state.pnl > 0 ? 'up' : state.pnl < 0 ? 'down' : 'muted';

  return (
    <div className="card tight">
      <div className="counterhead">
        <span>Бот лесенки</span>
        <button
          className={`switch ${state.enabled ? 'on' : ''}`}
          onClick={() => onEnable(!state.enabled)}
        />
      </div>

      <div className="counterrule muted">
        За 15 с до конца каждой минуты смотрит сторону, которая дороже. Если её
        можно купить дешевле, чем ступень лесенки на этой минуте, берёт{' '}
        {state.shares.toFixed(0)} долей и сразу ставит продажу на эту же ступень.
      </div>

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
          <span className="muted">окон · сделок</span>
          <b>
            {state.rounds} · {state.buys}/{state.sells}
          </b>
        </div>
      </div>

      {round && (
        <div className="counterlive muted">
          {round.side ? `${round.side} по ` : 'сторона не выбрана'}
          {round.ask != null ? cents(round.ask) : '—'}
          {round.rung > 0 ? ` · ступень ${cents(round.rung)}` : ''}
          {round.note ? ` · ${round.note}` : ''}
        </div>
      )}

      {round && round.lots.length > 0 &&
        round.lots.map((l, i) => (
          <div className="listrow static" key={i}>
            <span className={l.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
              {l.outcome}
            </span>
            <span className="listrow-main">
              {l.shares.toFixed(1)} × {cents(l.price)}
              <span className="sub muted">
                {usd(l.shares * l.price)}
                {l.sellPrice > 0 ? ` · продажа ${cents(l.sellPrice)}` : ''}
                {l.note ? ` · ${l.note}` : ''}
              </span>
            </span>
            <span className="listrow-pnl muted">
              {l.sold > 0 ? usd(l.proceeds) : '—'}
            </span>
          </div>
        ))}

      {state.lastFault && <div className="counterlive warn">{state.lastFault}</div>}

      <button className="ghost compact counterreset" onClick={onReset}>
        обнулить счёт бота
      </button>
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
  balance,
  exposure,
  ask,
  onChange,
  onNote,
}: {
  state: AutoSellState;
  settings: ManualSettings;
  balance: number | null;
  exposure: Exposure;
  /** Price a click would pay, for showing what the share works out to. */
  ask: number | null;
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
  // What a click really spends: the fee comes on top of the order, so the last
  // slice of the balance is never available to buy with. Priced off the cheaper
  // side, which is the one size questions are usually about.
  const stake =
    balance != null && ask != null
      ? spendableBalance(balance, ask) * settings.balanceSharePct
      : null;

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
          <b>контейнер</b>
          <i className={settings.exposureGuard && exposure.full ? 'warn' : undefined}>
            {!settings.exposureGuard
              ? 'выкл'
              : exposure.full
                ? 'лимит'
                : usd(exposure.room)}
          </i>
        </button>

        <button
          className={`ruletile${settings.useBalanceShare ? ' on' : ''}`}
          onClick={() =>
            onChange({ ...settings, useBalanceShare: !settings.useBalanceShare })
          }
        >
          <span className={`switch mini ${settings.useBalanceShare ? 'on' : ''}`} />
          <b>% баланса</b>
          <i>
            {settings.useBalanceShare && stake != null
              ? usd(stake)
              : `${Math.round(settings.balanceSharePct * 100)}%`}
          </i>
        </button>
      </div>

      {/*
        The share is the whole point of that last rule, so it is picked here
        rather than typed in settings — but only while the mode is on, or it is
        a row of buttons that does nothing.
      */}
      {settings.useBalanceShare && (
        <div className="pcts rules-pcts">
          {[25, 50, 100].map((pct) => (
            <button
              key={pct}
              className={
                Math.round(settings.balanceSharePct * 100) === pct ? 'on' : undefined
              }
              onClick={() => onChange({ ...settings, balanceSharePct: pct / 100 })}
            >
              {pct}%
            </button>
          ))}
          <span className="muted rules-note">
            {stake != null ? `${stake.toFixed(2)} $ за клик` : 'баланс не прочитан'}
          </span>
        </div>
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
function PositionPair({
  positions,
  secondsLeft,
  lookAhead,
  windowOpen,
  drift,
  onSell,
}: {
  positions: NativePosition[];
  secondsLeft: number;
  lookAhead: boolean;
  windowOpen: number | null;
  drift: number | null;
  onSell: (position: NativePosition) => void;
}) {
  const leg = (name: 'Up' | 'Down') => {
    const mine = positions.filter((p) => p.outcome === name);
    if (mine.length === 0) return null;
    const size = mine.reduce((a, p) => a + p.size, 0);
    const cost = mine.reduce((a, p) => a + p.size * p.avgPrice, 0);
    return { position: mine[0], size, avg: size > 0 ? cost / size : 0 };
  };

  const up = leg('Up');
  const down = leg('Down');

  /*
    A held side is a count and what it cost, and nothing else. The money it
    would make was four numbers that all move together and none of which
    changes what you do — the decision is the count, the average, and the
    clock between them.
  */
  const side = (name: 'Up' | 'Down', held: ReturnType<typeof leg>) => (
    <button
      className={`pairleg ${name === 'Up' ? 'up' : 'down'}${held ? '' : ' idle'}`}
      disabled={!held}
      onClick={() => held && onSell(held.position)}
    >
      <b>{name}</b>
      {held ? (
        <>
          <span className="pairsize">{held.size.toFixed(1)}</span>
          <span className="pairavg">{held.avg > 0 ? cents(held.avg) : '…'}</span>
        </>
      ) : (
        <span className="muted pairsize">—</span>
      )}
    </button>
  );

  return (
    <div className="pair">
      {side('Up', up)}

      {/*
        The clock and the open sit between the two sides, where the decision is
        actually made: this side or that one, and how long have I got.
      */}
      <div className="pairmid">
        <b className={clockTone(secondsLeft, lookAhead)}>
          {`${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`}
        </b>
        <span className="pairopen">
          {windowOpen != null ? windowOpen.toFixed(0) : '—'}
          {drift != null && (
            <i className={drift >= 0 ? 'up' : 'down'}>
              ({drift >= 0 ? '+' : '−'}
              {Math.abs(drift).toFixed(0)})
            </i>
          )}
        </span>
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
  onPick,
}: {
  value: number | null;
  /** Where the wheel lands when it opens, in cents. */
  center: number;
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
    scrollTo(center, false);
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
        {Array.from({ length: 99 }, (_, i) => i + 1).map((c) => (
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
        onClick={() => scrollTo(WHEEL_HIGH, true)}
      >
        {WHEEL_HIGH}
      </button>
    </div>
  );
}

/** The two ends of the strip that are worth one tap. */
const WHEEL_LOW = 15;
const WHEEL_HIGH = 85;

function ManualSettingsForm({
  settings,
  container,
  containerSplit,
  onContainer,
  onChange,
  onNote,
}: {
  settings: ManualSettings;
  container?: Container;
  containerSplit?: ContainerSplit;
  onContainer?: (next: Container) => void;
  onChange: (next: ManualSettings) => void;
  onNote: (text: string | null) => void;
}) {
  const setRule = (index: number, patch: Partial<{ maxPrice: number; shares: number }>) => {
    const rules = settings.sizeRules.map((r, i) => (i === index ? { ...r, ...patch } : r));
    onChange({ ...settings, sizeRules: rules });
  };

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
      title="Покупка по клику"
      note={`${Math.round(settings.balanceSharePct * 100)}% · $${settings.defaultStakeUsd}`}
    >

      <div className="fields">
        <label className="field">
          <span>доля, %</span>
          <input
            type="number"
            value={String(Math.round(settings.balanceSharePct * 100))}
            onChange={(e) =>
              onChange({
                ...settings,
                balanceSharePct: Number(e.target.value.replace(',', '.')) / 100,
              })
            }
          />
        </label>

        <label className="field">
          <span>сумма, $</span>
          <input
            type="number"
            step="0.5"
            value={String(settings.defaultStakeUsd)}
            onChange={(e) =>
              onChange({
                ...settings,
                defaultStakeUsd: Number(e.target.value.replace(',', '.')),
              })
            }
          />
        </label>

        <label className="field">
          <span>шаг лесенки, ¢</span>
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
      </div>

      <div className="toggle">
        <span>Лесенка по цене</span>
        <button
          className={`switch ${settings.useSizeLadder ? 'on' : ''}`}
          onClick={() =>
            onChange({ ...settings, useSizeLadder: !settings.useSizeLadder })
          }
        />
      </div>

      {settings.useSizeLadder && (
        <div className="rules">
          <div className="rules-head">
            <span>цена до, ¢</span>
            <span>долей</span>
          </div>
          {settings.sizeRules.map((r, i) => (
            <div className="rules-row" key={i}>
              <input
                type="number"
                inputMode="numeric"
                value={String(Math.round(r.maxPrice * 100))}
                onChange={(e) =>
                  setRule(i, { maxPrice: Number(e.target.value.replace(',', '.')) / 100 })
                }
              />
              <input
                type="number"
                inputMode="decimal"
                value={String(r.shares)}
                onChange={(e) =>
                  setRule(i, { shares: Number(e.target.value.replace(',', '.')) })
                }
              />
            </div>
          ))}
        </div>
      )}

    </Fold>

    {/*
      One row for the container: the switch that enforces it is the heading,
      and everything it is made of opens underneath. A fold whose only content
      was another switch was a fold for nothing.
    */}
    <SwitchFold
      title="Контейнер"
      on={settings.exposureGuard}
      note={
        containerSplit
          ? `${usd(containerSplit.locked)} заперто`
          : settings.exposureGuard
            ? 'включён'
            : 'выключен'
      }
      onToggle={() =>
        onChange({ ...settings, exposureGuard: !settings.exposureGuard })
      }
    >
      {container && containerSplit && onContainer ? (
        <ContainerCard
          container={container}
          split={containerSplit}
          onChange={onContainer}
        />
      ) : (
        <div className="muted empty">Контейнер ещё не загружен</div>
      )}
    </SwitchFold>

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
