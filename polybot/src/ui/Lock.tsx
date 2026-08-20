import { useState } from 'react';
import { clearVault } from '../core/storage';

export function Lock({
  onUnlock,
  onForget,
}: {
  onUnlock: (pin: string) => Promise<string | null>;
  onForget: () => void;
}) {
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    setError(await onUnlock(pin));
    setBusy(false);
  };

  const forget = async () => {
    await clearVault();
    onForget();
  };

  return (
    <div className="app">
      <div className="center">
        <h1 style={{ fontSize: 22, marginBottom: 6 }}>PolyBot · BTC 5м</h1>
        <p className="muted" style={{ marginTop: 0, marginBottom: 22 }}>
          Введите PIN, чтобы расшифровать ключ кошелька.
        </p>

        {error && <div className="banner error">{error}</div>}

        <label className="field">
          <span>PIN</span>
          <input
            type="password"
            inputMode="numeric"
            value={pin}
            autoFocus
            onChange={(e) => setPin(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && pin.length >= 4) void submit();
            }}
          />
        </label>

        <button
          className="primary"
          disabled={busy || pin.length < 4}
          onClick={() => void submit()}
        >
          {busy ? 'Проверяем…' : 'Разблокировать'}
        </button>

        <button
          className="danger"
          style={{ marginTop: 10 }}
          onClick={() => void forget()}
        >
          Забыть кошелёк
        </button>
      </div>
    </div>
  );
}
