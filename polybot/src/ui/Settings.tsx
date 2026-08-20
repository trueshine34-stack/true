import { useState } from 'react';
import type { Session } from '../bot/engine';
import type { StrategyMode, StrategySettings } from '../bot/strategy';
import { clearVault, type AccountConfig } from '../core/storage';
import { getBalanceAllowance } from '../polymarket/clob';
import { SignatureType } from '../polymarket/types';

export function SettingsScreen({
  settings,
  account,
  session,
  onChange,
  onForget,
}: {
  settings: StrategySettings;
  account: AccountConfig | null;
  session: Session | null;
  onChange: (s: StrategySettings) => void;
  onForget: () => void;
}) {
  const [balance, setBalance] = useState<string | null>(null);
  const [balanceError, setBalanceError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  const set = <K extends keyof StrategySettings>(
    key: K,
    value: StrategySettings[K],
  ) => onChange({ ...settings, [key]: value });

  const num = (raw: string, fallback: number) => {
    const v = Number(raw.replace(',', '.'));
    return Number.isFinite(v) ? v : fallback;
  };

  const checkBalance = async () => {
    if (!session) return;
    setChecking(true);
    setBalanceError(null);
    try {
      const ba = await getBalanceAllowance(
        session.wallet,
        session.creds,
        session.signatureType,
        'COLLATERAL',
      );
      setBalance((Number(ba.balance ?? 0) / 1e6).toFixed(2));
    } catch (err) {
      setBalanceError(err instanceof Error ? err.message : String(err));
    } finally {
      setChecking(false);
    }
  };

  const forget = async () => {
    await clearVault();
    onForget();
  };

  return (
    <>
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
              set('entryDelaySec', clampInt(num(e.target.value, settings.entryDelaySec), 0, 240))
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
        {balance !== null && (
          <div className="row">
            <span className="label">Баланс USDC</span>
            <span className="value">{balance} $</span>
          </div>
        )}
        {balanceError && <div className="banner error">{balanceError}</div>}

        <button
          className="ghost"
          style={{ marginTop: 10 }}
          disabled={checking || !session}
          onClick={() => void checkBalance()}
        >
          {checking ? 'Проверяем…' : 'Проверить баланс'}
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
