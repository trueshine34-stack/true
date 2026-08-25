import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_MANUAL_SETTINGS,
  balanceShares,
  limitShares,
  sharesFor,
  type ManualSettings,
} from '../core/manual';
import { findLevels } from '../core/levels';
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
  type NativeMarket,
  type NativePosition,
  type OpenOrder,
} from '../native/polybot';

const cents = (p: number) => `${Math.round(p * 100)}¢`;

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
  const [autoSell, setAutoSell] = useState<AutoSellState>(IDLE_AUTOSELL);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [limitPrice, setLimitPrice] = useState('');
  const [limitSize, setLimitSize] = useState('');
  const [balance, setBalance] = useState<number | null>(null);

  // Read inside pollers that must not re-subscribe every time a setting changes.
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  useEffect(() => {
    void loadManualSettings().then((stored) => {
      setSettings(stored);
      // The rules live in the native service, which starts every process with
      // them off. Without this the switch would read as on from the store while
      // nothing was actually sweeping — the toggle looked armed and was not.
      if (stored.autoSellEnabled) {
        void PolyBot.autoSellUpdate({
          enabled: true,
          ladder: stored.autoSellLadder,
          retryEverySec: stored.autoSellRetrySec,
          rebuyEnabled: stored.autoRebuyEnabled,
          rebuyDropPct: stored.autoRebuyDropPct,
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
      void PolyBot.gmxCandles({ symbol: 'BTC', period: '1m', limit: CHART_MINUTES })
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

  /** True while the loaded market is not the one the clock says we are in. */
  const marketStale = market?.windowStart != null && market.windowStart !== windowStart;

  // The market must never lag the clock: its token ids are what orders are sent
  // against, so a stale one would place a buy in the window that just ended.
  // While it is behind — including the second or two Gamma needs to index a new
  // window — this polls hard, and backs off once it has caught up.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.getCurrentMarket()
        .then((m) => {
          if (!cancelled) setMarket(m);
        })
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, marketStale ? 2000 : 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [windowStart, marketStale]);

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
    const timer = window.setInterval(read, 2000);
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
    };
    read();
    const timer = window.setInterval(read, 4000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

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
          if (s.enabled !== settingsRef.current.autoSellEnabled || (s.enabled && !s.running)) {
            void PolyBot.autoSellUpdate({
              enabled: settingsRef.current.autoSellEnabled,
              ladder: settingsRef.current.autoSellLadder,
              retryEverySec: settingsRef.current.autoSellRetrySec,
              rebuyEnabled: settingsRef.current.autoRebuyEnabled,
              rebuyDropPct: settingsRef.current.autoRebuyDropPct,
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
      return { ask, shares: minSize, short: true };
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
      if (market.windowStart != null && market.windowStart !== windowStart) {
        // The window rolled and the new market has not arrived yet. Sending
        // this would buy into the window that just closed.
        setNote('Окно сменилось — ждём новый рынок');
        return;
      }
      if (!Number.isFinite(price) || price <= 0 || price >= 1) {
        setNote('Цена вне диапазона');
        return;
      }
      if (!Number.isFinite(shares) || shares < market.minimumOrderSize) {
        setNote(`Минимум биржи — ${market.minimumOrderSize} долей`);
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
    [market, windowStart],
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
        shares: position.size.toFixed(1),
      });
      setNote(null);
    },
    [books],
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
        `Минимум биржи — ${minSize} долей, это больше ` +
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
      <div className="card tight">
        <div className="deskbar">
          <div>
            <div className="muted" style={{ fontSize: 10 }}>
              BTC · GMX 1м
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
            <div className="muted" style={{ fontSize: 10 }}>
              до конца
            </div>
            <div className="deskprice">
              {`${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`}
            </div>
          </div>
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
              <span>Позиции</span>
              <button
                className="linkbtn"
                onClick={() => setShowHistory((v) => !v)}
              >
                {showHistory ? 'скрыть' : `история ${history.length || ''}`}
              </button>
            </div>
            {showHistory ? (
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
                      A zero average means the trade is not indexed yet, not
                      that it was free. Showing a dash beats showing a price
                      that never happened — and the P&L built on it too.
                    */}
                    {p.size.toFixed(1)} × {p.avgPrice > 0 ? cents(p.avgPrice) : '…'}
                  </span>
                  <span className="listrow-now">
                    {p.curPrice != null ? cents(p.curPrice) : '—'}
                  </span>
                  <span
                    className={`listrow-pnl ${
                      p.avgPrice > 0 ? (p.cashPnl >= 0 ? 'up' : 'down') : 'muted'
                    }`}
                  >
                    {p.avgPrice > 0
                      ? `${p.cashPnl >= 0 ? '+' : '−'}${Math.abs(p.cashPnl).toFixed(2)}`
                      : '…'}
                  </span>
                </button>
              ))
            )}

            <div className="listhead second">
              <span>Ордера</span>
              <span className="muted">
                {orders.length > 0 ? `${orders.length} шт` : ''}
              </span>
            </div>
            {orders.length === 0 ? (
              <div className="muted empty">Активных ордеров нет</div>
            ) : (
              orders.slice(0, 4).map((o) => (
                <div className="listrow static" key={o.id}>
                  <span className={o.side === 'BUY' ? 'up tag-side' : 'down tag-side'}>
                    {o.side === 'BUY' ? 'ПОК' : 'ПРО'}
                  </span>
                  <span className="listrow-main">
                    {o.remaining.toFixed(1)} × {cents(o.price)}
                  </span>
                  <span className="muted listrow-now">{o.outcome ?? ''}</span>
                  <button
                    className="xbtn"
                    disabled={busy}
                    onClick={() => void cancel(o.id)}
                    aria-label="Снять ордер"
                  >
                    ✕
                  </button>
                </div>
              ))
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
                <button className="ghost compact narrow" onClick={() => setDraft(null)}>
                  ✕
                </button>
              </div>
            </div>
          )}

          {!draft && (
            <RuleBar
              state={autoSell}
              settings={settings}
              balance={balance}
              onChange={apply}
              onNote={setNote}
            />
          )}
        </>
      )}

      {note && <div className="banner info">{note}</div>}

      <div className="dock">
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
  onChange,
  onNote,
}: {
  state: AutoSellState;
  settings: ManualSettings;
  balance: number | null;
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
      }).catch((e) => onNote(e instanceof Error ? e.message : String(e)));
    },
    [onChange, onNote],
  );

  const covered = state.rows.filter((r) => r.status === 'покрыто').length;
  const rung = state.rows.length > 0 ? Math.max(...state.rows.map((r) => r.target)) : null;
  const stake = balance != null ? balance * settings.balanceSharePct : null;

  return (
    <div className="card tight">
      <div className="rule">
        <button
          className={`switch ${settings.autoSellEnabled ? 'on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellEnabled: !settings.autoSellEnabled })
          }
        />
        <span className="rule-name">Автопродажа</span>
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
                  ? `нет позиций · проверка ${ago(state.lastSweepAt)}`
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
            ? `ждём ${state.rebuys.map((r) => `${Math.round(r.trigger * 100)}¢`).join(', ')}`
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
        <span className="rule-name">
          {Math.round(settings.balanceSharePct * 100)}% баланса
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
      <div className="muted" style={{ fontSize: 11, marginBottom: 8 }}>
        Цена продажи по минуте окна. Лесенка перепрыгивает вперёд, если рынок
        уже прошёл ступень, и никогда не спускается обратно.
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

      <label className="field" style={{ marginTop: 12 }}>
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
