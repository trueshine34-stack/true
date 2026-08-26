import { useEffect, useState } from 'react';
import type { StrategyMode, StrategySettings } from '../core/settings';
import { SignatureType, type AccountConfig } from '../core/account';
import { clearVault } from '../core/storage';
import { PolyBot } from '../native/polybot';
import { Diagnostics } from './Diagnostics';
import { Logs } from './Logs';
import { Fold } from './Fold';

export function SettingsScreen({
  settings,
  account,
  onChange,
  onForget,
}: {
  settings: StrategySettings;
  account: AccountConfig | null;
  onChange: (s: StrategySettings) => void;
  onForget: () => void;
}) {
  const [balance, setBalance] = useState<string | null>(null);
  const [balanceError, setBalanceError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [batteryExempt, setBatteryExempt] = useState<boolean | null>(null);
  const [showDiagnostics, setShowDiagnostics] = useState(false);

  useEffect(() => {
    void PolyBot.isBatteryExempt()
      .then((r) => setBatteryExempt(r.exempt))
      .catch(() => setBatteryExempt(null));
  }, []);

  const set = <K extends keyof StrategySettings>(
    key: K,
    value: StrategySettings[K],
  ) => onChange({ ...settings, [key]: value });

  const num = (raw: string, fallback: number) => {
    const v = Number(raw.replace(',', '.'));
    return Number.isFinite(v) ? v : fallback;
  };

  const checkBalance = async () => {
    setChecking(true);
    setBalanceError(null);
    try {
      const r = await PolyBot.getBalance();
      setBalance(r.usdc.toFixed(2));
    } catch (err) {
      setBalanceError(err instanceof Error ? err.message : String(err));
      setShowDiagnostics(true);
    } finally {
      setChecking(false);
    }
  };

  const askBatteryExemption = async () => {
    const r = await PolyBot.requestBatteryExemption();
    setBatteryExempt(r.exempt);
  };

  const forget = async () => {
    await PolyBot.vaultClear();
    await clearVault();
    onForget();
  };

  return (
    <>
      {batteryExempt === false && (
        <div className="banner warn">
          Android может усыплять приложение в фоне. Отключите для него
          оптимизацию батареи, иначе окна будут пропускаться.
          <button
            className="ghost"
            style={{ marginTop: 10 }}
            onClick={() => void askBatteryExemption()}
          >
            Отключить оптимизацию батареи
          </button>
        </div>
      )}

      <Fold title="Торговля">

        <label className="field">
          <span>Стратегия</span>
          <select
            value={settings.mode}
            onChange={(e) => set('mode', e.target.value as StrategyMode)}
          >
            <option value="edge">По модели — входить только при перевесе</option>
            <option value="momentum">По тренду — сторона, что уже впереди</option>
            <option value="contrarian">Против тренда — отстающая сторона</option>
            <option value="off">Не торговать — только наблюдение</option>
          </select>
        </label>

        <label className="field">
          <span>Ставка на сделку, $</span>
          <input
            type="text"
            inputMode="decimal"
            value={String(settings.stakeUsd)}
            onChange={(e) => set('stakeUsd', num(e.target.value, settings.stakeUsd))}
          />
        </label>

        <label className="field">
          <span>Вход через N секунд после начала окна</span>
          <input
            type="text"
            inputMode="numeric"
            value={String(settings.entryDelaySec)}
            onChange={(e) =>
              set(
                'entryDelaySec',
                clampInt(num(e.target.value, settings.entryDelaySec), 0, 240),
              )
            }
          />
        </label>

        <div className="grid2">
          <label className="field">
            <span>Попыток входа</span>
            <input
              type="text"
              inputMode="numeric"
              value={String(settings.entryAttempts)}
              onChange={(e) =>
                set(
                  'entryAttempts',
                  clampInt(num(e.target.value, settings.entryAttempts), 1, 10),
                )
              }
            />
          </label>
          <label className="field">
            <span>Пауза между ними, с</span>
            <input
              type="text"
              inputMode="numeric"
              value={String(settings.entryRetryDelaySec)}
              onChange={(e) =>
                set(
                  'entryRetryDelaySec',
                  clampInt(num(e.target.value, settings.entryRetryDelaySec), 5, 120),
                )
              }
            />
          </label>
        </div>

        {settings.mode === 'edge' && (
          <label className="field">
            <span>Минимальный перевес модели, %</span>
            <input
              type="text"
              inputMode="decimal"
              value={(settings.minEdge * 100).toFixed(1)}
              onChange={(e) =>
                set('minEdge', num(e.target.value, settings.minEdge * 100) / 100)
              }
            />
          </label>
        )}

        <div className="grid2">
          <label className="field">
            <span>Мин. цена, ¢</span>
            <input
              type="text"
              inputMode="numeric"
              value={(settings.minPrice * 100).toFixed(0)}
              onChange={(e) =>
                set('minPrice', num(e.target.value, settings.minPrice * 100) / 100)
              }
            />
          </label>
          <label className="field">
            <span>Макс. цена, ¢</span>
            <input
              type="text"
              inputMode="numeric"
              value={(settings.maxPrice * 100).toFixed(0)}
              onChange={(e) =>
                set('maxPrice', num(e.target.value, settings.maxPrice * 100) / 100)
              }
            />
          </label>
        </div>

        <div className="toggle">
          <span>Поднимать ставку до минимума биржи</span>
          <Switch
            on={settings.autoBumpToMinimum}
            onToggle={() => set('autoBumpToMinimum', !settings.autoBumpToMinimum)}
          />
        </div>

        <div className="toggle">
          <span>Тестовый режим</span>
          <Switch on={settings.dryRun} onToggle={() => set('dryRun', !settings.dryRun)} />
        </div>
      </Fold>

      <Fold title="Выход из позиции">

        <div className="toggle" style={{ borderTop: 'none', paddingTop: 0 }}>
          <span>Ставить продажу сразу после входа</span>
          <Switch
            on={settings.exitEnabled}
            onToggle={() => set('exitEnabled', !settings.exitEnabled)}
          />
        </div>

        {settings.exitEnabled && (
          <>
            <label className="field">
              <span>Ставить продажу через N секунд после покупки</span>
              <input
                type="text"
                inputMode="numeric"
                value={String(settings.exitDelaySec)}
                onChange={(e) =>
                  set(
                    'exitDelaySec',
                    clampInt(num(e.target.value, settings.exitDelaySec), 0, 120),
                  )
                }
              />
            </label>

            <div className="muted" style={{ fontSize: 12, margin: '12px 0 6px' }}>
              Лесенка: с какой секунды окна какая цена продажи.
            </div>

            {settings.exitLadder.map((step, i) => (
              <div className="grid2" key={i}>
                <label className="field">
                  <span>С секунды</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    value={String(step.fromSec)}
                    onChange={(e) =>
                      set(
                        'exitLadder',
                        settings.exitLadder.map((s, j) =>
                          j === i
                            ? {
                                ...s,
                                fromSec: clampInt(num(e.target.value, s.fromSec), 0, 299),
                              }
                            : s,
                        ),
                      )
                    }
                  />
                </label>
                <label className="field">
                  <span>Цена, ¢</span>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <input
                      type="text"
                      inputMode="numeric"
                      value={(step.price * 100).toFixed(0)}
                      onChange={(e) =>
                        set(
                          'exitLadder',
                          settings.exitLadder.map((s, j) =>
                            j === i
                              ? {
                                  ...s,
                                  price:
                                    clampInt(num(e.target.value, s.price * 100), 1, 99) /
                                    100,
                                }
                              : s,
                          ),
                        )
                      }
                    />
                    {settings.exitLadder.length > 1 && (
                      <button
                        className="ghost"
                        style={{ width: 46, padding: 0 }}
                        onClick={() =>
                          set(
                            'exitLadder',
                            settings.exitLadder.filter((_, j) => j !== i),
                          )
                        }
                      >
                        −
                      </button>
                    )}
                  </div>
                </label>
              </div>
            ))}

            <button
              className="ghost"
              onClick={() => {
                const last = settings.exitLadder[settings.exitLadder.length - 1];
                set('exitLadder', [
                  ...settings.exitLadder,
                  {
                    fromSec: Math.min(299, (last?.fromSec ?? 0) + 30),
                    price: Math.min(0.99, (last?.price ?? 0.9) + 0.03),
                  },
                ]);
              }}
            >
              Добавить ступень
            </button>
          </>
        )}
      </Fold>

      <Fold title="Аккаунт">
        <div className="row">
          <span className="label">Ключ подписи</span>
          <span className="value mono">{account?.signerAddress ?? '—'}</span>
        </div>
        <div className="row">
          <span className="label">Кошелёк со средствами</span>
          <span className="value mono">{account?.funderAddress ?? '—'}</span>
        </div>
        <div className="row">
          <span className="label">Тип подписи</span>
          <span className="value">
            {account ? sigTypeLabel(account.signatureType) : '—'}
          </span>
        </div>
        <div className="row">
          <span className="label">Фоновый режим</span>
          <span className="value">
            {batteryExempt === null
              ? '—'
              : batteryExempt
                ? 'разрешён'
                : 'ограничен системой'}
          </span>
        </div>
        {balance !== null && (
          <div className="row">
            <span className="label">Баланс USDC</span>
            <span className="value">{balance} $</span>
          </div>
        )}
        {balanceError && <div className="banner error">{balanceError}</div>}
        {showDiagnostics && <Diagnostics />}

        <button
          className="ghost"
          style={{ marginTop: 10 }}
          disabled={checking}
          onClick={() => void checkBalance()}
        >
          {checking ? 'Проверяем…' : 'Проверить баланс'}
        </button>
        <button
          className="ghost"
          style={{ marginTop: 10 }}
          onClick={() => setShowDiagnostics((v) => !v)}
        >
          {showDiagnostics ? 'Скрыть проверку сети' : 'Проверить доступ к бирже'}
        </button>
        <button
          className="danger"
          style={{ marginTop: 10 }}
          onClick={() => void forget()}
        >
          Отключить кошелёк
        </button>
      </Fold>

      <Fold title="Журнал">
        <Logs />
      </Fold>
    </>
  );
}

function Switch({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <button
      className={`switch ${on ? 'on' : ''}`}
      onClick={onToggle}
      aria-pressed={on}
    />
  );
}

function clampInt(x: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, Math.round(x)));
}

function sigTypeLabel(t: SignatureType): string {
  switch (t) {
    case SignatureType.POLY_PROXY:
      return 'Proxy-кошелёк (email)';
    case SignatureType.POLY_GNOSIS_SAFE:
      return 'Gnosis Safe (браузерный кошелёк)';
    default:
      return 'EOA';
  }
}
