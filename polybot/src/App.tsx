import { useCallback, useEffect, useState } from 'react';
import type { AccountConfig } from './core/account';
import { DEFAULT_SETTINGS, type StrategySettings } from './core/settings';
import {
  hasVault,
  loadAccount,
  loadEncryptedKey,
  loadSettings,
  saveSettings,
} from './core/storage';
import { PolyBot, type NativePosition } from './native/polybot';
import { Dashboard } from './ui/Dashboard';
import { Logs } from './ui/Logs';
import { Orders, type SellPrefill } from './ui/Orders';
import { Pair } from './ui/Pair';
import { Lock } from './ui/Lock';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';

type Phase = 'loading' | 'setup' | 'locked' | 'ready';
type Tab = 'dashboard' | 'pair' | 'orders' | 'settings' | 'logs';

export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [tab, setTab] = useState<Tab>('dashboard');
  const [settings, setSettings] = useState<StrategySettings>(DEFAULT_SETTINGS);
  const [account, setAccount] = useState<AccountConfig | null>(null);
  const [sellPrefill, setSellPrefill] = useState<SellPrefill | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [stored, acct, vault] = await Promise.all([
        loadSettings(),
        loadAccount(),
        hasVault(),
      ]);
      if (cancelled) return;
      setSettings(stored);
      setAccount(acct);

      // The service outlives the UI, so a bot started earlier is still trading;
      // rejoin it instead of showing a lock screen over a running engine.
      const state = await PolyBot.getState().catch(() => null);
      if (!cancelled && state?.serviceAlive && acct) {
        setPhase('ready');
        return;
      }
      if (!cancelled) setPhase(vault && acct ? 'locked' : 'setup');
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Wallet balance in the header. It only moves when an order fills, so a slow
  // poll is enough — and it must not run before the engine holds credentials.
  useEffect(() => {
    if (phase !== 'ready') return;
    let cancelled = false;

    const read = () => {
      void PolyBot.getBalance()
        .then((r) => {
          if (!cancelled) setBalance(r.usdc);
        })
        .catch(() => {
          // Offline or not connected yet; keep the last known figure.
        });
    };

    read();
    const timer = window.setInterval(read, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [phase]);

  const applySettings = useCallback((next: StrategySettings) => {
    setSettings(next);
    void saveSettings(next);
    void PolyBot.updateSettings({ settings: next }).catch(() => {
      // Not connected yet; connect() carries the settings across.
    });
  }, []);

  const unlock = useCallback(
    async (pin: string): Promise<string | null> => {
      const acct = account ?? (await loadAccount());
      if (!acct) return 'Настройки аккаунта не найдены — подключите кошелёк заново.';

      const privateKey = await loadEncryptedKey(pin);
      if (!privateKey) return 'Неверный PIN.';

      try {
        await PolyBot.connect({
          privateKey,
          funderAddress: acct.funderAddress,
          signatureType: Number(acct.signatureType),
          settings,
        });
        setAccount(acct);
        setPhase('ready');
        return null;
      } catch (err) {
        return err instanceof Error ? err.message : String(err);
      }
    },
    [account, settings],
  );

  const onSetupDone = useCallback((acct: AccountConfig) => {
    setAccount(acct);
    setPhase('ready');
  }, []);

  /** Tapping a position opens the order desk with that position teed up. */
  const onSellPosition = useCallback((position: NativePosition) => {
    setSellPrefill({
      tokenId: position.asset,
      conditionId: position.conditionId,
      outcome: position.outcome === 'Up' ? 'Up' : 'Down',
      size: position.size,
      price: 0.9,
    });
    setTab('orders');
  }, []);

  const onForget = useCallback(() => {
    void PolyBot.stop().catch(() => {});
    setAccount(null);
    setPhase('setup');
  }, []);

  if (phase === 'loading') {
    return (
      <div className="app">
        <div className="center muted">Загрузка…</div>
      </div>
    );
  }

  if (phase === 'setup') {
    return <Setup settings={settings} onDone={onSetupDone} />;
  }

  if (phase === 'locked') {
    return <Lock onUnlock={unlock} onForget={onForget} />;
  }

  return (
    <div className="app">
      <div className="topbar">
        <h1>PolyBot · BTC 5м</h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <span className="pill balance">
            {balance === null ? '— $' : `${balance.toFixed(2)} $`}
          </span>
          {account && <span className="pill mono">{short(account.signerAddress)}</span>}
        </div>
      </div>

      <div className="scroll">
        {tab === 'dashboard' && (
          <Dashboard settings={settings} onSellPosition={onSellPosition} />
        )}
        {tab === 'pair' && <Pair />}
        {tab === 'orders' && (
          <Orders prefill={sellPrefill} onPrefillUsed={() => setSellPrefill(null)} />
        )}
        {tab === 'settings' && (
          <SettingsScreen
            settings={settings}
            account={account}
            onChange={applySettings}
            onForget={onForget}
          />
        )}
        {tab === 'logs' && <Logs />}
      </div>

      <nav className="tabs">
        <button
          className={tab === 'dashboard' ? 'active' : ''}
          onClick={() => setTab('dashboard')}
        >
          Терминал
        </button>
        <button className={tab === 'pair' ? 'active' : ''} onClick={() => setTab('pair')}>
          Пара
        </button>
        <button
          className={tab === 'orders' ? 'active' : ''}
          onClick={() => setTab('orders')}
        >
          Ордера
        </button>
        <button
          className={tab === 'settings' ? 'active' : ''}
          onClick={() => setTab('settings')}
        >
          Настройки
        </button>
        <button className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>
          Журнал
        </button>
      </nav>
    </div>
  );
}

function short(address: string): string {
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}
