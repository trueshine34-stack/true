import { useCallback, useEffect, useMemo, useState } from 'react';
import { App as CapApp } from '@capacitor/app';
import { Wallet } from 'ethers';
import { BotEngine, type Session } from './bot/engine';
import { DEFAULT_SETTINGS, type StrategySettings } from './bot/strategy';
import { log } from './core/logger';
import {
  hasVault,
  loadAccount,
  loadEncryptedKey,
  loadSettings,
  saveSettings,
  type AccountConfig,
} from './core/storage';
import { createOrDeriveApiCreds } from './polymarket/clob';
import { Dashboard } from './ui/Dashboard';
import { Logs } from './ui/Logs';
import { Lock } from './ui/Lock';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';

type Phase = 'loading' | 'setup' | 'locked' | 'ready';
type Tab = 'dashboard' | 'settings' | 'logs';

export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [tab, setTab] = useState<Tab>('dashboard');
  const [settings, setSettings] = useState<StrategySettings>(DEFAULT_SETTINGS);
  const [session, setSession] = useState<Session | null>(null);
  const [account, setAccount] = useState<AccountConfig | null>(null);

  const engine = useMemo(() => new BotEngine(DEFAULT_SETTINGS), []);

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
      engine.setSettings(stored);
      setAccount(acct);
      setPhase(vault && acct ? 'locked' : 'setup');
      engine.startFeed();
    })();
    return () => {
      cancelled = true;
    };
  }, [engine]);

  // Android suspends WebView sockets while the app is backgrounded. Nudge the
  // feed on the way back so the next window opens with a live price.
  useEffect(() => {
    const handle = CapApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        engine.startFeed();
      } else {
        log.warn('Приложение свёрнуто — фид может прерваться, окна пропускаются');
      }
    });
    return () => {
      void handle.then((h) => h.remove());
    };
  }, [engine]);

  const applySettings = useCallback(
    (next: StrategySettings) => {
      setSettings(next);
      engine.setSettings(next);
      void saveSettings(next);
    },
    [engine],
  );

  const unlock = useCallback(
    async (pin: string): Promise<string | null> => {
      const acct = account ?? (await loadAccount());
      if (!acct) return 'Настройки аккаунта не найдены — подключите кошелёк заново.';

      const privateKey = await loadEncryptedKey(pin);
      if (!privateKey) return 'Неверный PIN.';

      let wallet: Wallet;
      try {
        wallet = new Wallet(privateKey);
      } catch {
        return 'Сохранённый ключ повреждён — подключите кошелёк заново.';
      }

      try {
        const creds = await createOrDeriveApiCreds(wallet);
        const next: Session = {
          wallet,
          creds,
          funderAddress: acct.funderAddress,
          signatureType: acct.signatureType,
        };
        setAccount(acct);
        setSession(next);
        engine.setSession(next);
        setPhase('ready');
        log.info(`Кошелёк подключён: ${short(wallet.address)}`);
        return null;
      } catch (err) {
        return `Не удалось получить ключи CLOB: ${err instanceof Error ? err.message : String(err)}`;
      }
    },
    [account, engine],
  );

  const onSetupDone = useCallback(
    (acct: AccountConfig, newSession: Session) => {
      setAccount(acct);
      setSession(newSession);
      engine.setSession(newSession);
      setPhase('ready');
    },
    [engine],
  );

  const onForget = useCallback(() => {
    engine.stop();
    engine.setSession(null);
    setSession(null);
    setAccount(null);
    setPhase('setup');
  }, [engine]);

  if (phase === 'loading') {
    return (
      <div className="app">
        <div className="center muted">Загрузка…</div>
      </div>
    );
  }

  if (phase === 'setup') {
    return <Setup onDone={onSetupDone} />;
  }

  if (phase === 'locked') {
    return <Lock onUnlock={unlock} onForget={onForget} />;
  }

  return (
    <div className="app">
      <div className="topbar">
        <h1>PolyBot · BTC 5м</h1>
        {session && <span className="pill mono">{short(session.wallet.address)}</span>}
      </div>

      <div className="scroll">
        {tab === 'dashboard' && <Dashboard engine={engine} settings={settings} />}
        {tab === 'settings' && (
          <SettingsScreen
            settings={settings}
            account={account}
            session={session}
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
