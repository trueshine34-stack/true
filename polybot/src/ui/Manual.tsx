import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  DEFAULT_MANUAL_SETTINGS,
  sharesFor,
  type ManualSettings,
} from '../core/manual';
import { findLevels } from '../core/levels';
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
  const [books, setBooks] = useState<Record<'Up' | 'Down', BookLevels>>({
    Up: { bids: [], asks: [] },
    Down: { bids: [], asks: [] },
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [positions, setPositions] = useState<NativePosition[]>([]);
  const [orders, setOrders] = useState<OpenOrder[]>([]);
  const [autoSell, setAutoSell] = useState<AutoSellState>(IDLE_AUTOSELL);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => Date.now());

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
            <div className="listhead">
              <span>Позиции</span>
              <span className="muted">нажать — продать</span>
            </div>
            {positions.length === 0 ? (
              <div className="muted empty">Открытых позиций нет</div>
            ) : (
              positions.map((p) => (
                <button
                  className="listrow"
                  key={p.asset}
                  onClick={() => sellPosition(p)}
                >
                  <span className={p.outcome === 'Up' ? 'up tag-side' : 'down tag-side'}>
                    {p.outcome}
                  </span>
                  <span className="listrow-main">
                    {p.size.toFixed(1)} × {cents(p.avgPrice)}
                  </span>
                  <span className="listrow-now">
                    {p.curPrice != null ? cents(p.curPrice) : '—'}
                  </span>
                  <span className={`listrow-pnl ${p.cashPnl >= 0 ? 'up' : 'down'}`}>
                    {p.cashPnl >= 0 ? '+' : '−'}
                    {Math.abs(p.cashPnl).toFixed(2)}
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
function Chart({ candles, spot }: { candles: GmxCandle[]; spot: number | null }) {
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
    const openPrice = openIndex >= 0 ? candles[openIndex].open : null;

    return { W, H, lo, hi, step, y, openIndex, openPrice };
  }, [candles, spot]);

  if (!view) {
    return <div className="chart-empty muted">График загружается…</div>;
  }
  const { W, H, lo, hi, step, y, openIndex, openPrice } = view;
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
        readable than one, and the lines themselves are still both drawn.
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
      {openPrice != null && inView(openPrice) && (
        <span className="chart-tag open" style={{ top: `${(y(openPrice) / H) * 100}%` }}>
          окно {openPrice.toFixed(0)}
        </span>
      )}
    </div>
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
