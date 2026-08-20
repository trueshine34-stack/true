import { useEffect, useState } from 'react';
import { PolyBot, type NativeLog } from '../native/polybot';

export function Logs() {
  const [entries, setEntries] = useState<NativeLog[]>([]);
  const [journalBytes, setJournalBytes] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

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

    const size = () =>
      PolyBot.getJournalSize()
        .then((r) => {
          if (!cancelled) setJournalBytes(r.bytes);
        })
        .catch(() => {});
    void size();

    const poll = setInterval(() => {
      void load();
      void size();
    }, 10_000);
    return () => {
      cancelled = true;
      clearInterval(poll);
      void handle?.remove();
    };
  }, []);

  const exportJournal = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await PolyBot.exportJournal();
      setNotice(`Файл ${r.file} · ${(r.bytes / 1024).toFixed(0)} КБ`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  const clearJournal = async () => {
    await PolyBot.clearJournal();
    setJournalBytes(0);
    setNotice('Журнал на диске очищен');
  };

  return (
    <>
      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner info">{notice}</div>}

      <div className="card">
        <h2>Экспорт</h2>
        <div className="row">
          <span className="label">Записано на диск</span>
          <span className="value">
            {journalBytes === null ? '—' : `${(journalBytes / 1024).toFixed(0)} КБ`}
          </span>
        </div>
        <button
          className="primary"
          style={{ marginTop: 10 }}
          disabled={busy}
          onClick={() => void exportJournal()}
        >
          {busy ? 'Готовим файл…' : 'Выгрузить журнал в файл'}
        </button>
        <button
          className="ghost"
          style={{ marginTop: 10 }}
          onClick={() => void clearJournal()}
        >
          Очистить журнал на диске
        </button>
        <p className="muted" style={{ fontSize: 12, margin: '10px 0 0' }}>
          Откроется меню «Поделиться» — файл можно отправить куда угодно. Внутри
          настройки, калибровка модели и по строке на каждое закрытое окно с
          ценами, преимуществом, комиссиями и результатом.
        </p>
      </div>

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
    </>
  );
}
