import { useCallback, useEffect, useState } from 'react';
import type { AccountConfig } from './core/account';
import { DEFAULT_SETTINGS, type StrategySettings } from './core/settings';
import { loadAccount, loadSettings, saveSettings } from './core/storage';
import { PolyBot } from './native/polybot';
import { Dashboard } from './ui/Dashboard';
import { Manual } from './ui/Manual';
import { Pair } from './ui/Pair';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';

type Phase = 'loading' | 'setup' | 'ready';
type Tab = 'dashboard' | 'manual' | 'pair' | 'settings';

export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [tab, setTab] = useState<Tab>('dashboard');
  const [settings, setSettings] = useState<StrategySettings>(DEFAULT_SETTINGS);
  const [account, setAccount] = useState<AccountConfig | null>(null);
  const [balance, setBalance] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [stored, acct] = await Promise.all([loadSettings(), loadAccount()]);
      if (cancelled) return;
      setSettings(stored);
      setAccount(acct);

      // The service outlives the UI, so a bot started earlier is still trading;
      // rejoin it rather than reconnecting over a running engine.
      const state = await PolyBot.getState().catch(() => null);
      if (!cancelled && state?.serviceAlive && acct) {
        setPhase('ready');
        return;
      }
      if (!acct) {
        if (!cancelled) setPhase('setup');
        return;
      }

      // The key is sealed by the Android Keystore, so it opens without anything
      // from the user. A vault that cannot be opened — reinstalled app, cleared
      // keystore — means the key is genuinely gone and has to be entered again.
      const vault = await PolyBot.vaultLoad().catch(() => ({ privateKey: null }));
      if (cancelled) return;
      if (!vault.privateKey) {
        setPhase('setup');
        return;
      }
      try {
        await PolyBot.connect({
          privateKey: vault.privateKey,
          funderAddress: acct.funderAddress,
          signatureType: Number(acct.signatureType),
          settings: stored,
        });
        if (!cancelled) setPhase('ready');
      } catch {
        if (!cancelled) setPhase('setup');
      }
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

  const onSetupDone = useCallback((acct: AccountConfig) => {
    setAccount(acct);
    setPhase('ready');
  }, []);

  /** Tapping a position on the terminal hands it to the manual desk. */
  const onSellPosition = useCallback(() => {
    setTab('manual');
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
        {tab === 'manual' && <Manual />}
        {tab === 'pair' && <Pair />}
        {tab === 'settings' && (
          <SettingsScreen
            settings={settings}
            account={account}
            onChange={applySettings}
            onForget={onForget}
          />
        )}
      </div>

      <nav className="tabs">
        <button
          className={tab === 'dashboard' ? 'active' : ''}
          onClick={() => setTab('dashboard')}
        >
          Терминал
        </button>
        <button
          className={tab === 'manual' ? 'active' : ''}
          onClick={() => setTab('manual')}
        >
          Руки
        </button>
        <button className={tab === 'pair' ? 'active' : ''} onClick={() => setTab('pair')}>
          Пара
        </button>
        <button
          className={tab === 'settings' ? 'active' : ''}
          onClick={() => setTab('settings')}
        >
          Настройки
        </button>
      </nav>
    </div>
  );
}

function short(address: string): string {
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}
