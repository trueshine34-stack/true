import { useEffect, useState } from 'react';
import { log, type LogEntry } from '../core/logger';

export function Logs() {
  const [entries, setEntries] = useState<LogEntry[]>([]);

  useEffect(() => log.subscribe(setEntries), []);

  return (
    <>
      <div className="card">
        <h2>Журнал</h2>
        {entries.length === 0 && <div className="muted">Пока пусто.</div>}
        {entries.map((e) => (
          <div className={`logline ${e.level}`} key={e.id}>
            <time>
              {new Date(e.at).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
              })}
            </time>
            <div>
              <div>{e.message}</div>
              {e.detail && (
                <div className="mono muted" style={{ marginTop: 3 }}>
                  {e.detail.slice(0, 400)}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <button className="ghost" onClick={() => log.clear()}>
        Очистить журнал
      </button>
    </>
  );
}
