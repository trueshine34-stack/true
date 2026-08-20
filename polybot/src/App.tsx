import { useCallback, useEffect, useState } from 'react';
import { Wallet } from 'ethers';
import { syncClock } from './core/clock';
import { DEFAULT_SETTINGS, type StrategySettings } from './core/settings';
import {
  hasVault,
  loadAccount,
  loadEncryptedKey,
  loadSettings,
  saveSettings,
  type AccountConfig,
} from './core/storage';
import { PolyBot } from './native/polybot';
import { createOrDeriveApiCreds, getServerTime } from './polymarket/clob';
import type { ApiCreds } from './polymarket/types';
import { Dashboard } from './ui/Dashboard';
import { Logs } from './ui/Logs';
import { Lock } from './ui/Lock';
import { SettingsScreen } from './ui/Settings';
import { Setup } from './ui/Setup';

type Phase = 'loading' | 'setup' | 'locked' | 'ready';
type Tab = 'dashboard' | 'settings' | 'logs';

/** What the UI keeps after unlocking. The private key is not among it. */
export type Session = {
  address: string;
  creds: ApiCreds;
  account: AccountConfig;
};

export function App() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [tab, setTab] = useState<Tab>('dashboard');
  const [settings, setSettings] = useState<StrategySettings>(DEFAULT_SETTINGS);
  const [session, setSession] = useState<Session | null>(null);
  const [account, setAccount] = useState<AccountConfig | null>(null);

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
      // rejoin it instead of showing the lock screen over a running engine.
      const state = await PolyBot.getState().catch(() => null);
      if (!cancelled && state?.serviceAlive && acct) {
        setSession({ address: acct.signerAddress, creds: EMPTY_CREDS, account: acct });
        setPhase('ready');
        return;
      }

      if (!cancelled) setPhase(vault && acct ? 'locked' : 'setup');
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const applySettings = useCallback((next: StrategySettings) => {
    setSettings(next);
    void saveSettings(next);
    void PolyBot.updateSettings({ settings: next }).catch(() => {
      /* service not up yet; configure() will carry these */
    });
  }, []);

  const handoff = useCallback(
    async (
      privateKey: string,
      acct: AccountConfig,
      creds: ApiCreds,
      current: StrategySettings,
    ) => {
      await PolyBot.configure({
        privateKey,
        signerAddress: acct.signerAddress,
        funderAddress: acct.funderAddress,
        signatureType: Number(acct.signatureType),
        apiKey: creds.apiKey,
        secret: creds.secret,
        passphrase: creds.passphrase,
        settings: current,
      });
    },
    [],
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
        await syncClock(getServerTime);
      } catch {
        /* the native service syncs its own clock as well */
      }

      try {
        const creds = await createOrDeriveApiCreds(wallet);
        await handoff(privateKey, acct, creds, settings);
        setAccount(acct);
        setSession({ address: wallet.address, creds, account: acct });
        setPhase('ready');
        return null;
      } catch (err) {
        return `Не удалось получить ключи CLOB: ${
          err instanceof Error ? err.message : String(err)
        }`;
      }
    },
    [account, handoff, settings],
  );

  const onSetupDone = useCallback(
    async (privateKey: string, acct: AccountConfig, creds: ApiCreds) => {
      await handoff(privateKey, acct, creds, settings);
      setAccount(acct);
      setSession({ address: acct.signerAddress, creds, account: acct });
      setPhase('ready');
    },
    [handoff, settings],
  );

  const onForget = useCallback(() => {
    void PolyBot.stop().catch(() => {});
    setSession(null);
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
    return <Setup onDone={onSetupDone} />;
  }

  if (phase === 'locked') {
    return <Lock onUnlock={unlock} onForget={onForget} />;
  }

  return (
    <div className="app">
      <div className="topbar">
        <h1>PolyBot · BTC 5м</h1>
        {session && <span className="pill mono">{short(session.address)}</span>}
      </div>

      <div className="scroll">
        {tab === 'dashboard' && <Dashboard settings={settings} />}
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

/** Placeholder for a rejoined session: the service already holds the real ones. */
const EMPTY_CREDS: ApiCreds = { apiKey: '', secret: '', passphrase: '' };

function short(address: string): string {
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}
