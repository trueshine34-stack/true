import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** Minimalny parser .env - bez zaleznosci zewnetrznych. */
function loadDotEnv() {
  const file = path.join(ROOT, '.env');
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (/^".*"$/.test(value) || /^'.*'$/.test(value)) value = value.slice(1, -1);
    if (process.env[key] === undefined) process.env[key] = value;
  }
}
loadDotEnv();

export const config = {
  port: Number(process.env.PORT || 8787),
  host: process.env.HOST || '127.0.0.1',
  dataDir: process.env.DATA_DIR || path.join(ROOT, 'data'),
  panelToken: process.env.PANEL_TOKEN || '',
  olx: {
    // Partner API OLX.pl - https://developer.olx.pl
    baseUrl: process.env.OLX_BASE_URL || 'https://www.olx.pl/api/partner',
    tokenUrl: process.env.OLX_TOKEN_URL || 'https://www.olx.pl/api/open/oauth/token',
    clientId: process.env.OLX_CLIENT_ID || '',
    clientSecret: process.env.OLX_CLIENT_SECRET || '',
    refreshToken: process.env.OLX_REFRESH_TOKEN || '',
    scope: process.env.OLX_SCOPE || 'v2 read write',
    country: process.env.OLX_COUNTRY || 'pl',
  },
  importer: {
    userAgent: process.env.IMPORT_USER_AGENT
      || 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36',
    timeoutMs: Number(process.env.IMPORT_TIMEOUT_MS || 20000),
  },
  publisher: {
    // Odstep miedzy publikacjami - chroni przed limitem API i wygladem spamu.
    intervalMs: Number(process.env.PUBLISH_INTERVAL_MS || 45000),
    maxPerRun: Number(process.env.PUBLISH_MAX_PER_RUN || 20),
    dryRun: String(process.env.PUBLISH_DRY_RUN || '') === '1',
  },
};

export const hasOlxCredentials = () =>
  Boolean(config.olx.clientId && config.olx.clientSecret);
