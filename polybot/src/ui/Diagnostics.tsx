import { useEffect, useState } from 'react';
import { PolyBot, type DiagnosticCheck } from '../native/polybot';

/**
 * Reachability panel.
 *
 * Polymarket is geo-restricted and routinely blocked by ISPs, which looks
 * exactly like an app bug from the inside. Probing a neutral host alongside the
 * exchange separates "no internet", "Polymarket unreachable from this network"
 * and "the exchange refused us".
 */
export function Diagnostics() {
  const [checks, setChecks] = useState<DiagnosticCheck[] | null>(null);
  const [running, setRunning] = useState(false);

  const run = async () => {
    setRunning(true);
    try {
      const r = await PolyBot.diagnose();
      setChecks(r.checks);
    } catch {
      setChecks([]);
    } finally {
      setRunning(false);
    }
  };

  useEffect(() => {
    void run();
  }, []);

  const control = checks?.find((c) => c.control);
  const exchange = checks?.filter((c) => !c.control) ?? [];
  const exchangeBlocked =
    control?.ok === true && exchange.length > 0 && exchange.every((c) => !c.ok);

  return (
    <div className="card">
      <h2>Проверка сети</h2>

      {running && <div className="muted">Проверяем…</div>}

      {checks?.map((c) => (
        <div className="row" key={c.name}>
          <span className="label">{c.name}</span>
          <span className={`value ${c.ok ? 'up' : 'down'}`}>
            {c.ok ? `доступен · ${c.ms} мс` : 'недоступен'}
          </span>
        </div>
      ))}

      {checks?.some((c) => !c.ok && c.error) && (
        <div className="mono muted" style={{ marginTop: 8 }}>
          {checks
            .filter((c) => !c.ok && c.error)
            .map((c) => `${c.name}: ${c.error}`)
            .join('\n')}
        </div>
      )}

      {exchangeBlocked && (
        <div className="banner warn" style={{ marginTop: 10 }}>
          Интернет есть, но серверы Polymarket с этой сети не открываются. Обычно
          это блокировка провайдером или гео-ограничение — попробуйте другую сеть
          или VPN.
        </div>
      )}

      <button
        className="ghost"
        style={{ marginTop: 10 }}
        disabled={running}
        onClick={() => void run()}
      >
        Проверить снова
      </button>
    </div>
  );
}
