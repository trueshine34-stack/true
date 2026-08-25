import { useEffect, useState } from 'react';
import { WINDOW_SECONDS } from '../core/config';
import type { StrategySettings } from '../core/settings';
import {
  PolyBot,
  type NativeCycle,
  type NativeMarket,
  type NativePosition,
  type NativeQuote,
  type NativeState,
} from '../native/polybot';

/** A limit buy being composed on the terminal, before it is sent. */
type BuyDraft = {
  outcome: 'Up' | 'Down';
  price: string;
  amount: string;
};

const IDLE: NativeState = {
  serviceAlive: false,
  running: false,
  feedStatus: 'closed',
  clockOffsetSec: 0,
};

export function Dashboard({
  settings,
  onSellPosition,
}: {
  settings: StrategySettings;
  onSellPosition: (position: NativePosition) => void;
}) {
  const [snap, setSnap] = useState<NativeState>(IDLE);
  const [now, setNow] = useState(Date.now());
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [draft, setDraft] = useState<BuyDraft | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [placing, setPlacing] = useState(false);
  const [loadedMarket, setLoadedMarket] = useState<NativeMarket | null>(null);

  useEffect(() => {
    let handle: { remove: () => Promise<void> } | null = null;
    let cancelled = false;

    void PolyBot.getState().then((s) => {
      if (!cancelled) setSnap(s);
    });
    void PolyBot.addListener('state', (s) => setSnap(s)).then((h) => {
      if (cancelled) void h.remove();
      else handle = h;
    });

    // The service pushes on every state change, but a poll covers the gap when
    // the WebView was suspended and missed the events entirely.
    const poll = setInterval(() => {
      void PolyBot.getState().then((s) => setSnap(s));
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(poll);
      void handle?.remove();
    };
  }, []);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);

  // The running cycle carries its market, but manual orders must work with the
  // bot stopped too, so keep an independently loaded copy.
  useEffect(() => {
    let cancelled = false;
    const load = () =>
      PolyBot.getCurrentMarket()
        .then((m) => {
          if (!cancelled) setLoadedMarket(m);
        })
        .catch(() => {});
    void load();
    const poll = setInterval(load, 60_000);
    return () => {
      cancelled = true;
      clearInterval(poll);
    };
  }, []);

  const cycle = snap.current;
  /**
   * The headline is the thirty-second TWAP, because that is the number
   * Polymarket puts on screen and the one the window settles against. The raw
   * Chainlink tick is what the model works from and sits underneath it — they
   * differ by the smoothing, and showing only the raw one made the app disagree
   * with the site by tens of dollars.
   */
  const price = snap.twapTick?.value ?? snap.lastTick?.value ?? null;
  const raw = snap.twapTick ? (snap.lastTick?.value ?? null) : null;
  const strike = cycle?.strike ?? null;
  const drift =
    price !== null && strike !== null && strike !== undefined ? price - strike : null;

  const windowStartMs = cycle ? cycle.windowStart * 1000 : null;
  const windowEndMs = cycle ? cycle.windowEnd * 1000 : null;
  const elapsed =
    windowStartMs !== null ? Math.max(0, now - windowStartMs) / 1000 : 0;
  const remaining =
    windowEndMs !== null ? Math.max(0, (windowEndMs - now) / 1000) : 0;
  const progress = Math.min(100, (elapsed / WINDOW_SECONDS) * 100);

  const entryIn = cycle
    ? cycle.windowStart * 1000 + settings.entryDelaySec * 1000 - now
    : null;

  const market: NativeMarket | undefined = cycle?.market ?? loadedMarket ?? undefined;

  /**
   * Tapping a price opens a limit buy on it, sized to the whole balance.
   *
   * The displayed number is the middle of the book, which can sit between
   * ticks, so it is snapped to the market's grid before it becomes a price the
   * exchange would accept.
   */
  const openDraft = (outcome: 'Up' | 'Down', quote?: NativeQuote | null) => {
    if (!market) {
      setError('Рынок текущего окна ещё не загружен.');
      return;
    }
    const tick = market.tickSize || 0.01;
    const raw = quote?.mid ?? quote?.bestAsk ?? quote?.bestBid;
    if (raw == null) {
      setError('Котировка ещё не загружена.');
      return;
    }
    const snapped = Math.round(raw / tick) * tick;
    setError(null);
    setNotice(null);
    setDraft({
      outcome,
      price: (snapped * 100).toFixed(0),
      amount: '',
    });
    // The whole balance is the default size, so fetch it before the user types.
    void PolyBot.getBalance()
      .then((r) => {
        setBalance(r.usdc);
        setDraft((d) => (d && d.amount === '' ? { ...d, amount: r.usdc.toFixed(2) } : d));
      })
      .catch(() => setBalance(null));
  };

  const snapPrice = (value?: number | null) => {
    if (value == null || !draft) return;
    setDraft({ ...draft, price: (value * 100).toFixed(0) });
  };

  const placeDraft = async () => {
    if (!draft || !market) return;
    const price = Number(draft.price.replace(',', '.')) / 100;
    const amount = Number(draft.amount.replace(',', '.'));

    if (!Number.isFinite(price) || price <= 0 || price >= 1) {
      setError('Цена должна быть от 1 до 99 центов.');
      return;
    }
    if (!Number.isFinite(amount) || amount <= 0) {
      setError('Сумма должна быть больше нуля.');
      return;
    }
    const shares = amount / price;
    if (shares < market.minimumOrderSize) {
      setError(
        `${amount.toFixed(2)} $ по ${(price * 100).toFixed(0)}¢ даёт ` +
          `${shares.toFixed(2)} долей — минимум ${market.minimumOrderSize}.`,
      );
      return;
    }

    setPlacing(true);
    setError(null);
    setNotice(null);
    try {
      // A limit order is sized in shares, not dollars.
      const r = await PolyBot.placeOrder({
        tokenId: draft.outcome === 'Up' ? market.upTokenId : market.downTokenId,
        conditionId: market.conditionId,
        side: 'BUY',
        price,
        size: shares,
        orderType: 'GTC',
      });
      if (!r.success) throw new Error(r.error ?? 'CLOB отклонил ордер');
      setNotice(
        `Лимитка ${draft.outcome} · ${shares.toFixed(2)} долей по ` +
          `${(price * 100).toFixed(0)}¢${r.status ? ` · ${r.status}` : ''}`,
      );
      setDraft(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPlacing(false);
    }
  };

  const toggle = async () => {
    setError(null);
    try {
      if (snap.running) await PolyBot.stop();
      else await PolyBot.start();
      setSnap(await PolyBot.getState());
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <>
      {settings.dryRun && (
        <div className="banner warn">
          Тестовый режим: ордера не отправляются. Включите реальную торговлю в
          настройках.
        </div>
      )}
      {snap.haltReason && (
        <div className="banner error">Бот остановлен: {snap.haltReason}</div>
      )}
      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner info">{notice}</div>}

      <div className="card">
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
          }}
        >
          <div>
            <div className="muted" style={{ fontSize: 12 }}>
              BTC/USD · Polymarket TWAP 30с (цена расчёта)
            </div>
            <div className="price">
              {price !== null ? `$${price.toFixed(2)}` : '—'}
            </div>
            {raw !== null && (
              <div className="muted" style={{ fontSize: 12 }}>
                Тик Chainlink: ${raw.toFixed(2)}
                {price !== null &&
                  ` · сглаживание ${raw - price >= 0 ? '+' : ''}${(raw - price).toFixed(2)} $`}
              </div>
            )}
          </div>
          <span className="pill">
            <i className={`dot ${snap.feedStatus}`} />
            {feedLabel(snap.feedStatus)}
          </span>
        </div>

        <div className="row">
          <span className="label">Страйк окна</span>
          <span className="value">
            {strike != null ? `$${strike.toFixed(2)}` : 'ждём тик…'}
          </span>
        </div>
        <div className="row">
          <span className="label">Отклонение</span>
          <span className={`value ${drift === null ? '' : drift >= 0 ? 'up' : 'down'}`}>
            {drift === null
              ? '—'
              : `${drift >= 0 ? '+' : ''}${drift.toFixed(2)} $`}
          </span>
        </div>
        <div className="row">
          <span className="label">Модель: вероятность Up</span>
          <span className="value">
            {cycle?.fair ? (
              <>
                {(cycle.fair.pUp * 100).toFixed(1)}%
                {cycle.fair.rawPUp !== undefined &&
                  Math.abs(cycle.fair.rawPUp - cycle.fair.pUp) > 0.005 && (
                    <span className="muted" style={{ fontSize: 12 }}>
                      {' '}
                      (сырая {(cycle.fair.rawPUp * 100).toFixed(1)}%)
                    </span>
                  )}
              </>
            ) : (
              '—'
            )}
          </span>
        </div>

        <div className="grid2" style={{ marginTop: 12 }}>
          <OutcomeQuote
            label="Up"
            quote={snap.quotes?.up}
            up
            onPick={() => openDraft('Up', snap.quotes?.up)}
          />
          <OutcomeQuote
            label="Down"
            quote={snap.quotes?.down}
            up={false}
            onPick={() => openDraft('Down', snap.quotes?.down)}
          />
        </div>
        <div className="muted" style={{ fontSize: 11, marginTop: 6 }}>
          Нажмите на цену, чтобы выставить лимитку на покупку.
        </div>

        <div className="bar" style={{ marginTop: 12 }}>
          <i style={{ width: `${progress}%` }} />
        </div>
        <div
          className="muted"
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            fontSize: 12,
            marginTop: 6,
          }}
        >
          <span>{cycle ? windowLabel(cycle) : '—'}</span>
          <span>до закрытия {formatClock(remaining)}</span>
        </div>
      </div>

      {draft && market && (
        <div className="card">
          <h2>
            Лимитка на покупку ·{' '}
            <span className={draft.outcome === 'Up' ? 'up' : 'down'}>
              {draft.outcome}
            </span>
          </h2>

          <div className="grid2">
            <label className="field">
              <span>Цена, ¢</span>
              <input
                inputMode="decimal"
                value={draft.price}
                onChange={(e) => setDraft({ ...draft, price: e.target.value })}
              />
            </label>
            <label className="field">
              <span>Сумма, $</span>
              <input
                inputMode="decimal"
                value={draft.amount}
                onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
              />
            </label>
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
            <button
              className="ghost"
              onClick={() =>
                snapPrice(
                  draft.outcome === 'Up'
                    ? snap.quotes?.up?.bestBid
                    : snap.quotes?.down?.bestBid,
                )
              }
            >
              бид
            </button>
            <button
              className="ghost"
              onClick={() =>
                snapPrice(
                  draft.outcome === 'Up'
                    ? snap.quotes?.up?.bestAsk
                    : snap.quotes?.down?.bestAsk,
                )
              }
            >
              аск
            </button>
            {balance !== null && (
              <button
                className="ghost"
                onClick={() => setDraft({ ...draft, amount: balance.toFixed(2) })}
              >
                вся сумма
              </button>
            )}
          </div>

          <DraftSummary draft={draft} market={market} balance={balance} />

          <button
            className="primary"
            style={{ marginTop: 10 }}
            disabled={placing}
            onClick={() => void placeDraft()}
          >
            {placing ? 'Отправляем…' : 'Выставить лимитку'}
          </button>
          <button
            className="ghost"
            style={{ marginTop: 10 }}
            onClick={() => setDraft(null)}
          >
            Отмена
          </button>

          <div className="banner warn" style={{ marginTop: 12, marginBottom: 0 }}>
            Ручной ордер уходит на биржу на реальные деньги — тестовый режим бота
            на него не распространяется.
          </div>
        </div>
      )}

      <div className="card">
        <h2>Текущее окно</h2>
        <div className="row">
          <span className="label">Статус</span>
          <span className="value">{stateLabel(snap, cycle)}</span>
        </div>
        {cycle?.state === 'armed' && entryIn !== null && entryIn > 0 && (
          <div className="row">
            <span className="label">Решение через</span>
            <span className="value">{formatClock(entryIn / 1000)}</span>
          </div>
        )}
        {cycle?.entry && (
          <>
            <div className="row">
              <span className="label">Позиция</span>
              <span className={`value ${cycle.entry.side === 'Up' ? 'up' : 'down'}`}>
                {cycle.entry.side} · {cycle.entry.shares.toFixed(2)} долей
              </span>
            </div>
            <div className="row">
              <span className="label">Цена входа / вложено</span>
              <span className="value">
                {(cycle.entry.price * 100).toFixed(0)}¢ ·{' '}
                {cycle.entry.costUsd.toFixed(2)} $
              </span>
            </div>
          </>
        )}
        {(cycle?.exits?.length ?? 0) > 0 && (
          <div className="row">
            <span className="label">
              Продажа
            </span>
            <span className="value">
              {cycle!.exits!
                .filter((e) => !e.cancelled)
                .map(
                  (e) =>
                    `${(e.price * 100).toFixed(0)}¢ · ${(e.size - e.matched).toFixed(2)} долей`,
                )
                .join(', ') || 'снята'}
            </span>
          </div>
        )}
        {(cycle?.soldAtMarket ?? 0) > 0 && (
          <div className="row">
            <span className="label">Продано по рынку</span>
            <span className="value up">
              {cycle!.soldAtMarket!.toFixed(2)} долей ·{' '}
              {(cycle!.marketProceedsUsd ?? 0).toFixed(2)} $
            </span>
          </div>
        )}
        {(cycle?.averageDownCount ?? 0) > 0 && (
          <div className="row">
            <span className="label">Докупок</span>
            <span className="value">{cycle!.averageDownCount}</span>
          </div>
        )}
        {cycle?.note && (
          <div className="row">
            <span className="label">Комментарий</span>
            <span className="value muted" style={{ fontSize: 12 }}>
              {cycle.note}
            </span>
          </div>
        )}
      </div>

      <div className="card">
        <h2>Позиции</h2>
        {(snap.positions?.length ?? 0) === 0 ? (
          <div className="muted">Открытых позиций нет.</div>
        ) : (
          snap.positions!.map((p) => (
            <button
              key={p.asset}
              className="position"
              onClick={() => onSellPosition(p)}
            >
              <div className="position-main">
                <span className={p.outcome === 'Up' ? 'up' : 'down'}>{p.outcome}</span>
                <span className="value">
                  {p.size.toFixed(2)} долей · средняя {(p.avgPrice * 100).toFixed(0)}¢
                </span>
              </div>
              <div className="position-sub muted">
                <span>{p.title}</span>
                <span className={p.cashPnl >= 0 ? 'up' : 'down'}>
                  {p.cashPnl >= 0 ? '+' : ''}
                  {p.cashPnl.toFixed(2)} $ · сейчас {(p.curPrice * 100).toFixed(0)}¢
                </span>
              </div>
            </button>
          ))
        )}
        {(snap.positions?.length ?? 0) > 0 && (
          <p className="muted" style={{ fontSize: 12, margin: '8px 0 0' }}>
            Нажмите на позицию, чтобы открыть продажу с её размером.
          </p>
        )}
      </div>

      <div className="grid2" style={{ marginBottom: 12 }}>
        <div className="stat">
          <div className="k">Результат за день</div>
          <div className={`v ${(snap.stats?.realisedPnlUsd ?? 0) >= 0 ? 'up' : 'down'}`}>
            {(snap.stats?.realisedPnlUsd ?? 0) >= 0 ? '+' : ''}
            {(snap.stats?.realisedPnlUsd ?? 0).toFixed(2)} $
          </div>
        </div>
        <div className="stat">
          <div className="k">Сделок</div>
          <div className="v">{snap.stats?.trades ?? 0}</div>
        </div>
        <div className="stat">
          <div className="k">Побед / поражений</div>
          <div className="v">
            {snap.stats?.wins ?? 0} / {snap.stats?.losses ?? 0}
          </div>
        </div>
        <div className="stat">
          <div className="k">Оборот</div>
          <div className="v">{(snap.stats?.stakedUsd ?? 0).toFixed(2)} $</div>
        </div>
      </div>

      {snap.calibration && (
        <div className="card">
          <h2>Калибровка модели</h2>
          <div className="row">
            <span className="label">Доверие к прогнозу</span>
            <span className="value">
              {(snap.calibration.shrinkage * 100).toFixed(0)}%
            </span>
          </div>
          <div className="row">
            <span className="label">Оценённых окон</span>
            <span className="value">{snap.calibration.samples}</span>
          </div>
          <div className="row">
            <span className="label">Brier</span>
            <span className="value">
              {snap.calibration.brier == null
                ? '—'
                : snap.calibration.brier.toFixed(4)}
            </span>
          </div>
          <p className="muted" style={{ fontSize: 12, margin: '8px 0 0' }}>
            Каждое закрытое окно оценивает прогноз, даже если сделки не было.
            Доверие — какая доля отклонения модели от 50/50 идёт в решение: оно
            падает, если модель самоуверенна. Brier 0,25 — это монетка, меньше
            лучше.
          </p>
        </div>
      )}

      <button className={snap.running ? 'danger' : 'primary'} onClick={() => void toggle()}>
        {snap.running ? 'Остановить бота' : 'Запустить бота'}
      </button>

      <p className="muted" style={{ fontSize: 12, marginTop: 10 }}>
        Бот работает в фоновом сервисе — можно свернуть приложение и погасить
        экран. Пока он запущен, в шторке висит уведомление. Статистика
        накапливается за календарный день{snap.statsDay ? ` (${snap.statsDay})` : ''} и
        остановкой бота не сбрасывается.
      </p>

      <button
        className="ghost"
        style={{ marginTop: 10 }}
        onClick={() => {
          void PolyBot.resetStats().then(() => PolyBot.getState()).then(setSnap);
        }}
      >
        Обнулить статистику дня
      </button>

      {(snap.history?.length ?? 0) > 0 && (
        <div className="card" style={{ marginTop: 12 }}>
          <h2>История окон</h2>
          {snap.history!.slice(0, 20).map((c) => (
            <div className="row" key={c.windowStart}>
              <span className="label">{windowLabel(c)}</span>
              <span className="value">
                {c.entry ? (
                  <>
                    <span className={c.entry.side === 'Up' ? 'up' : 'down'}>
                      {c.entry.side}
                    </span>{' '}
                    {c.pnlUsd == null ? (
                      <span className="muted">ждём расчёт</span>
                    ) : (
                      <span className={c.pnlUsd >= 0 ? 'up' : 'down'}>
                        {c.pnlUsd >= 0 ? '+' : ''}
                        {c.pnlUsd.toFixed(2)} $
                      </span>
                    )}
                  </>
                ) : (
                  <span className="muted" style={{ fontSize: 12 }}>
                    {c.note ?? 'без входа'}
                  </span>
                )}
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

function OutcomeQuote({
  label,
  quote,
  up,
  onPick,
}: {
  label: string;
  quote?: NativeQuote | null;
  up: boolean;
  onPick: () => void;
}) {
  return (
    <button className="stat quote" onClick={onPick}>
      <div className="k">{label}</div>
      <div className={`v ${up ? 'up' : 'down'}`}>{cents(quote?.mid)}</div>
      <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
        бид {cents(quote?.bestBid)} · аск {cents(quote?.bestAsk)}
      </div>
    </button>
  );
}

/** Turns the two edited fields into the numbers the exchange will see. */
function DraftSummary({
  draft,
  market,
  balance,
}: {
  draft: BuyDraft;
  market: NativeMarket;
  balance: number | null;
}) {
  const price = Number(draft.price.replace(',', '.')) / 100;
  const amount = Number(draft.amount.replace(',', '.'));
  const valid =
    Number.isFinite(price) && price > 0 && price < 1 &&
    Number.isFinite(amount) && amount > 0;
  const shares = valid ? amount / price : 0;
  const belowMinimum = valid && shares < market.minimumOrderSize;
  const overBalance = valid && balance !== null && amount > balance + 1e-9;

  return (
    <>
      <div className="row">
        <span className="label">Получится долей</span>
        <span className={`value ${belowMinimum ? 'down' : ''}`}>
          {valid ? shares.toFixed(2) : '—'}
          {belowMinimum && ` · минимум ${market.minimumOrderSize}`}
        </span>
      </div>
      <div className="row">
        <span className="label">Доступно USDC</span>
        <span className={`value ${overBalance ? 'down' : ''}`}>
          {balance === null ? '—' : `${balance.toFixed(2)} $`}
        </span>
      </div>
      <div className="row">
        <span className="label">Выигрыш, если сыграет</span>
        <span className="value up">{valid ? `${shares.toFixed(2)} $` : '—'}</span>
      </div>
    </>
  );
}

function cents(v?: number | null): string {
  return v === null || v === undefined ? '—' : `${(v * 100).toFixed(0)}¢`;
}

function windowLabel(c: NativeCycle): string {
  const fmt = (d: Date) =>
    d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `${fmt(new Date(c.windowStart * 1000))}–${fmt(new Date(c.windowEnd * 1000))}`;
}

function formatClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

function feedLabel(status: string): string {
  switch (status) {
    case 'live':
      return 'фид активен';
    case 'connecting':
      return 'подключение';
    case 'stalled':
      return 'нет данных';
    default:
      return 'отключён';
  }
}

function stateLabel(snap: NativeState, cycle?: NativeCycle): string {
  if (!snap.running) return 'бот не запущен';
  if (!cycle) return 'ожидание окна';
  switch (cycle.state) {
    case 'waiting':
      return 'ищем рынок';
    case 'armed':
      return 'ждём момент входа';
    case 'entered':
      return 'позиция открыта';
    case 'skipped':
      return 'пропущено';
    case 'settled':
      return 'рассчитано';
    case 'failed':
      return 'ошибка';
    default:
      return cycle.state;
  }
}
