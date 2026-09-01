import { useEffect, useState } from 'react';
import { SignatureType, type AccountConfig } from '../core/account';
import { clearVault, loadChimeTest, saveChimeTest } from '../core/storage';
import { PolyBot } from '../native/polybot';
import { Diagnostics } from './Diagnostics';
import { Logs } from './Logs';
import { Fold } from './Fold';
import { APP_VERSION } from '../version';
import { DAY_MULTIPLE } from '../core/day';

export function SettingsScreen({
  account,
  onForget,
  dayLock,
  onDayLock,
  dayHit,
}: {
  account: AccountConfig | null;
  onForget: () => void;
  /** Whether reaching the day's goal stops buying until midnight. */
  dayLock: boolean;
  onDayLock: (on: boolean) => void;
  /** Whether it is stopped right now, which is what the switch would lift. */
  dayHit: boolean;
}) {
  const [balance, setBalance] = useState<string | null>(null);
  const [balanceError, setBalanceError] = useState<string | null>(null);
  /** How much of that balance is held back from every order. */
  const [lockedUsd, setLockedUsd] = useState(0);
  const [checking, setChecking] = useState(false);
  const [batteryExempt, setBatteryExempt] = useState<boolean | null>(null);
  const [showDiagnostics, setShowDiagnostics] = useState(false);
  /**
   * The sound test, and whether it is still here.
   *
   * Null while the answer is being read off disk, so the block does not flash
   * onto the screen of someone who has already taken it away.
   */
  const [chimeTest, setChimeTest] = useState<boolean | null>(null);
  /** The delete button asks once before it does something irreversible. */
  const [dropping, setDropping] = useState(false);

  useEffect(() => {
    void PolyBot.isBatteryExempt()
      .then((r) => setBatteryExempt(r.exempt))
      .catch(() => setBatteryExempt(null));
    void loadChimeTest().then(setChimeTest);
  }, []);

  const checkBalance = async () => {
    setChecking(true);
    setBalanceError(null);
    try {
      const r = await PolyBot.getBalance();
      // The wallet, since this row is a connection check — it answers "does
      // the venue see my money", not "what may this order be".
      setBalance((r.wallet ?? r.usdc).toFixed(2));
      setLockedUsd(r.locked ?? 0);
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
            <span className="value">
              {balance} $
              {lockedUsd > 0 && (
                <span className="muted"> · 🔒{lockedUsd.toFixed(2)}</span>
              )}
            </span>
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

      {chimeTest && (
        <Fold title="Звук сделки">
          {/*
            Three buttons that make the three sounds. A cue only ever fires at
            the moment of a trade, which is the worst possible time to discover
            that it does not — and silence answers "did I miss it?" and "is it
            broken?" identically.
          */}
          <div className="muted balhint" style={{ margin: '0 0 10px' }}>
            Звучит на медиа-канале: слышно в наушниках и при беззвучном режиме
            телефона, поверх музыки. Громкость — медиа.
          </div>
          <div className="pcts">
            <button onClick={() => void PolyBot.playChime({ kind: 'up' })}>
              Up
            </button>
            <button onClick={() => void PolyBot.playChime({ kind: 'down' })}>
              Down
            </button>
            <button onClick={() => void PolyBot.playChime({ kind: 'sold' })}>
              Продажа
            </button>
          </div>

          {/*
            And the way out. Once the question is answered these are three
            buttons in the way of the ones that get used — so the block takes
            itself off, delete button and all, rather than waiting for a new
            build to do it.
          */}
          {dropping ? (
            <div className="banner warn" style={{ marginTop: 10 }}>
              Убрать проверку звука насовсем? Кнопки исчезнут вместе с этой —
              вернуть их можно будет только новой сборкой.
              <div className="confirmrow">
                <button
                  className="danger"
                  onClick={() => {
                    setChimeTest(false);
                    setDropping(false);
                    void saveChimeTest(false);
                  }}
                >
                  Убрать
                </button>
                <button className="ghost" onClick={() => setDropping(false)}>
                  Оставить
                </button>
              </div>
            </div>
          ) : (
            <button
              className="ghost"
              style={{ marginTop: 10 }}
              onClick={() => setDropping(true)}
            >
              Убрать проверку звука
            </button>
          )}
        </Fold>
      )}

      {/*
        The stop, and only the stop.

        The number it is measured from lives in the balance sheet, where the
        balance it is measured against is — this is the one thing about the
        goal that is a setting: whether hitting it is allowed to take the
        buttons away for the rest of the day.
      */}
      <Fold title="Цель дня">
        <button
          className={`ruletile${dayLock ? ' on' : ''}`}
          onClick={() => onDayLock(!dayLock)}
        >
          <span className={`switch mini ${dayLock ? 'on' : ''}`} />
          <b>блокировка</b>
          <i>×{DAY_MULTIPLE} → стоп до полуночи</i>
        </button>
        <div className="muted balhint" style={{ marginTop: 10 }}>
          {dayLock
            ? 'Когда счёт доходит до цели, покупки блокируются до полуночи.' +
              ' Продажи и выход по лесенке работают всегда.'
            : 'Цель считается и показывается, но покупки не останавливаются.'}
          {dayHit &&
            dayLock &&
            ' Сейчас цель взята — выключите, чтобы торговать дальше сегодня.'}
          {' Сумма, от которой считается цель, — в балансе.'}
        </div>
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
