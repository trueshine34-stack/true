import { useEffect, useState } from 'react';
import { WINDOW_SECONDS } from '../core/config';
import type { StrategySettings } from '../core/settings';
import {
  PolyBot,
  type NativeCycle,
  type NativePosition,
  type NativeQuote,
  type NativeState,
} from '../native/polybot';

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

  const cycle = snap.current;
  const price = snap.lastTick?.value ?? null;
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
              BTC/USD · Chainlink (источник расчёта Polymarket)
            </div>
            <div className="price">
              {price !== null ? `$${price.toFixed(2)}` : '—'}
            </div>
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
            {cycle?.fair ? `${(cycle.fair.pUp * 100).toFixed(1)}%` : '—'}
          </span>
        </div>

        <div className="grid2" style={{ marginTop: 12 }}>
          <OutcomeQuote label="Up" quote={snap.quotes?.up} up />
          <OutcomeQuote label="Down" quote={snap.quotes?.down} up={false} />
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
}: {
  label: string;
  quote?: NativeQuote | null;
  up: boolean;
}) {
  const cents = (v?: number | null) =>
    v === null || v === undefined ? '—' : `${(v * 100).toFixed(0)}¢`;
  return (
    <div className="stat">
      <div className="k">{label}</div>
      <div className={`v ${up ? 'up' : 'down'}`}>{cents(quote?.mid)}</div>
      <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
        бид {cents(quote?.bestBid)} · аск {cents(quote?.bestAsk)}
      </div>
    </div>
  );
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
