import { useCallback, useEffect, useState } from 'react';
import { DEFAULT_PAIR_SETTINGS } from '../core/pair';
import { loadPairSettings, savePairSettings } from '../core/storage';
import {
  PolyBot,
  type PairFill,
  type PairOrder,
  type PairSettings,
  type PairState,
} from '../native/polybot';

const IDLE: PairState = {
  running: false,
  dryRun: true,
  orders: [],
  fills: [],
  windows: [],
  stats: { windows: 0, buys: 0, sells: 0, pairsLocked: 0, feesUsd: 0, realisedPnlUsd: 0 },
};

const cents = (p: number) => `${Math.round(p * 100)}¢`;
const usd = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)} $`;
const clock = (ms: number) =>
  new Date(ms).toLocaleTimeString('ru-RU', { hour12: false });

/**
 * The pair desk.
 *
 * One Up plus one Down settles to exactly $1 no matter which way the window
 * goes, so this bot does not predict anything — it assembles matched pairs for
 * less than a dollar and recycles whichever leg has run into the one that has
 * not. What the screen has to answer at a glance is therefore: how many pairs
 * are locked, what they cost, and how much stock is still unmatched.
 */
export function Pair() {
  const [state, setState] = useState<PairState>(IDLE);
  const [settings, setSettings] = useState<PairSettings>(DEFAULT_PAIR_SETTINGS);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<'live' | 'settings'>('live');

  useEffect(() => {
    void loadPairSettings().then(setSettings);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const read = () => {
      void PolyBot.pairGetState()
        .then((s) => {
          if (!cancelled) setState(s);
        })
        .catch(() => {
          // Service not up yet; the next poll picks it up.
        });
    };
    read();
    const timer = window.setInterval(read, 1500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const apply = useCallback((next: PairSettings) => {
    setSettings(next);
    void savePairSettings(next);
    void PolyBot.pairUpdateSettings({ settings: next }).catch(() => {});
  }, []);

  const toggle = useCallback(async () => {
    setError(null);
    try {
      if (state.running) {
        await PolyBot.pairStop();
      } else {
        await PolyBot.pairStart({ settings });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [state.running, settings]);

  const book = state.book;
  const upQ = state.quotes?.up;
  const downQ = state.quotes?.down;

  return (
    <>
      {error && <div className="banner error">{error}</div>}

      {settings.dryRun && (
        <div className="banner info">
          Тестовый режим: ордера не уходят на биржу, но исполняются по реальному
          стакану и с реальными комиссиями.
        </div>
      )}

      <div className="card">
        <div className="segmented">
          <button
            className={tab === 'live' ? 'active' : ''}
            onClick={() => setTab('live')}
          >
            Книга
          </button>
          <button
            className={tab === 'settings' ? 'active' : ''}
            onClick={() => setTab('settings')}
          >
            Правила
          </button>
        </div>
      </div>

      {tab === 'settings' ? (
        <PairSettingsForm settings={settings} onChange={apply} running={state.running} />
      ) : (
        <>
          <div className="card">
            <h2>Пара Up + Down = 1 $</h2>
            <div className="grid2">
              <div className="stat">
                <div className="k">Связано пар</div>
                <div className="v">{book ? book.pairs.toFixed(1) : '—'}</div>
              </div>
              <div className="stat">
                <div className="k">Средняя пары</div>
                <div
                  className={`v ${
                    book && book.pairs > 0
                      ? book.pairAvg <= settings.maxPairAvg
                        ? 'up'
                        : 'down'
                      : ''
                  }`}
                >
                  {book && book.pairs > 0 ? cents(book.pairAvg) : '—'}
                </div>
              </div>
            </div>

            <div className="row">
              <span className="label">Заперто в парах</span>
              <span className={`value ${book && book.lockedProfitUsd >= 0 ? 'up' : 'down'}`}>
                {book ? usd(book.lockedProfitUsd) : '—'}
              </span>
            </div>
            <div className="row">
              <span className="label">Незакрытый перекос</span>
              <span className={`value ${book && book.imbalance !== 0 ? 'warn' : ''}`}>
                {book
                  ? book.imbalance === 0
                    ? 'нет'
                    : `${book.imbalance > 0 ? 'Up' : 'Down'} ${Math.abs(
                        book.imbalance,
                      ).toFixed(1)}`
                  : '—'}
              </span>
            </div>
            <div className="row">
              <span className="label">В работе</span>
              <span className="value">
                {book ? `${book.exposureUsd.toFixed(2)} $` : '—'}
              </span>
            </div>
            <div className="row">
              <span className="label">Комиссии окна</span>
              <span className="value">{book ? `${book.feesUsd.toFixed(3)} $` : '—'}</span>
            </div>
          </div>

          <div className="card">
            <h2>Стороны</h2>
            <div className="grid2">
              <LegTile
                label="Up"
                shares={book?.upShares}
                avg={book?.upAvg}
                bid={upQ?.bestBid}
                ask={upQ?.bestAsk}
              />
              <LegTile
                label="Down"
                shares={book?.downShares}
                avg={book?.downAvg}
                bid={downQ?.bestBid}
                ask={downQ?.bestAsk}
              />
            </div>
          </div>

          <div className="card">
            <h2>Активные ордера</h2>
            {state.orders.length === 0 ? (
              <div className="muted" style={{ fontSize: 13 }}>
                Нет активных ордеров.
              </div>
            ) : (
              state.orders.map((o) => <OrderRow key={o.localId} order={o} />)
            )}
          </div>

          <div className="card">
            <h2>Сделки</h2>
            {state.fills.length === 0 ? (
              <div className="muted" style={{ fontSize: 13 }}>
                Сделок пока нет.
              </div>
            ) : (
              state.fills.map((f, i) => <FillRow key={`${f.at}-${i}`} fill={f} />)
            )}
          </div>

          <div className="card">
            <h2>Итог</h2>
            <div className="grid2">
              <div className="stat">
                <div className="k">Результат</div>
                <div
                  className={`v ${state.stats.realisedPnlUsd >= 0 ? 'up' : 'down'}`}
                >
                  {usd(state.stats.realisedPnlUsd)}
                </div>
              </div>
              <div className="stat">
                <div className="k">Окон закрыто</div>
                <div className="v">{state.stats.windows}</div>
              </div>
            </div>
            <div className="row">
              <span className="label">Покупок / продаж</span>
              <span className="value">
                {state.stats.buys} / {state.stats.sells}
              </span>
            </div>
            <div className="row">
              <span className="label">Пар собрано всего</span>
              <span className="value">{state.stats.pairsLocked.toFixed(1)}</span>
            </div>
            <div className="row">
              <span className="label">Комиссии всего</span>
              <span className="value">{state.stats.feesUsd.toFixed(3)} $</span>
            </div>
          </div>

          {state.windows.length > 0 && (
            <div className="card">
              <h2>Закрытые окна</h2>
              {state.windows.map((w) => (
                <div className="row" key={w.windowStart}>
                  <span className="label">
                    {clock(w.windowStart * 1000).slice(0, 5)} · пар{' '}
                    {w.pairs.toFixed(1)} по {cents(w.pairAvg)}
                  </span>
                  <span
                    className={`value ${(w.pnlUsd ?? 0) >= 0 ? 'up' : 'down'}`}
                  >
                    {usd(w.pnlUsd ?? 0)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {state.haltReason && !state.running && (
        <div className="banner warn">{state.haltReason}</div>
      )}

      <button
        className={state.running ? 'danger' : 'primary'}
        onClick={() => void toggle()}
      >
        {state.running
          ? 'Остановить'
          : settings.dryRun
            ? 'Запустить тест'
            : 'Запустить на реальные'}
      </button>
    </>
  );
}

function LegTile({
  label,
  shares,
  avg,
  bid,
  ask,
}: {
  label: string;
  shares?: number;
  avg?: number;
  bid?: number | null;
  ask?: number | null;
}) {
  return (
    <div className="stat">
      <div className="k">{label}</div>
      <div className="v">{shares !== undefined ? shares.toFixed(1) : '—'}</div>
      <div className="muted" style={{ fontSize: 11 }}>
        средняя {shares ? cents(avg ?? 0) : '—'}
      </div>
      <div className="muted" style={{ fontSize: 11 }}>
        {bid != null ? cents(bid) : '—'} / {ask != null ? cents(ask) : '—'}
      </div>
    </div>
  );
}

function OrderRow({ order }: { order: PairOrder }) {
  const remaining = order.size - order.matched;
  return (
    <div className="ledger">
      <span className={`tag ${order.action === 'BUY' ? 'buy' : 'sell'}`}>
        {order.action === 'BUY' ? 'ПОКУПКА' : 'ПРОДАЖА'}
      </span>
      <span className={order.side === 'Up' ? 'up' : 'down'}>{order.side}</span>
      <span className="ledger-main">
        {remaining.toFixed(1)} × {cents(order.price)}
      </span>
      <span className="muted ledger-note">
        {order.dryRun ? 'тест · ' : ''}
        {order.note}
      </span>
    </div>
  );
}

function FillRow({ fill }: { fill: PairFill }) {
  return (
    <div className="ledger">
      <span className={`tag ${fill.action === 'BUY' ? 'buy' : 'sell'}`}>
        {fill.action === 'BUY' ? 'ПОКУПКА' : 'ПРОДАЖА'}
      </span>
      <span className={fill.side === 'Up' ? 'up' : 'down'}>{fill.side}</span>
      <span className="ledger-main">
        {fill.shares.toFixed(1)} × {cents(fill.price)}
      </span>
      <span className="muted ledger-note">
        {clock(fill.at)}
        {fill.dryRun ? ' · тест' : ''}
        {fill.feeUsd > 0 ? ` · комиссия ${fill.feeUsd.toFixed(3)} $` : ''}
      </span>
    </div>
  );
}

function PairSettingsForm({
  settings,
  onChange,
  running,
}: {
  settings: PairSettings;
  onChange: (next: PairSettings) => void;
  running: boolean;
}) {
  const num = (key: keyof PairSettings, label: string, step = '1', hint?: string) => (
    <label className="field" key={key}>
      <span>{label}</span>
      <input
        type="number"
        step={step}
        value={String(settings[key] as number)}
        onChange={(e) =>
          onChange({ ...settings, [key]: Number(e.target.value.replace(',', '.')) })
        }
      />
      {hint && (
        <span className="muted" style={{ fontSize: 11 }}>
          {hint}
        </span>
      )}
    </label>
  );

  return (
    <div className="card">
      <h2>Правила</h2>

      <div className="toggle">
        <div>
          <div>Тестовый режим</div>
          <div className="muted" style={{ fontSize: 11 }}>
            Считает по реальному стакану и комиссиям, но ничего не отправляет.
          </div>
        </div>
        <button
          className={`switch ${settings.dryRun ? 'on' : ''}`}
          disabled={running}
          onClick={() => onChange({ ...settings, dryRun: !settings.dryRun })}
        />
      </div>

      <div className="toggle">
        <div>
          <div>Бить в стакан</div>
          <div className="muted" style={{ fontSize: 11 }}>
            Быстрее исполняется, но платит комиссию тейкера на каждой ноге — это
            большая часть маржи пары.
          </div>
        </div>
        <button
          className={`switch ${settings.takerEntry ? 'on' : ''}`}
          onClick={() => onChange({ ...settings, takerEntry: !settings.takerEntry })}
        />
      </div>

      <div style={{ height: 12 }} />
      {num('lotShares', 'Долей в лоте', '1', 'Минимум биржи — 5.')}
      {num('minIntervalSec', 'Пауза между лотами, от (сек)')}
      {num('maxIntervalSec', 'Пауза между лотами, до (сек)')}
      {num('maxSeedPrice', 'Набирать сторону дешевле', '0.01', 'Доля, не центы: 0.50 = 50¢.')}
      {num(
        'maxPairAvg',
        'Потолок средней пары',
        '0.01',
        'Главный предохранитель: пара платит 1 $, значит 0.95 — это 5¢ маржи.',
      )}
      {num('minPairProfitPct', 'Минимальная доходность пары', '0.01', '0.03 = 3%.')}
      {num('rotateProfitPct', 'Ротация от прибыли', '0.01', '0.10 = продать половину на +10%.')}
      {num('cheapLegUnder', 'Дешёвая нога — дешевле', '0.01')}
      {num('cheapRotateProfitPct', 'Ротация дешёвой ноги', '0.01', '0.05 = +5%.')}
      {num('rotateFraction', 'Доля ноги в продажу', '0.1', '0.5 = половина.')}
      {num('maxExposureUsd', 'Потолок вложенного, $', '1')}
      {num('maxImbalanceShares', 'Потолок перекоса, долей', '1', 'Единственный риск направления.')}
      {num('flattenSec', 'Сводить книгу за (сек до закрытия)', '5')}
    </div>
  );
}
