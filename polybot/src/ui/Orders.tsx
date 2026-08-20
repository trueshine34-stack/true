import { useCallback, useEffect, useState } from 'react';
import {
  PolyBot,
  type NativeMarket,
  type OpenOrder,
  type PlaceOrderArgs,
} from '../native/polybot';

type Draft = { price: string; size: string };

/** A sell handed over from tapping a position on the terminal. */
export type SellPrefill = {
  tokenId: string;
  conditionId: string;
  outcome: 'Up' | 'Down';
  size: number;
  price: number;
};

/**
 * Manual order desk.
 *
 * The exchange has no amend — price and size are inside the signature — so
 * editing a resting order is a cancel followed by a fresh one. That is done
 * natively in a single call so a cancel can never succeed while the
 * replacement silently fails to go out.
 */
export function Orders({
  prefill,
  onPrefillUsed,
}: {
  prefill?: SellPrefill | null;
  onPrefillUsed?: () => void;
}) {
  const [market, setMarket] = useState<NativeMarket | null>(null);
  const [orders, setOrders] = useState<OpenOrder[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [editing, setEditing] = useState<Record<string, Draft>>({});

  // New-order form
  const [outcome, setOutcome] = useState<'Up' | 'Down'>('Up');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [orderType, setOrderType] = useState<'GTC' | 'FOK'>('GTC');
  const [price, setPrice] = useState('50');
  const [size, setSize] = useState('5');
  const [placing, setPlacing] = useState(false);
  // A tapped position targets its own token, which may belong to a window that
  // has already rolled over — so carry the ids rather than re-deriving them.
  const [override, setOverride] = useState<SellPrefill | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const m = await PolyBot.getCurrentMarket().catch(() => null);
      if (m) setMarket(m);
      const r = await PolyBot.getOpenOrders();
      setOrders(r.orders);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const poll = setInterval(() => void refresh(), 10_000);
    return () => clearInterval(poll);
  }, [refresh]);

  useEffect(() => {
    if (!prefill) return;
    setOverride(prefill);
    setOutcome(prefill.outcome);
    setSide('SELL');
    setOrderType('GTC');
    setSize(prefill.size.toFixed(2));
    setPrice((prefill.price * 100).toFixed(0));
    setNotice(null);
    setError(null);
    onPrefillUsed?.();
  }, [prefill, onPrefillUsed]);

  const run = async (id: string, fn: () => Promise<string>) => {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      setNotice(await fn());
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusyId(null);
    }
  };

  const cancel = (order: OpenOrder) =>
    run(order.id, async () => {
      const r = await PolyBot.cancelOrder({ orderId: order.id });
      return r.cancelled ? 'Ордер отменён' : 'Ордер уже был неактивен';
    });

  const saveEdit = (order: OpenOrder) => {
    const draft = editing[order.id];
    if (!draft) return;
    const newPrice = Number(draft.price.replace(',', '.')) / 100;
    const newSize = Number(draft.size.replace(',', '.'));
    if (!Number.isFinite(newPrice) || newPrice <= 0 || newPrice >= 1) {
      setError('Цена должна быть от 1 до 99 центов.');
      return;
    }
    if (!Number.isFinite(newSize) || newSize <= 0) {
      setError('Размер должен быть больше нуля.');
      return;
    }
    return run(order.id, async () => {
      const r = await PolyBot.replaceOrder({
        orderId: order.id,
        tokenId: order.assetId,
        conditionId: order.market,
        side: order.side,
        price: newPrice,
        size: newSize,
        orderType: 'GTC',
      });
      if (!r.success) throw new Error(r.error ?? 'CLOB отклонил новый ордер');
      setEditing((prev) => {
        const next = { ...prev };
        delete next[order.id];
        return next;
      });
      return 'Ордер изменён';
    });
  };

  const cancelAll = () => {
    if (!market) return;
    return run('all', async () => {
      const r = await PolyBot.cancelMarketOrders({ conditionId: market.conditionId });
      return `Отменено ордеров: ${r.cancelled}`;
    });
  };

  const place = async () => {
    const target = override ?? (market
      ? {
          tokenId: outcome === 'Up' ? market.upTokenId : market.downTokenId,
          conditionId: market.conditionId,
        }
      : null);
    if (!target) {
      setError('Рынок текущего окна ещё не загружен.');
      return;
    }
    const priceValue = Number(price.replace(',', '.')) / 100;
    const sizeValue = Number(size.replace(',', '.'));
    if (!Number.isFinite(priceValue) || priceValue <= 0 || priceValue >= 1) {
      setError('Цена должна быть от 1 до 99 центов.');
      return;
    }
    if (!Number.isFinite(sizeValue) || sizeValue <= 0) {
      setError('Размер должен быть больше нуля.');
      return;
    }

    const args: PlaceOrderArgs = {
      tokenId: target.tokenId,
      conditionId: target.conditionId,
      side,
      price: priceValue,
      size: sizeValue,
      orderType,
    };

    setPlacing(true);
    setError(null);
    setNotice(null);
    try {
      const r = await PolyBot.placeOrder(args);
      if (!r.success) throw new Error(r.error ?? 'CLOB отклонил ордер');
      setNotice(`Ордер отправлен${r.status ? ` · ${r.status}` : ''}`);
      setOverride(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPlacing(false);
    }
  };

  // Buys lock USDC; sells lock shares. Summing them into one number would
  // mean adding dollars to shares, so they stay side by side.
  const live = orders.filter((o) => o.remaining > 0);
  const buys = live.filter((o) => o.side === 'BUY');
  const sells = live.filter((o) => o.side === 'SELL');
  const buyExposure = buys.reduce((sum, o) => sum + o.remaining * o.price, 0);
  const sellShares = sells.reduce((sum, o) => sum + o.remaining, 0);
  const sellExposure = sells.reduce((sum, o) => sum + o.remaining * o.price, 0);
  const buyOrders = buys.length;
  const sellOrders = sells.length;

  const sizeHint =
    orderType === 'GTC'
      ? `в долях${market ? `, минимум ${market.minimumOrderSize}` : ''}`
      : side === 'BUY'
        ? 'в долларах'
        : 'в долях';

  return (
    <>
      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner info">{notice}</div>}

      <div className="card">
        <h2>В активных ордерах</h2>
        <div className="grid2">
          <div className="stat">
            <div className="k">Покупки</div>
            <div className="v">{buyExposure.toFixed(2)} $</div>
            <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
              {buyOrders} шт · зарезервировано
            </div>
          </div>
          <div className="stat">
            <div className="k">Продажи</div>
            <div className="v">{sellShares.toFixed(2)}</div>
            <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
              {sellOrders} шт · на {sellExposure.toFixed(2)} $
            </div>
          </div>
        </div>
      </div>

      <div className="card">
        <h2>Открытые ордера</h2>

        {loading && orders.length === 0 && <div className="muted">Загрузка…</div>}
        {!loading && orders.length === 0 && (
          <div className="muted">Активных ордеров нет.</div>
        )}

        {orders.map((o) => {
          const draft = editing[o.id];
          return (
            <div
              key={o.id}
              style={{ padding: '10px 0', borderTop: '1px solid var(--line)' }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  gap: 10,
                }}
              >
                <span className={o.side === 'BUY' ? 'up' : 'down'}>
                  {o.side === 'BUY' ? 'Покупка' : 'Продажа'}
                  {o.outcome ? ` · ${o.outcome}` : ''}
                </span>
                <span className="value">
                  {(o.price * 100).toFixed(0)}¢ · {o.remaining.toFixed(2)} из{' '}
                  {o.originalSize.toFixed(2)}
                </span>
              </div>

              {o.sizeMatched > 0 && (
                <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
                  исполнено {o.sizeMatched.toFixed(2)} долей
                </div>
              )}

              {draft ? (
                <>
                  <div className="grid2" style={{ marginTop: 8 }}>
                    <label className="field" style={{ marginBottom: 0 }}>
                      <span>Цена, ¢</span>
                      <input
                        inputMode="decimal"
                        value={draft.price}
                        onChange={(e) =>
                          setEditing((p) => ({
                            ...p,
                            [o.id]: { ...draft, price: e.target.value },
                          }))
                        }
                      />
                    </label>
                    <label className="field" style={{ marginBottom: 0 }}>
                      <span>Размер, долей</span>
                      <input
                        inputMode="decimal"
                        value={draft.size}
                        onChange={(e) =>
                          setEditing((p) => ({
                            ...p,
                            [o.id]: { ...draft, size: e.target.value },
                          }))
                        }
                      />
                    </label>
                  </div>
                  <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                    <button
                      className="primary"
                      disabled={busyId === o.id}
                      onClick={() => void saveEdit(o)}
                    >
                      {busyId === o.id ? 'Сохраняем…' : 'Сохранить'}
                    </button>
                    <button
                      className="ghost"
                      onClick={() =>
                        setEditing((p) => {
                          const next = { ...p };
                          delete next[o.id];
                          return next;
                        })
                      }
                    >
                      Отмена
                    </button>
                  </div>
                </>
              ) : (
                <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                  <button
                    className="ghost"
                    onClick={() =>
                      setEditing((p) => ({
                        ...p,
                        [o.id]: {
                          price: (o.price * 100).toFixed(0),
                          size: o.remaining.toFixed(2),
                        },
                      }))
                    }
                  >
                    Изменить
                  </button>
                  <button
                    className="danger"
                    disabled={busyId === o.id}
                    onClick={() => void cancel(o)}
                  >
                    {busyId === o.id ? '…' : 'Снять'}
                  </button>
                </div>
              )}
            </div>
          );
        })}

        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <button className="ghost" disabled={loading} onClick={() => void refresh()}>
            Обновить
          </button>
          {orders.length > 0 && market && (
            <button
              className="danger"
              disabled={busyId === 'all'}
              onClick={() => void cancelAll()}
            >
              Снять все
            </button>
          )}
        </div>
      </div>

      <div className="card">
        <h2>Новый ордер</h2>

        <div className="muted" style={{ fontSize: 12, marginBottom: 10 }}>
          {market ? market.question : 'Рынок текущего окна загружается…'}
        </div>

        <div className="grid2">
          <label className="field">
            <span>Исход</span>
            <select
              value={outcome}
              onChange={(e) => {
                setOutcome(e.target.value as 'Up' | 'Down');
                setOverride(null);
              }}
            >
              <option value="Up">Up</option>
              <option value="Down">Down</option>
            </select>
          </label>
          <label className="field">
            <span>Сторона</span>
            <select
              value={side}
              onChange={(e) => setSide(e.target.value as 'BUY' | 'SELL')}
            >
              <option value="BUY">Купить</option>
              <option value="SELL">Продать</option>
            </select>
          </label>
        </div>

        <label className="field">
          <span>Тип</span>
          <select
            value={orderType}
            onChange={(e) => setOrderType(e.target.value as 'GTC' | 'FOK')}
          >
            <option value="GTC">Лимитный — стоит в стакане</option>
            <option value="FOK">По рынку — сразу или отмена</option>
          </select>
        </label>

        <div className="grid2">
          <label className="field">
            <span>Цена, ¢</span>
            <input
              inputMode="decimal"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
            />
          </label>
          <label className="field">
            <span>Размер, {sizeHint}</span>
            <input
              inputMode="decimal"
              value={size}
              onChange={(e) => setSize(e.target.value)}
            />
          </label>
        </div>

        <button className="primary" disabled={placing || !market} onClick={() => void place()}>
          {placing ? 'Отправляем…' : 'Выставить ордер'}
        </button>

        <div className="banner warn" style={{ marginTop: 12, marginBottom: 0 }}>
          Ручные ордера уходят на биржу на реальные деньги сразу — тестовый
          режим бота на них не распространяется.
        </div>
      </div>
    </>
  );
}
