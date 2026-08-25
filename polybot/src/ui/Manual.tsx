import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_MANUAL_SETTINGS,
  balanceShares,
  sellableShares,
  stakeShares,
  limitShares,
  minShares,
  sharesFor,
  spendableBalance,
  type ManualSettings,
} from '../core/manual';
import { findLevels } from '../core/levels';
import { pairOrders, realised } from '../core/trades';
import {
  SELL_GAINS,
  positionPnl,
  signedPct,
  signedUsd,
  targetPrice,
  usd,
} from '../core/money';
import {
  appendPositionHistory,
  loadPositionHistory,
  type PositionRecord,
} from '../core/history';
import { loadManualSettings, saveManualSettings } from '../core/storage';
import {
  PolyBot,
  type AutoSellState,
  type BookLevels,
  type GmxCandle,
  type AutoSellRebuy,
  type AutoSellRebuyDone,
  type EventSummary,
  type LoggedOrder,
  type NativeMarket,
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
 * Forty minutes on screen. Ninety made every candle two pixels wide, which is a
 * price history rather than something you can read a turn off; forty leaves
 * eight windows of context and candles wide enough to have shape.
 */
const CHART_MINUTES = 40;

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
export function Manual() {
  const [settings, setSettings] = useState<ManualSettings>(DEFAULT_MANUAL_SETTINGS);
  const [tab, setTab] = useState<'desk' | 'settings'>('desk');
  const [candles, setCandles] = useState<GmxCandle[]>([]);
  const [spot, setSpot] = useState<number | null>(null);
  const [market, setMarket] = useState<NativeMarket | null>(null);
  const [books, setBooks] = useState<Record<'Up' | 'Down', BookLevels>>({
    Up: { bids: [], asks: [] },
    Down: { bids: [], asks: [] },
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [positions, setPositions] = useState<NativePosition[]>([]);
  const [history, setHistory] = useState<PositionRecord[]>([]);
  const [showHistory, setShowHistory] = useState(false);
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
  const closedPositions = market
    ? positions.filter((p) => p.conditionId !== market.conditionId)
    : positions;

  // Chart. Candles are a slow-moving thing between windows, but the moment one
  // rolls the whole picture changes — a new open, a new shaded stretch, levels
  // that now sit relative to a different price — so the window is a dependency
  // and the fetch happens on the boundary rather than up to ten seconds later.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.polyCandles({ minutes: CHART_MINUTES })
        .then((r) => {
          if (cancelled) return;
          setCandles(r.candles);
          setSpot(r.ticker?.mid ?? r.candles[r.candles.length - 1]?.close ?? null);
        })
        .catch((e) => {
          if (!cancelled) setNote(e instanceof Error ? e.message : String(e));
        });
    };
    read();
    const timer = window.setInterval(read, 10_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [windowStart]);

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

  /** The window's orders, paired into trades: a buy and the sell that closed it. */
  const trades = useMemo(() => pairOrders(logged), [logged]);
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

  useEffect(() => {
    void loadPositionHistory().then(setHistory);
  }, []);

  // Archive what has dropped out of the current window. Keyed by window, so a
  // position seen on several polls is recorded once.
  useEffect(() => {
    if (closedPositions.length === 0) return;
    const records: PositionRecord[] = closedPositions.map((p) => ({
      windowStart: windowStart - WINDOW_SEC,
      conditionId: p.conditionId,
      outcome: p.outcome,
      size: p.size,
      avgPrice: p.avgPrice,
      lastPrice: p.curPrice,
      pnlUsd: p.cashPnl,
    }));
    void appendPositionHistory(records).then(setHistory);
    // Only the identity of what closed matters here; re-running on every price
    // tick would rewrite the same rows continuously.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [closedPositions.map((p) => p.asset).join(','), windowStart]);

  const apply = useCallback((next: ManualSettings) => {
    setSettings(next);
    void saveManualSettings(next);
  }, []);

  const minSize = market?.minimumOrderSize ?? 5;

  /**
   * What one tap on a buy button does, worked out once so the label and the
   * order can never disagree. `short` marks the case where the wallet share
   * cannot reach the venue's floor: the order is still possible, but it spends
   * more than the rule allows and the button says so.
   */
  const quickFor = (which: 'Up' | 'Down') => {
    const ask = books[which].asks[0]?.price ?? null;
    if (ask == null) return null;

    if (settings.useBalanceShare && balance != null) {
      const shares = balanceShares(ask, balance, settings.balanceSharePct, minSize);
      if (shares != null) return { ask, shares, short: false };
      return { ask, shares: minShares(ask, minSize), short: true };
    }
    return { ask, shares: sharesFor(ask, settings, minSize), short: false };
  };

  /**
   * Where the current five-minute window opened, in GMX's own series. It is
   * the level the window turns on, so it belongs in the header next to the
   * price rather than only as a line on the chart.
   */
  const windowOpen = useMemo(() => {
    if (candles.length === 0) return null;
    const start = Math.floor(candles[candles.length - 1].time / WINDOW_SEC) * WINDOW_SEC;
    return candles.find((c) => c.time >= start)?.open ?? null;
  }, [candles]);

  const drift = spot != null && windowOpen != null ? spot - windowOpen : null;

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
    [market, deskWindow],
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
        shares: sellableShares(position.size, bid).toFixed(1),
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
    if (quick.short) {
      setNote(
        `Минимум — ${minShares(quick.ask, minSize).toFixed(0)} долей, это больше ` +
          `${Math.round(settings.balanceSharePct * 100)}% баланса`,
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

  const nudgeLimit = (delta: number) => {
    const base = Number.isFinite(limitPriceNum) && limitPriceNum > 0
      ? Math.round(limitPriceNum * 100)
      : Math.round((quickUp?.ask ?? 0.5) * 100);
    setLimitPrice(String(Math.min(99, Math.max(1, base + delta))));
  };

  const placeLimit = (which: 'Up' | 'Down') => {
    if (!Number.isFinite(limitPriceNum) || limitPriceNum <= 0) {
      setNote('Укажите цену лимитки');
      return;
    }
    void place(which, 'BUY', limitPriceNum, limitSizeNum);
  };

  return (
    <>
      <EventStrip
        events={events}
        session={sessionPnl}
        windowStart={windowStart}
        selected={viewWindow}
        onSelect={setViewWindow}
      />

      <div className="card tight">
        <div className="deskbar">
          <div>
            <div className="muted" style={{ fontSize: 10 }}>
              BTC · Polymarket TWAP
            </div>
            <div className="deskprice">
              {spot != null ? `$${spot.toFixed(0)}` : '—'}
            </div>
          </div>
          <div style={{ textAlign: 'center' }}>
            <div className="muted" style={{ fontSize: 10 }}>
              открытие 5м
            </div>
            <div className="deskprice small">
              {windowOpen != null ? windowOpen.toFixed(0) : '—'}
              {drift != null && (
                <span className={drift >= 0 ? 'up' : 'down'}>
                  {' '}
                  {drift >= 0 ? '+' : '−'}
                  {Math.abs(drift).toFixed(0)}
                </span>
              )}
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className={`${lookAhead ? 'warn' : 'muted'}`} style={{ fontSize: 10 }}>
              {lookAhead ? 'до старта' : 'до конца'}
            </div>
            <div className="deskprice">
              {`${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`}
            </div>
          </div>
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
        <Chart candles={candles} spot={spot} windowOpen={windowOpen} />
      </div>

      {tab === 'settings' ? (
        <ManualSettingsForm settings={settings} onChange={apply} onNote={setNote} />
      ) : (
        <>
          <div className="card tight">
            <div className="listhead">
              <span>{viewWindow != null ? `Событие ${clockOf(viewWindow)}` : 'Позиции'}</span>
              {viewWindow != null ? (
                <button className="linkbtn" onClick={() => setViewWindow(null)}>
                  к текущему
                </button>
              ) : (
                <button
                  className="linkbtn"
                  onClick={() => setShowHistory((v) => !v)}
                >
                  {showHistory ? 'скрыть' : `история ${history.length || ''}`}
                </button>
              )}
            </div>
            {/*
              A closed window's positions are gone from the exchange; what is
              left is what was archived when it closed. Showing the live ones
              under a past event would attribute this window's money to it.
            */}
            {viewWindow != null ? (
              <PositionHistory
                records={history.filter((r) => r.windowStart === viewWindow)}
              />
            ) : showHistory ? (
              <PositionHistory records={history} />
            ) : livePositions.length === 0 ? (
              <div className="muted empty">Открытых позиций нет</div>
            ) : (
              livePositions.map((p) => (
                <button
                  className="listrow"
                  key={p.asset}
                  onClick={() => sellPosition(p)}
                >
                  <span className={p.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
                    {p.outcome}
                  </span>
                  <span className="listrow-main">
                    {/*
                      Money first. A share count means something different at
                      10¢ than at 90¢, and nothing at all at a glance — while a
                      zero average means the trade is not indexed yet, not that
                      it was free, so it shows a dash rather than a price that
                      never happened.
                    */}
                    {p.avgPrice > 0 ? usd(p.size * p.avgPrice) : '…'}
                    <span className="sub muted">
                      {p.size.toFixed(1)} × {p.avgPrice > 0 ? cents(p.avgPrice) : '…'}
                    </span>
                  </span>
                  <span className="listrow-now">
                    {p.curPrice != null ? usd(p.size * p.curPrice) : '—'}
                    <span className="sub muted">
                      {p.curPrice != null ? cents(p.curPrice) : '—'}
                    </span>
                  </span>
                  {(() => {
                    // What selling right now would actually pay, fee included —
                    // the exchange's own figure is the mark, which is a bigger
                    // number than the money.
                    const pnl = positionPnl(p.size, p.avgPrice, p.curPrice ?? 0);
                    return (
                      <span
                        className={`listrow-pnl ${
                          p.avgPrice > 0 ? (pnl.pnl >= 0 ? 'up' : 'down') : 'muted'
                        }`}
                      >
                        {p.avgPrice > 0 ? signedUsd(pnl.pnl) : '…'}
                        <span className="sub">
                          {p.avgPrice > 0 ? signedPct(pnl.pct) : ''}
                        </span>
                      </span>
                    );
                  })()}
                </button>
              ))
            )}

            <div className="listhead second">
              <span>Сделки окна</span>
              <span className={realisedPnl >= 0 ? 'up sessum' : 'down sessum'}>
                {trades.some((t) => t.status === 'closed') ? signedUsd(realisedPnl) : ''}
              </span>
            </div>
            {trades.length === 0 ? (
              <div className="muted empty">В этом окне сделок не было</div>
            ) : (
              trades.slice(0, 8).map((t) => {
                const live = t.orderId
                  ? orders.find((x) => x.id === t.orderId)
                  : undefined;
                return (
                  <div className={`listrow static trade trade-${t.status}`} key={t.key}>
                    <span className={t.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
                      {t.outcome}
                      {t.auto && <span className="muted"> а</span>}
                    </span>
                    <span className="listrow-main">
                      {/*
                        The round on one line: what it cost, what it fetched.
                        An arrow with nothing after it is a buy still looking
                        for its exit, which is a state worth seeing at a glance.
                      */}
                      {t.buyPrice != null ? usd(t.shares * t.buyPrice) : '—'}
                      <span className="arrow">→</span>
                      {t.sellPrice != null ? (
                        usd(t.shares * t.sellPrice)
                      ) : (
                        <span className="muted">
                          {t.status === 'buying' ? 'ждёт' : '…'}
                        </span>
                      )}
                      <span className="sub muted">
                        {t.shares.toFixed(1)} ·{' '}
                        {t.buyPrice != null ? cents(t.buyPrice) : '—'}
                        {t.sellPrice != null ? ` → ${cents(t.sellPrice)}` : ''}
                      </span>
                    </span>
                    <span
                      className={`listrow-pnl ${
                        t.pnl == null
                          ? 'muted'
                          : t.pnl >= 0
                            ? 'up'
                            : 'down'
                      }`}
                    >
                      {t.pnl == null ? '—' : signedUsd(t.pnl)}
                      <span className="sub">
                        {t.pnl == null || t.pct == null
                          ? t.status === 'buying'
                            ? 'лимитка'
                            : 'открыта'
                          : t.status === 'pending'
                            ? 'ждёт'
                            : signedPct(t.pct)}
                      </span>
                    </span>
                    {live ? (
                      <button
                        className="xbtn"
                        disabled={busy}
                        onClick={() => void cancel(live.id)}
                        aria-label="Снять ордер"
                      >
                        ✕
                      </button>
                    ) : (
                      <span className="orderdot" aria-hidden />
                    )}
                  </div>
                );
              })
            )}
          </div>

          {draft && (
            <div className="card tight">
              <div className="draftrow">
                <label className="mini">
                  <span>цена, ¢</span>
                  <input
                    type="number"
                    inputMode="numeric"
                    value={draft.price}
                    onChange={(e) => setDraft({ ...draft, price: e.target.value })}
                  />
                </label>
                <label className="mini">
                  <span>долей</span>
                  <input
                    type="number"
                    inputMode="decimal"
                    value={draft.shares}
                    onChange={(e) => setDraft({ ...draft, shares: e.target.value })}
                  />
                </label>
                <div className="mini">
                  <span>итого</span>
                  <div className="draftsum">
                    {(
                      (Number(draft.price) / 100) * Number(draft.shares) || 0
                    ).toFixed(2)}{' '}
                    $
                  </div>
                </div>
              </div>
              {/*
                Selling asks a different question from buying. Not "what price"
                — the price is a means — but "how much more than it cost". The
                fee comes out of the proceeds, so each chip solves for the price
                whose *net* is the gain it names.
              */}
              {draft.action === 'SELL' && draft.avg != null && (
                <div className="draftpcts pcts">
                  {SELL_GAINS.map((gain) => {
                    const price = targetPrice(draft.avg as number, gain, market?.tickSize ?? 0.01);
                    return (
                      <button
                        key={gain}
                        className={
                          Math.round(price * 100) === Math.round(Number(draft.price))
                            ? 'on'
                            : undefined
                        }
                        onClick={() =>
                          setDraft({ ...draft, price: String(Math.round(price * 100)) })
                        }
                        title={`${cents(price)} · ${usd(
                          Number(draft.shares.replace(',', '.')) * price || 0,
                        )}`}
                      >
                        +{Math.round(gain * 100)}%
                      </button>
                    );
                  })}
                </div>
              )}
              {draft.action !== 'SELL' && balance != null && balance > 0 && (
                <div className="draftpcts pcts">
                  {[25, 50, 100].map((pct) => {
                    const price = Number(draft.price) / 100;
                    const shares = stakeShares(price, balance, pct / 100, minSize);
                    return (
                      <button
                        key={pct}
                        disabled={shares == null}
                        onClick={() =>
                          shares != null &&
                          setDraft({ ...draft, shares: String(shares) })
                        }
                        title={
                          shares == null
                            ? 'Меньше минимального ордера'
                            : `${shares} долей`
                        }
                      >
                        {pct}%
                      </button>
                    );
                  })}
                  <span className="muted draftpct-hint">от баланса</span>
                </div>
              )}
              <div className="draftrow">
                <button
                  className="primary compact"
                  disabled={busy}
                  onClick={() =>
                    void place(
                      draft.side,
                      'BUY',
                      Number(draft.price) / 100,
                      Number(draft.shares),
                    )
                  }
                >
                  Купить {draft.side}
                </button>
                <button
                  className="danger compact"
                  disabled={busy}
                  onClick={() =>
                    void place(
                      draft.side,
                      'SELL',
                      Number(draft.price) / 100,
                      Number(draft.shares),
                    )
                  }
                >
                  Продать
                </button>
                <button
                  className="danger compact"
                  disabled={busy}
                  onClick={() => void marketSell(draft)}
                  title="Продать всё по рынку"
                >
                  Рынок
                </button>
                <button className="ghost compact narrow" onClick={() => setDraft(null)}>
                  ✕
                </button>
              </div>
            </div>
          )}

          {!draft &&
            (autoSell.rebuys.length > 0 ||
              (autoSell.rebuysDone?.length ?? 0) > 0) && (
              <RebuyCard state={autoSell} now={now} />
            )}

          {!draft && (
            <RuleBar
              state={autoSell}
              settings={settings}
              balance={balance}
              ask={
                quickUp && quickDown
                  ? Math.min(quickUp.ask, quickDown.ask)
                  : (quickUp?.ask ?? quickDown?.ask ?? null)
              }
              onChange={apply}
              onNote={setNote}
            />
          )}
        </>
      )}

      {lookAhead && !market && (
        <div className="banner warn">
          Следующее окно ещё не опубликовано — оно появляется незадолго до
          старта.
        </div>
      )}

      {note && <div className="banner info">{note}</div>}

      <div className="dock">
        {/*
          Chips for the size field, shown while it is being edited. A limit is
          usually sized as "some of the wallet", and the arithmetic — balance,
          less the fee, over the limit price — is not something to do on a
          phone with the keyboard already up.
        */}
        {sizingLimit && balance != null && balance > 0 && (
          <div className="limitpcts pcts" onMouseDown={(e) => e.preventDefault()}>
            {[25, 50, 100].map((pct) => {
              const shares = stakeShares(limitBasis, balance, pct / 100, minSize);
              return (
                <button
                  key={pct}
                  disabled={shares == null}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => shares != null && setLimitSize(String(shares))}
                  title={
                    shares == null
                      ? 'Меньше минимального ордера'
                      : `${shares} долей · ${usd(shares * limitBasis)}`
                  }
                >
                  {pct}%
                </button>
              );
            })}
            <span className="muted limitpct-hint">
              {limitSizeNum > 0 && limitBasis > 0
                ? `≈ ${usd(limitSizeNum * limitBasis)}`
                : 'от баланса'}
            </span>
          </div>
        )}
        <div className="limitrow">
          <button
            className="limit up"
            disabled={busy}
            onClick={() => placeLimit('Up')}
          >
            Up
          </button>
          <div className="limitmid">
            <div className="limitprice">
              <button className="step" onClick={() => nudgeLimit(-1)}>
                −
              </button>
              <input
                type="number"
                inputMode="numeric"
                placeholder={quickUp ? String(Math.round(quickUp.ask * 100)) : '¢'}
                value={limitPrice}
                onChange={(e) => setLimitPrice(e.target.value)}
              />
              <button className="step" onClick={() => nudgeLimit(1)}>
                +
              </button>
            </div>
            <input
              className="limitsize"
              type="number"
              inputMode="decimal"
              placeholder={`${limitDefaultSize.toFixed(0)} долей`}
              value={limitSize}
              onFocus={() => setSizingLimit(true)}
              onBlur={() => setSizingLimit(false)}
              onChange={(e) => setLimitSize(e.target.value)}
            />
          </div>
          <button
            className="limit down"
            disabled={busy}
            onClick={() => placeLimit('Down')}
          >
            Down
          </button>
        </div>

        <div className="buybar">
          <button
            className="buy up"
            disabled={busy || !quickUp}
            onClick={() => quickBuy('Up')}
          >
            <b>{quickUp ? cents(quickUp.ask) : '—'}</b>
            <s>{quickUp ? `${quickUp.shares.toFixed(0)} долей` : 'стакан пуст'}</s>
          </button>
          <button
            className="buy down"
            disabled={busy || !quickDown}
            onClick={() => quickBuy('Down')}
          >
            <b>{quickDown ? cents(quickDown.ask) : '—'}</b>
            <s>{quickDown ? `${quickDown.shares.toFixed(0)} долей` : 'стакан пуст'}</s>
          </button>
        </div>
      </div>
    </>
  );
}

/** Closed positions, newest first, stamped with the window they belonged to. */
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
  session,
  windowStart,
  selected,
  onSelect,
}: {
  events: EventSummary[];
  session: number;
  windowStart: number;
  selected: number | null;
  onSelect: (windowStart: number | null) => void;
}) {
  const past = events.filter((e) => e.windowStart !== windowStart);
  const live = events.find((e) => e.windowStart === windowStart);

  return (
    <div className="card tight eventcard">
      <div className="listhead">
        <span>Сессия</span>
        <span className={session >= 0 ? 'up sessum' : 'down sessum'}>
          {signedUsd(session)}
        </span>
      </div>
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

function PositionHistory({ records }: { records: PositionRecord[] }) {
  if (records.length === 0) {
    return <div className="muted empty">История пуста</div>;
  }
  return (
    <>
      {records.slice(0, 20).map((r) => (
        <div className="listrow static" key={`${r.windowStart}${r.outcome}`}>
          <span className={r.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
            {r.outcome}
          </span>
          <span className="listrow-main">
            {r.size.toFixed(1)} × {r.avgPrice > 0 ? cents(r.avgPrice) : '…'}
          </span>
          <span className="muted listrow-now">
            {new Date(r.windowStart * 1000).toLocaleTimeString('ru-RU', {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </span>
          <span className={`listrow-pnl ${r.pnlUsd >= 0 ? 'up' : 'down'}`}>
            {r.pnlUsd >= 0 ? '+' : '−'}
            {Math.abs(r.pnlUsd).toFixed(2)}
          </span>
        </div>
      ))}
    </>
  );
}


/**
 * One-minute candles from GMX.
 *
 * The five-minute grid is drawn in because that is what is actually being
 * traded: the dashed line is where the current window opened, and every bar
 * after it is the move that decides Up or Down. The price itself is GMX's own
 * oracle — close to the settlement feed but not it, so nothing here is a strike.
 */
function Chart({
  candles,
  spot,
  windowOpen,
}: {
  candles: GmxCandle[];
  spot: number | null;
  windowOpen: number | null;
}) {
  const levels = useMemo(() => findLevels(candles), [candles]);

  const view = useMemo(() => {
    if (candles.length === 0) return null;
    const W = 340;
    const H = 82;
    let lo = Math.min(...candles.map((c) => c.low));
    let hi = Math.max(...candles.map((c) => c.high));
    if (spot != null) {
      lo = Math.min(lo, spot);
      hi = Math.max(hi, spot);
    }
    const pad = (hi - lo) * 0.08 || 1;
    lo -= pad;
    hi += pad;

    const step = W / candles.length;
    const y = (p: number) => H - ((p - lo) / (hi - lo)) * H;

    const windowStart =
      Math.floor(candles[candles.length - 1].time / WINDOW_SEC) * WINDOW_SEC;
    const openIndex = candles.findIndex((c) => c.time >= windowStart);

    return { W, H, lo, hi, step, y, openIndex };
  }, [candles, spot]);

  if (!view) {
    return <div className="chart-empty muted">График загружается…</div>;
  }
  const { W, H, lo, hi, step, y, openIndex } = view;
  const openPrice = windowOpen;
  const inView = (p: number) => p > lo && p < hi;

  const visible = levels.filter((l) => inView(l.price));
  const labelled: typeof visible = [];
  for (const l of visible) {
    // Eleven pixels of chart is about one line of the label's own text.
    if (labelled.every((k) => Math.abs(y(k.price) - y(l.price)) > 11)) labelled.push(l);
  }

  return (
    <div className="chartwrap">
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
        {openIndex >= 0 && (
          <rect
            x={openIndex * step}
            y={0}
            width={W - openIndex * step}
            height={H}
            className="chart-window"
          />
        )}
        {visible.map((l) => (
          <line
            key={`${l.kind}${l.price}`}
            x1={0}
            x2={W}
            y1={y(l.price)}
            y2={y(l.price)}
            className={`chart-level ${l.kind}`}
            strokeWidth={l.touches >= 3 ? 1.4 : 0.8}
          />
        ))}
        {candles.map((c, i) => {
          const x = i * step + step / 2;
          const up = c.close >= c.open;
          const top = y(Math.max(c.open, c.close));
          const bottom = y(Math.min(c.open, c.close));
          return (
            <g key={c.time} className={up ? 'c-up' : 'c-down'}>
              <line x1={x} x2={x} y1={y(c.high)} y2={y(c.low)} strokeWidth={0.9} />
              <rect
                x={i * step + 0.8}
                width={Math.max(step - 1.6, 1)}
                y={top}
                height={Math.max(bottom - top, 1)}
              />
            </g>
          );
        })}
        {openPrice != null && inView(openPrice) && (
          <line
            x1={0}
            x2={W}
            y1={y(openPrice)}
            y2={y(openPrice)}
            className="chart-open"
          />
        )}
        {spot != null && inView(spot) && (
          <line x1={0} x2={W} y1={y(spot)} y2={y(spot)} className="chart-spot" />
        )}
      </svg>

      {/*
        Labels sit outside the SVG: the chart is stretched to fill its box, so
        text drawn inside it would be squashed with the candles. Levels close
        together get one label between them — two overlapping numbers are less
        readable than one, and the lines themselves are still both drawn. They
        hug the left edge: the newest candles are on the right and must stay
        clear. The window open needs no label here — it is in the header.
      */}
      {labelled.map((l) => (
        <span
          key={`t${l.kind}${l.price}`}
          className={`chart-tag ${l.kind}`}
          style={{ top: `${(y(l.price) / H) * 100}%` }}
        >
          {l.price.toFixed(0)}
          <i>{'·'.repeat(Math.min(l.touches, 4))}</i>
        </span>
      ))}
    </div>
  );
}

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
  ask,
  onChange,
  onNote,
}: {
  state: AutoSellState;
  settings: ManualSettings;
  balance: number | null;
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

  return (
    <div className="card tight">
      <div className="rule">
        <button
          className={`switch ${settings.autoSellEnabled ? 'on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellEnabled: !settings.autoSellEnabled })
          }
        />
        <span className="rule-name">
          Автопродажа
          {settings.autoSellPercentMode && (
            <span className="muted">
              {' '}
              +{Math.round(settings.autoSellProfitPct * 100)}%
            </span>
          )}
        </span>
        <span className={`rule-note ${state.lastFault ? 'down' : 'muted'}`}>
          {/*
            A rule that is on but not sweeping used to look identical to one
            with nothing to do. It now says which it is.
          */}
          {!settings.autoSellEnabled
            ? 'выключена'
            : state.lastFault
              ? state.lastFault
              : !state.running
                ? 'запускается…'
                : state.rows.length === 0
                  ? 'ждём покупку'
                  : stuck
                    ? // A position the rule cannot cover says why, right here.
                      // "Ждём покупку" while a bought lot sat with no sell was
                      // the least useful thing this line could have said.
                      `${stuck.outcome}: ${stuck.status}`
                    : `${rung != null ? `${Math.round(rung * 100)}¢ · ` : ''}` +
                      `покрыто ${covered}/${state.rows.length} · ${ago(state.lastSweepAt)}`}
        </span>
      </div>

      <div className="rule">
        <button
          className={`switch ${settings.autoRebuyEnabled ? 'on' : ''}`}
          onClick={() =>
            push({ ...settings, autoRebuyEnabled: !settings.autoRebuyEnabled })
          }
        />
        <span className="rule-name">Автодокуп</span>
        <span className="rule-note muted">
          {state.rebuys.length > 0
            ? `ждёт ${state.rebuys.length}`
            : `−${Math.round(settings.autoRebuyDropPct * 100)}%`}
        </span>
      </div>

      <div className="rule">
        <button
          className={`switch ${settings.useBalanceShare ? 'on' : ''}`}
          onClick={() =>
            onChange({ ...settings, useBalanceShare: !settings.useBalanceShare })
          }
        />
        {/*
          The share is the whole point of this rule, so it is picked here rather
          than typed in settings. The switch still decides whether the mode is
          on at all.
        */}
        <span className="pcts">
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
        </span>
        <span className="rule-note muted">
          {settings.useBalanceShare
            ? stake != null
              ? `${stake.toFixed(2)} $ за клик`
              : 'баланс не прочитан'
            : 'вместо лесенки размера'}
        </span>
      </div>

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

function ManualSettingsForm({
  settings,
  onChange,
  onNote,
}: {
  settings: ManualSettings;
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
    <div className="card">
      <h2>Покупка по клику</h2>

      <label className="field">
        <span>Доля баланса на клик, %</span>
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
        <span className="muted" style={{ fontSize: 11 }}>
          Работает, когда включён тумблер на панели. Заменяет лесенку размера:
          сколько бы ни стоила сторона, клик тратит одну и ту же долю кошелька.
        </span>
      </label>

      <label className="field">
        <span>Сумма по умолчанию, $</span>
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
        <span className="muted" style={{ fontSize: 11 }}>
          Используется, когда лесенка выключена или цена не попала ни в один
          диапазон.
        </span>
      </label>

      <div className="toggle">
        <div>
          <div>Лесенка по цене</div>
          <div className="muted" style={{ fontSize: 11 }}>
            Пять долей по 10¢ и по 90¢ — это 50¢ и 4,50 $ риска. Разное
            количество по диапазонам держит вложенное примерно ровным.
          </div>
        </div>
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

    </div>

    <div className="card">
      <h2>Автопродажа</h2>

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
        <>
          <div className="muted" style={{ fontSize: 11, marginBottom: 8 }}>
            Продажа выставляется от цены покупки: столько сверху, сколько указано
            — уже за вычетом комиссии, которая снимается с выручки. Несколько
            покупок продаются по частям, каждая следующая дороже предыдущей.
          </div>

          <label className="field">
            <span>Продавать при плюсе, %</span>
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
            <span className="muted" style={{ fontSize: 11 }}>
              Считается по тому, что сделка реально заплатит. Заявка на «avg ×
              1,2» приносит меньше двадцати процентов — комиссия берётся с
              выручки, а на дешёвой стороне она заметная.
            </span>
          </label>

          <label className="field">
            <span>Пауза между частями, сек</span>
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
            <span className="muted" style={{ fontSize: 11 }}>
              Если первая часть исполнилась — рынок идёт вверх, и остальное имеет
              смысл отдавать дороже, а не по той же цене.
            </span>
          </label>

          <label className="field">
            <span>Добирать любой плюс за, сек до конца</span>
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
            <span className="muted" style={{ fontSize: 11 }}>
              В последнюю минуту, если нужный процент так и не набрался, позиция
              уходит по тому, что даёт стакан, — но только пока это плюс после
              комиссии. Убыток не фиксируется: пятиминутка досиживается до
              расчёта.
            </span>
          </label>
        </>
      ) : (
        <>
      <div className="muted" style={{ fontSize: 11, marginBottom: 8 }}>
        Цена продажи по минуте окна: ступень встаёт на {settings.autoSellLeadSec}{' '}
        секунд раньше самой минуты. Лесенка перепрыгивает вперёд, если рынок уже
        прошёл ступень, и никогда не спускается обратно.
      </div>
      <label className="field">
        <span>Переключать ступень за, сек до минуты</span>
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
        <span className="muted" style={{ fontSize: 11 }}>
          Ступень, меняющаяся ровно на минуте, выставляет заявку в стакан ровно
          тогда, когда он переворачивается. Пятнадцать секунд форы ставят
          предложение раньше. Шаг между ступенями остаётся минутным — сдвигается
          вся последовательность.
        </span>
      </label>

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

      <label className="field" style={{ marginTop: 12 }}>
        <span>Сколько добиваться после покупки, сек</span>
        <input
          type="number"
          value={String(settings.autoSellWatchSec)}
          onChange={(e) =>
            push({
              ...settings,
              autoSellWatchSec: Number(e.target.value.replace(',', '.')),
            })
          }
        />
        <span className="muted" style={{ fontSize: 11 }}>
          Правило просыпается только после покупки и живёт эту минуту. Продажа,
          которую отклоняют минуту, отклоняется по причине, которую следующая
          попытка не изменит, — а бесконечные попытки упирались в лимит
          запросов data-api и убивали правило целиком.
        </span>
      </label>

      <label className="field">
        <span>Повтор, сек</span>
        <input
          type="number"
          value={String(settings.autoSellRetrySec)}
          onChange={(e) =>
            push({
              ...settings,
              autoSellRetrySec: Number(e.target.value.replace(',', '.')),
            })
          }
        />
        <span className="muted" style={{ fontSize: 11 }}>
          Доли не продаются сразу после покупки, поэтому правило повторяет
          попытку, пока биржа не примет ордер.
        </span>
      </label>

      <label className="field">
        <span>Пауза между докупами, сек</span>
        <input
          type="number"
          value={String(settings.autoRebuySlicePauseSec)}
          onChange={(e) =>
            push({
              ...settings,
              autoRebuySlicePauseSec: Number(e.target.value.replace(',', '.')),
            })
          }
        />
        <span className="muted" style={{ fontSize: 11 }}>
          Докуп идёт клипами того же размера, каким набиралась позиция: три по
          пять выкупаются по пять. Взять всё по первой подходящей цене — значит
          отдать остаток просадки.
        </span>
      </label>

      <label className="field">
        <span>Автодокуп при падении на, %</span>
        <input
          type="number"
          value={String(Math.round(settings.autoRebuyDropPct * 100))}
          onChange={(e) =>
            push({
              ...settings,
              autoRebuyDropPct: Number(e.target.value.replace(',', '.')) / 100,
            })
          }
        />
        <span className="muted" style={{ fontSize: 11 }}>
          Считаются только продажи, выставленные самим правилом: позиция
          уменьшается и когда вы продаёте руками, а выкупать это обратно —
          противоположное тому, что вы имели в виду.
        </span>
      </label>
    </div>
    </>
  );
}
