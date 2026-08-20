import { useEffect, useState } from 'react';
import type { BotEngine, Cycle, EngineSnapshot } from '../bot/engine';
import type { StrategySettings } from '../bot/strategy';
import { WINDOW_SECONDS } from '../core/config';

export function Dashboard({
  engine,
  settings,
}: {
  engine: BotEngine;
  settings: StrategySettings;
}) {
  const [snap, setSnap] = useState<EngineSnapshot>(() => engine.snapshot());
  const [now, setNow] = useState(Date.now());

  useEffect(() => engine.subscribe(setSnap), [engine]);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);

  const cycle = snap.current;
  const price = snap.lastTick?.value ?? null;
  const strike = cycle?.strike ?? null;
  const drift = price !== null && strike !== null ? price - strike : null;

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
            {strike !== null ? `$${strike.toFixed(2)}` : 'ждём тик…'}
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

        <div className="bar">
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
          <span className="value">{stateLabel(cycle)}</span>
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
        {cycle?.note && (
          <div className="row">
            <span className="label">Комментарий</span>
            <span className="value muted" style={{ fontSize: 12 }}>
              {cycle.note}
            </span>
          </div>
        )}
      </div>

      <div className="grid2" style={{ marginBottom: 12 }}>
        <div className="stat">
          <div className="k">Результат</div>
          <div className={`v ${snap.stats.realisedPnlUsd >= 0 ? 'up' : 'down'}`}>
            {snap.stats.realisedPnlUsd >= 0 ? '+' : ''}
            {snap.stats.realisedPnlUsd.toFixed(2)} $
          </div>
        </div>
        <div className="stat">
          <div className="k">Сделок</div>
          <div className="v">{snap.stats.trades}</div>
        </div>
        <div className="stat">
          <div className="k">Побед / поражений</div>
          <div className="v">
            {snap.stats.wins} / {snap.stats.losses}
          </div>
        </div>
        <div className="stat">
          <div className="k">Оборот</div>
          <div className="v">{snap.stats.stakedUsd.toFixed(2)} $</div>
        </div>
      </div>

      {snap.running ? (
        <button className="danger" onClick={() => engine.stop()}>
          Остановить бота
        </button>
      ) : (
        <button className="primary" onClick={() => engine.start()}>
          Запустить бота
        </button>
      )}

      {snap.history.length > 0 && (
        <div className="card" style={{ marginTop: 12 }}>
          <h2>История окон</h2>
          {snap.history.slice(0, 20).map((c) => (
            <div className="row" key={c.windowStart}>
              <span className="label">{windowLabel(c)}</span>
              <span className="value">
                {c.entry ? (
                  <>
                    <span className={c.entry.side === 'Up' ? 'up' : 'down'}>
                      {c.entry.side}
                    </span>{' '}
                    {c.pnlUsd === null ? (
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

function windowLabel(c: Cycle): string {
  const start = new Date(c.windowStart * 1000);
  const end = new Date(c.windowEnd * 1000);
  const fmt = (d: Date) =>
    d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `${fmt(start)}–${fmt(end)}`;
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

function stateLabel(cycle: Cycle | null): string {
  if (!cycle) return 'бот не запущен';
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
  }
}
