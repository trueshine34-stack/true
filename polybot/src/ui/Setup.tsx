import { useState } from 'react';
import {
  looksLikeAddress,
  looksLikePrivateKey,
  SignatureType,
  type AccountConfig,
} from '../core/account';
import type { StrategySettings } from '../core/settings';
import { saveAccount } from '../core/storage';
import { PolyBot } from '../native/polybot';
import { Diagnostics } from './Diagnostics';

type WalletKind = 'email' | 'browser' | 'eoa';

const KIND_TO_SIGTYPE: Record<WalletKind, SignatureType> = {
  email: SignatureType.POLY_PROXY,
  browser: SignatureType.POLY_GNOSIS_SAFE,
  eoa: SignatureType.EOA,
};

export function Setup({
  settings,
  onDone,
}: {
  settings: StrategySettings;
  onDone: (account: AccountConfig) => void;
}) {
  const [privateKey, setPrivateKey] = useState('');
  const [kind, setKind] = useState<WalletKind>('email');
  const [funder, setFunder] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDiagnostics, setShowDiagnostics] = useState(false);

  const needsFunder = kind !== 'eoa';

  const connect = async () => {
    setError(null);
    setShowDiagnostics(false);

    const key = privateKey.trim();
    if (!looksLikePrivateKey(key)) {
      return setError('Не похоже на приватный ключ (нужны 64 hex-символа).');
    }
    const funderAddress = needsFunder ? funder.trim() : '';
    if (needsFunder && !looksLikeAddress(funderAddress)) {
      return setError('Укажите корректный адрес кошелька Polymarket.');
    }

    const signatureType = KIND_TO_SIGTYPE[kind];
    setBusy(true);
    try {
      const result = await PolyBot.connect({
        privateKey: key,
        funderAddress,
        signatureType: Number(signatureType),
        settings,
      });

      const account: AccountConfig = {
        signerAddress: result.address,
        funderAddress: funderAddress || result.address,
        signatureType,
      };

      await PolyBot.vaultStore({
        privateKey: key.startsWith('0x') ? key : `0x${key}`,
      });
      await saveAccount(account);
      onDone(account);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      // A transport failure here is usually the network, not the wallet —
      // the probe tells the two apart instead of leaving the user guessing.
      setShowDiagnostics(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app">
      <div className="scroll">
        <h1 style={{ fontSize: 21, marginBottom: 4 }}>Подключение Polymarket</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          Ключ шифруется хранилищем Android и остаётся только на этом телефоне. Он никуда
          не отправляется — им подписываются ордера прямо на устройстве.
        </p>

        {error && <div className="banner error">{error}</div>}
        {showDiagnostics && <Diagnostics />}

        <div className="card">
          <h2>Кошелёк</h2>

          <label className="field">
            <span>Тип аккаунта Polymarket</span>
            <select
              value={kind}
              onChange={(e) => setKind(e.target.value as WalletKind)}
            >
              <option value="email">Вход по email / Magic (proxy-кошелёк)</option>
              <option value="browser">Вход через MetaMask и т.п. (Gnosis Safe)</option>
              <option value="eoa">Обычный EOA — средства на самом ключе</option>
            </select>
          </label>

          <label className="field">
            <span>Приватный ключ подписи</span>
            <input
              type="password"
              placeholder="0x…"
              autoCapitalize="none"
              autoCorrect="off"
              value={privateKey}
              onChange={(e) => setPrivateKey(e.target.value)}
            />
          </label>

          {needsFunder && (
            <label className="field">
              <span>Адрес кошелька Polymarket (где лежит USDC)</span>
              <input
                placeholder="0x…"
                autoCapitalize="none"
                autoCorrect="off"
                value={funder}
                onChange={(e) => setFunder(e.target.value)}
              />
            </label>
          )}

          <p className="muted" style={{ fontSize: 12, marginBottom: 0 }}>
            В Polymarket: профиль → Settings → Export Private Key даёт ключ
            подписи. Адрес кошелька — тот, что показан как адрес для пополнения.
          </p>
        </div>

        <div className="card">
          <h2>Как хранится ключ</h2>
          <p className="muted" style={{ fontSize: 12, margin: 0 }}>
            Ключ шифруется хранилищем Android: сам ключ шифрования держит
            система, приложению он не выдаётся. Из бэкапа или другого
            приложения достать его нельзя, но и PIN на входе больше нет — кто
            держит разблокированный телефон, тот и торгует.
          </p>
        </div>

        <button className="primary" disabled={busy} onClick={() => void connect()}>
          {busy ? 'Подключаемся…' : 'Подключить'}
        </button>

        {!showDiagnostics && (
          <button
            className="ghost"
            style={{ marginTop: 10 }}
            onClick={() => setShowDiagnostics(true)}
          >
            Проверить доступ к бирже
          </button>
        )}

        <p className="muted" style={{ fontSize: 12, marginTop: 14 }}>
          Бот стартует в тестовом режиме: ордера считаются и подписываются, но не
          отправляются. Реальную торговлю нужно включить вручную в настройках.
        </p>
      </div>
    </div>
  );
}
