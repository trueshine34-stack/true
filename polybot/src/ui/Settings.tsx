import { useEffect, useState } from 'react';
import type { StrategyMode, StrategySettings } from '../core/settings';
import { SignatureType, type AccountConfig } from '../core/account';
import { clearVault } from '../core/storage';
import { PolyBot } from '../native/polybot';
import { Diagnostics } from './Diagnostics';

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

      <div className="card">
        <h2>Торговля</h2>

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
          <div>
            <div>Поднимать ставку до минимума биржи</div>
            <div className="muted" style={{ fontSize: 12 }}>
              Минимум — 5 долей, поэтому 2 $ проходят только при цене до 40¢.
            </div>
          </div>
          <Switch
            on={settings.autoBumpToMinimum}
            onToggle={() => set('autoBumpToMinimum', !settings.autoBumpToMinimum)}
          />
        </div>

        <div className="toggle">
          <div>
            <div>Тестовый режим</div>
            <div className="muted" style={{ fontSize: 12 }}>
              Считать и подписывать ордера, но не отправлять их.
            </div>
          </div>
          <Switch on={settings.dryRun} onToggle={() => set('dryRun', !settings.dryRun)} />
        </div>
      </div>

      <div className="card">
        <h2>Выход из позиции</h2>

        <div className="toggle" style={{ borderTop: 'none', paddingTop: 0 }}>
          <div>
            <div>Ставить продажу сразу после входа</div>
            <div className="muted" style={{ fontSize: 12 }}>
              Лимитка на всю позицию: сначала по первой цене, за N секунд до
              конца окна переставляется на вторую.
            </div>
          </div>
          <Switch
            on={settings.exitEnabled}
            onToggle={() => set('exitEnabled', !settings.exitEnabled)}
          />
        </div>

        {settings.exitEnabled && (
          <>
            <div className="grid2">
              <label className="field">
                <span>Первая цена, ¢</span>
                <input
                  type="text"
                  inputMode="numeric"
                  value={(settings.exitPriceEarly * 100).toFixed(0)}
                  onChange={(e) =>
                    set(
                      'exitPriceEarly',
                      num(e.target.value, settings.exitPriceEarly * 100) / 100,
                    )
                  }
                />
              </label>
              <label className="field">
                <span>Вторая цена, ¢</span>
                <input
                  type="text"
                  inputMode="numeric"
                  value={(settings.exitPriceLate * 100).toFixed(0)}
                  onChange={(e) =>
                    set(
                      'exitPriceLate',
                      num(e.target.value, settings.exitPriceLate * 100) / 100,
                    )
                  }
                />
              </label>
            </div>
            <label className="field">
              <span>Переставить за N секунд до конца окна</span>
              <input
                type="text"
                inputMode="numeric"
                value={String(settings.exitSwitchSec)}
                onChange={(e) =>
                  set(
                    'exitSwitchSec',
                    clampInt(num(e.target.value, settings.exitSwitchSec), 5, 280),
                  )
                }
              />
            </label>
            <p className="muted" style={{ fontSize: 12, margin: 0 }}>
              Если к моменту перестановки неисполненный остаток меньше минимума
              биржи, ордер остаётся на первой цене — снимать его было бы хуже,
              чем оставить шанс на исполнение.
            </p>
          </>
        )}
      </div>

      <div className="card">
        <h2>Ограничение риска</h2>
        <label className="field">
          <span>Стоп по убытку за сессию, $</span>
          <input
            type="text"
            inputMode="decimal"
            value={String(settings.dailyLossLimitUsd)}
            onChange={(e) =>
              set('dailyLossLimitUsd', num(e.target.value, settings.dailyLossLimitUsd))
            }
          />
        </label>
        <label className="field">
          <span>Стоп после N убытков подряд</span>
          <input
            type="text"
            inputMode="numeric"
            value={String(settings.maxConsecutiveLosses)}
            onChange={(e) =>
              set(
                'maxConsecutiveLosses',
                clampInt(num(e.target.value, settings.maxConsecutiveLosses), 1, 100),
              )
            }
          />
        </label>
      </div>

      <div className="card">
        <h2>Аккаунт</h2>
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
      </div>
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
