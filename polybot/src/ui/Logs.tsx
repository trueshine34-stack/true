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

  /**
   * The journal as text, on the clipboard.
   *
   * Newest last, so a pasted excerpt reads in the order things happened rather
   * than the order the screen shows them.
   */
  const copyJournal = async () => {
    const text = [...entries]
      .reverse()
      .map(
        (e) =>
          `${new Date(e.at).toLocaleTimeString([], {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
          })} ${e.level.padEnd(5)} ${e.message}`,
      )
      .join('\n');

    if (!text) {
      setNotice('Журнал пуст');
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      setNotice(`Скопировано строк: ${entries.length}`);
    } catch {
      // Some WebViews refuse the async clipboard; the old way still works.
      const area = document.createElement('textarea');
      area.value = text;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(area);
      setNotice(ok ? `Скопировано строк: ${entries.length}` : 'Не вышло скопировать');
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

      </div>

    <div className="card">
      <div className="listhead">
        <span>Журнал сервиса</span>
        <button className="linkbtn" onClick={() => void copyJournal()}>
          скопировать ({entries.length})
        </button>
      </div>
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
