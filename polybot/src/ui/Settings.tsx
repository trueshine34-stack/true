import { useEffect, useState } from 'react';
import { SignatureType, type AccountConfig } from '../core/account';
import { clearVault } from '../core/storage';
import { PolyBot } from '../native/polybot';
import { Diagnostics } from './Diagnostics';
import { Logs } from './Logs';
import { Fold } from './Fold';
import { APP_VERSION } from '../version';

export function SettingsScreen({
  account,
  onForget,
}: {
  account: AccountConfig | null;
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

      <div className="buildmark muted">PolyBot {APP_VERSION}</div>
    </>
  );
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
