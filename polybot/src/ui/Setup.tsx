import { useState } from 'react';
import { Wallet, isAddress } from 'ethers';
import type { Session } from '../bot/engine';
import { log } from '../core/logger';
import {
  saveAccount,
  saveEncryptedKey,
  type AccountConfig,
} from '../core/storage';
import {
  createOrDeriveApiCreds,
  getBalanceAllowance,
} from '../polymarket/clob';
import { SignatureType } from '../polymarket/types';

type WalletKind = 'email' | 'browser' | 'eoa';

const KIND_TO_SIGTYPE: Record<WalletKind, SignatureType> = {
  email: SignatureType.POLY_PROXY,
  browser: SignatureType.POLY_GNOSIS_SAFE,
  eoa: SignatureType.EOA,
};

export function Setup({
  onDone,
}: {
  onDone: (account: AccountConfig, session: Session) => void;
}) {
  const [privateKey, setPrivateKey] = useState('');
  const [kind, setKind] = useState<WalletKind>('email');
  const [funder, setFunder] = useState('');
  const [pin, setPin] = useState('');
  const [pin2, setPin2] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [balance, setBalance] = useState<string | null>(null);

  const needsFunder = kind !== 'eoa';

  const connect = async () => {
    setError(null);
    setBalance(null);

    const key = privateKey.trim();
    if (!key) return setError('Вставьте приватный ключ.');
    if (pin.length < 4) return setError('PIN должен быть не короче 4 символов.');
    if (pin !== pin2) return setError('PIN и подтверждение не совпадают.');

    let wallet: Wallet;
    try {
      wallet = new Wallet(key.startsWith('0x') ? key : `0x${key}`);
    } catch {
      return setError('Не похоже на приватный ключ (нужны 64 hex-символа).');
    }

    const funderAddress = needsFunder ? funder.trim() : wallet.address;
    if (needsFunder && !isAddress(funderAddress)) {
      return setError('Укажите корректный адрес кошелька Polymarket.');
    }

    setBusy(true);
    try {
      const creds = await createOrDeriveApiCreds(wallet);
      const signatureType = KIND_TO_SIGTYPE[kind];

      // A balance read is the cheapest proof that the signer, the funder and
      // the wallet type line up — a mismatch shows up here instead of on the
      // first live order.
      let usdc: string | null = null;
      try {
        const ba = await getBalanceAllowance(
          wallet,
          creds,
          signatureType,
          'COLLATERAL',
        );
        usdc = (Number(ba.balance ?? 0) / 1e6).toFixed(2);
        setBalance(usdc);
      } catch (err) {
        log.warn('Не удалось прочитать баланс USDC', err);
      }

      const account: AccountConfig = {
        signerAddress: wallet.address,
        funderAddress,
        signatureType,
      };

      await saveEncryptedKey(wallet.privateKey, pin);
      await saveAccount(account);

      log.info(
        `Кошелёк подключён: ${wallet.address}${usdc !== null ? ` · ${usdc} USDC` : ''}`,
      );

      onDone(account, {
        wallet,
        creds,
        funderAddress,
        signatureType,
      });
    } catch (err) {
      setError(
        `Не удалось подключиться к CLOB: ${err instanceof Error ? err.message : String(err)}`,
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app">
      <div className="scroll">
        <h1 style={{ fontSize: 21, marginBottom: 4 }}>Подключение Polymarket</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          Ключ шифруется PIN-кодом и хранится только на этом телефоне. Он никуда
          не отправляется — им подписываются ордера прямо на устройстве.
        </p>

        {error && <div className="banner error">{error}</div>}
        {balance !== null && (
          <div className="banner info">Баланс USDC: {balance} $</div>
        )}

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
          <h2>PIN для шифрования</h2>
          <label className="field">
            <span>PIN</span>
            <input
              type="password"
              inputMode="numeric"
              value={pin}
              onChange={(e) => setPin(e.target.value)}
            />
          </label>
          <label className="field">
            <span>Повторите PIN</span>
            <input
              type="password"
              inputMode="numeric"
              value={pin2}
              onChange={(e) => setPin2(e.target.value)}
            />
          </label>
          <p className="muted" style={{ fontSize: 12, margin: 0 }}>
            PIN нигде не сохраняется. Если забудете — просто импортируйте ключ
            заново.
          </p>
        </div>

        <button className="primary" disabled={busy} onClick={() => void connect()}>
          {busy ? 'Подключаемся…' : 'Подключить'}
        </button>

        <p className="muted" style={{ fontSize: 12, marginTop: 14 }}>
          Бот стартует в тестовом режиме: ордера считаются и подписываются, но не
          отправляются. Реальную торговлю нужно включить вручную в настройках.
        </p>
      </div>
    </div>
  );
}
