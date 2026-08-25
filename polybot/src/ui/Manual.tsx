import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_MANUAL_SETTINGS,
  sharesFor,
  type ManualSettings,
} from '../core/manual';
import { loadManualSettings, saveManualSettings } from '../core/storage';
import {
  PolyBot,
  type AutoSellState,
  type BookLevel,
  type BookLevels,
  type GmxCandle,
  type NativeMarket,
} from '../native/polybot';

const cents = (p: number) => `${Math.round(p * 100)}¢`;
const WINDOW_SEC = 300;

type Draft = {
  side: 'Up' | 'Down';
  action: 'BUY' | 'SELL';
  price: string;
  shares: string;
};

const IDLE_AUTOSELL: AutoSellState = {
  enabled: false,
  running: false,
  price: 0.97,
  retryEverySec: 7,
  lastSweepAt: 0,
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
  const [side, setSide] = useState<'Up' | 'Down'>('Up');
  const [books, setBooks] = useState<Record<'Up' | 'Down', BookLevels>>({
    Up: { bids: [], asks: [] },
    Down: { bids: [], asks: [] },
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [autoSell, setAutoSell] = useState<AutoSellState>(IDLE_AUTOSELL);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const sideRef = useRef(side);
  sideRef.current = side;

  useEffect(() => {
    void loadManualSettings().then(setSettings);
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // Chart. One minute of candles is a slow-moving thing; the last price comes
  // with the same call, so a 10-second refresh keeps both current enough.
  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.gmxCandles({ symbol: 'BTC', period: '1m', limit: 90 })
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
  }, []);

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
    const timer = window.setInterval(read, 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

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

  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.autoSellState()
        .then((s) => {
          if (!cancelled) setAutoSell(s);
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

  const book = books[side];
  const bestAsk = book.asks[0]?.price ?? null;
  const bestBid = book.bids[0]?.price ?? null;

  const minSize = market?.minimumOrderSize ?? 5;
  const quickFor = (which: 'Up' | 'Down') => {
    const ask = books[which].asks[0]?.price ?? null;
    return ask == null ? null : { ask, shares: sharesFor(ask, settings, minSize) };
  };

  const secondsLeft = market?.windowEnd
    ? Math.max(0, market.windowEnd - Math.floor(now / 1000))
    : null;

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
    [market],
  );

  /** One tap on a level: price comes from the book, size from the ladder. */
  const openDraft = useCallback(
    (level: BookLevel, action: 'BUY' | 'SELL') => {
      const shares =
        action === 'BUY'
          ? sharesFor(level.price, settings, market?.minimumOrderSize ?? 5)
          : Math.max(level.size, market?.minimumOrderSize ?? 5);
      setDraft({
        side: sideRef.current,
        action,
        price: String(Math.round(level.price * 100)),
        shares: shares.toFixed(1),
      });
      setNote(null);
    },
    [settings, market],
  );

  const quickBuy = useCallback(
    (which: 'Up' | 'Down') => {
      const ask = books[which].asks[0]?.price ?? null;
      if (ask == null) {
        setNote(`Стакан ${which} пуст`);
        return;
      }
      void place(which, 'BUY', ask, sharesFor(ask, settings, minSize));
    },
    [books, place, settings, minSize],
  );

  const quickUp = quickFor('Up');
  const quickDown = quickFor('Down');

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
          <div style={{ textAlign: 'right' }}>
            <div className="muted" style={{ fontSize: 10 }}>
              до конца окна
            </div>
            <div className="deskprice">
              {secondsLeft == null
                ? '—'
                : `${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, '0')}`}
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
        <Chart candles={candles} spot={spot} />
      </div>

      {tab === 'settings' ? (
        <ManualSettingsForm settings={settings} onChange={apply} />
      ) : (
        <>
          <div className="card tight">
            <div className="segmented">
              <button
                className={side === 'Up' ? 'active up' : ''}
                onClick={() => setSide('Up')}
              >
                Up {quickUp ? cents(quickUp.ask) : ''}
              </button>
              <button
                className={side === 'Down' ? 'active down' : ''}
                onClick={() => setSide('Down')}
              >
                Down {quickDown ? cents(quickDown.ask) : ''}
              </button>
            </div>

            <div className="dom">
              {[...book.asks].reverse().map((l) => (
                <button
                  className="dom-row ask"
                  key={`a${l.price}`}
                  onClick={() => openDraft(l, 'BUY')}
                >
                  <span className="dom-size">{l.size.toFixed(0)}</span>
                  <span className="dom-bar">
                    <i style={{ width: `${barWidth(l, book)}%` }} />
                  </span>
                  <span className="dom-price">{cents(l.price)}</span>
                </button>
              ))}
              <div className="dom-spread">
                {bestBid != null && bestAsk != null
                  ? `спред ${Math.round((bestAsk - bestBid) * 100)}¢`
                  : 'стакан пуст'}
              </div>
              {book.bids.map((l) => (
                <button
                  className="dom-row bid"
                  key={`b${l.price}`}
                  onClick={() => openDraft(l, 'SELL')}
                >
                  <span className="dom-size">{l.size.toFixed(0)}</span>
                  <span className="dom-bar">
                    <i style={{ width: `${barWidth(l, book)}%` }} />
                  </span>
                  <span className="dom-price">{cents(l.price)}</span>
                </button>
              ))}
            </div>
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
            <AutoSellBar
              state={autoSell}
              settings={settings}
              onChange={apply}
              onNote={setNote}
            />
          )}
        </>
      )}

      {note && <div className="banner info">{note}</div>}

      <div className="buybar">
        <button
          className="buy up"
          disabled={busy || !quickUp}
          onClick={() => quickBuy('Up')}
        >
          <b>Купить Up</b>
          <s>
            {quickUp
              ? `${cents(quickUp.ask)} · ${quickUp.shares.toFixed(0)} долей`
              : 'стакан пуст'}
          </s>
        </button>
        <button
          className="buy down"
          disabled={busy || !quickDown}
          onClick={() => quickBuy('Down')}
        >
          <b>Купить Down</b>
          <s>
            {quickDown
              ? `${cents(quickDown.ask)} · ${quickDown.shares.toFixed(0)} долей`
              : 'стакан пуст'}
          </s>
        </button>
      </div>
    </>
  );
}

/** Depth bar width, scaled to the biggest level on screen. */
function barWidth(level: BookLevel, book: BookLevels): number {
  const peak = Math.max(
    ...book.asks.map((l) => l.size),
    ...book.bids.map((l) => l.size),
    1,
  );
  return Math.min(100, (level.size / peak) * 100);
}

/**
 * One-minute candles from GMX.
 *
 * The five-minute grid is drawn in because that is what is actually being
 * traded: the dashed line is where the current window opened, and every bar
 * after it is the move that decides Up or Down. The price itself is GMX's own
 * oracle — close to the settlement feed but not it, so nothing here is a strike.
 */
function Chart({ candles, spot }: { candles: GmxCandle[]; spot: number | null }) {
  const view = useMemo(() => {
    if (candles.length === 0) return null;
    const W = 340;
    const H = 118;
    const lows = candles.map((c) => c.low);
    const highs = candles.map((c) => c.high);
    let lo = Math.min(...lows);
    let hi = Math.max(...highs);
    if (spot != null) {
      lo = Math.min(lo, spot);
      hi = Math.max(hi, spot);
    }
    const pad = (hi - lo) * 0.08 || 1;
    lo -= pad;
    hi += pad;

    const step = W / candles.length;
    const y = (p: number) => H - ((p - lo) / (hi - lo)) * H;

    const windowStart = Math.floor(candles[candles.length - 1].time / WINDOW_SEC) * WINDOW_SEC;
    const openIndex = candles.findIndex((c) => c.time >= windowStart);
    const openPrice = openIndex >= 0 ? candles[openIndex].open : null;

    return { W, H, lo, hi, step, y, windowStart, openIndex, openPrice };
  }, [candles, spot]);

  if (!view) {
    return <div className="chart-empty muted">График загружается…</div>;
  }
  const { W, H, step, y, openIndex, openPrice } = view;

  return (
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
      {candles.map((c, i) => {
        const x = i * step + step / 2;
        const up = c.close >= c.open;
        const top = y(Math.max(c.open, c.close));
        const bottom = y(Math.min(c.open, c.close));
        return (
          <g key={c.time} className={up ? 'c-up' : 'c-down'}>
            <line x1={x} x2={x} y1={y(c.high)} y2={y(c.low)} strokeWidth={1} />
            <rect
              x={i * step + 0.6}
              width={Math.max(step - 1.2, 0.8)}
              y={top}
              height={Math.max(bottom - top, 1)}
            />
          </g>
        );
      })}
      {openPrice != null && (
        <line
          x1={0}
          x2={W}
          y1={y(openPrice)}
          y2={y(openPrice)}
          className="chart-open"
        />
      )}
      {spot != null && (
        <line x1={0} x2={W} y1={y(spot)} y2={y(spot)} className="chart-spot" />
      )}
    </svg>
  );
}

function AutoSellBar({
  state,
  settings,
  onChange,
  onNote,
}: {
  state: AutoSellState;
  settings: ManualSettings;
  onChange: (next: ManualSettings) => void;
  onNote: (text: string | null) => void;
}) {
  const push = useCallback(
    (next: ManualSettings) => {
      onChange(next);
      void PolyBot.autoSellUpdate({
        enabled: next.autoSellEnabled,
        price: next.autoSellPrice,
        retryEverySec: next.autoSellRetrySec,
      }).catch((e) => onNote(e instanceof Error ? e.message : String(e)));
    },
    [onChange, onNote],
  );

  const covered = state.rows.filter((r) => r.status === 'покрыто').length;

  return (
    <div className="card tight">
      <div className="autosell">
        <button
          className={`switch ${settings.autoSellEnabled ? 'on' : ''}`}
          onClick={() =>
            push({ ...settings, autoSellEnabled: !settings.autoSellEnabled })
          }
        />
        <div className="autosell-label">
          <div>Автопродажа всего</div>
          <div className="muted" style={{ fontSize: 10 }}>
            повтор каждые {settings.autoSellRetrySec} с · позиции ботов не
            трогает
          </div>
        </div>
        <label className="mini narrow">
          <span>по, ¢</span>
          <input
            type="number"
            inputMode="numeric"
            value={String(Math.round(settings.autoSellPrice * 100))}
            onChange={(e) =>
              push({
                ...settings,
                autoSellPrice: Number(e.target.value.replace(',', '.')) / 100,
              })
            }
          />
        </label>
      </div>

      {state.rows.length > 0 && (
        <div className="autosell-rows">
          <div className="muted" style={{ fontSize: 11, marginBottom: 4 }}>
            покрыто {covered} из {state.rows.length}
          </div>
          {state.rows.map((r) => (
            <div className="ledger" key={r.asset}>
              <span className={r.outcome === 'Up' ? 'up' : 'down'}>{r.outcome}</span>
              <span className="ledger-main">
                {r.size.toFixed(1)} · продаётся {r.resting.toFixed(1)}
              </span>
              <span
                className={`ledger-note ${
                  r.status === 'покрыто'
                    ? 'up'
                    : r.status === 'выставлено' || r.status === 'у бота'
                      ? 'muted'
                      : 'warn'
                }`}
              >
                {r.status}
                {r.attempts > 0 && r.status !== 'покрыто' ? ` · ${r.attempts}` : ''}
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
}: {
  settings: ManualSettings;
  onChange: (next: ManualSettings) => void;
}) {
  const setRule = (index: number, patch: Partial<{ maxPrice: number; shares: number }>) => {
    const rules = settings.sizeRules.map((r, i) => (i === index ? { ...r, ...patch } : r));
    onChange({ ...settings, sizeRules: rules });
  };

  return (
    <div className="card">
      <h2>Покупка по клику</h2>

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

      <label className="field" style={{ marginTop: 12 }}>
        <span>Повтор автопродажи, сек</span>
        <input
          type="number"
          value={String(settings.autoSellRetrySec)}
          onChange={(e) =>
            onChange({
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
    </div>
  );
}
