export type LogLevel = 'info' | 'trade' | 'warn' | 'error';

export type LogEntry = {
  id: number;
  at: number;
  level: LogLevel;
  message: string;
  detail?: string;
};

const MAX_ENTRIES = 500;

class Logger {
  private entries: LogEntry[] = [];
  private nextId = 1;
  private listeners = new Set<(entries: LogEntry[]) => void>();

  subscribe(fn: (entries: LogEntry[]) => void): () => void {
    this.listeners.add(fn);
    fn(this.entries);
    return () => this.listeners.delete(fn);
  }

  get all(): LogEntry[] {
    return this.entries;
  }

  add(level: LogLevel, message: string, detail?: unknown): void {
    const entry: LogEntry = {
      id: this.nextId++,
      at: Date.now(),
      level,
      message,
      detail: detail === undefined ? undefined : stringify(detail),
    };
    this.entries = [entry, ...this.entries].slice(0, MAX_ENTRIES);
    for (const fn of this.listeners) fn(this.entries);
  }

  info = (m: string, d?: unknown) => this.add('info', m, d);
  trade = (m: string, d?: unknown) => this.add('trade', m, d);
  warn = (m: string, d?: unknown) => this.add('warn', m, d);
  error = (m: string, d?: unknown) => this.add('error', m, d);

  clear(): void {
    this.entries = [];
    for (const fn of this.listeners) fn(this.entries);
  }
}

function stringify(d: unknown): string {
  if (typeof d === 'string') return d;
  if (d instanceof Error) return d.message;
  try {
    return JSON.stringify(d);
  } catch {
    return String(d);
  }
}

export const log = new Logger();
