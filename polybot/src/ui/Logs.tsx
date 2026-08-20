import { useEffect, useState } from 'react';
import { PolyBot, type NativeLog } from '../native/polybot';

export function Logs() {
  const [entries, setEntries] = useState<NativeLog[]>([]);

  useEffect(() => {
    let handle: { remove: () => Promise<void> } | null = null;
    let cancelled = false;

    const load = () =>
      PolyBot.getLogs()
        .then((r) => {
          if (!cancelled) setEntries(r.entries);
        })
        .catch(() => {
          /* service not up yet */
        });

    void load();
    void PolyBot.addListener('log', (entry) => {
      // Prepend rather than reloading: the journal is newest-first and the
      // service may have trimmed its tail since the last full read.
      setEntries((prev) => [entry, ...prev.filter((e) => e.id !== entry.id)]);
    }).then((h) => {
      if (cancelled) void h.remove();
      else handle = h;
    });

    const poll = setInterval(load, 10_000);
    return () => {
      cancelled = true;
      clearInterval(poll);
      void handle?.remove();
    };
  }, []);

  return (
    <div className="card">
      <h2>Журнал сервиса</h2>
      {entries.length === 0 && (
        <div className="muted">
          Пока пусто. Записи появятся, когда бот будет запущен.
        </div>
      )}
      {entries.map((e) => (
        <div className={`logline ${e.level}`} key={e.id}>
          <time>
            {new Date(e.at).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            })}
          </time>
          <div>{e.message}</div>
        </div>
      ))}
    </div>
  );
}
