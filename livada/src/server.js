import http from 'node:http';
import { config } from './config.js';
import { openDb, closeDb, one } from './db/index.js';
import { seedAll } from './db/seed.js';
import { createApp } from './app.js';
import { pruneSessions } from './services/users.js';

openDb();
// primul start: catalog, lockere, setări și contul de admin; în development și comenzi demo pentru panou
const firstRun = one('SELECT COUNT(*) n FROM products').n === 0;
await seedAll({ demo: firstRun && (!config.production || process.env.SEED_DEMO === '1') });

const server = http.createServer(createApp());
server.keepAliveTimeout = 65_000;
server.headersTimeout = 66_000;
server.requestTimeout = 30_000;

server.listen(config.port, config.host, () => {
  console.log(`[livada] ${config.production ? 'production' : 'development'} → ${config.publicUrl}  (ascult pe ${config.host}:${config.port})`);
  if (!config.smtp.host) console.log('[livada] SMTP neconfigurat — emailurile se salvează în var/outbox (vezi /dev/mail).');
});

const cleanup = setInterval(() => { try { pruneSessions(); } catch (e) { console.error(e); } }, 6 * 3600 * 1000);
cleanup.unref();

function shutdown(sig) {
  console.log(`[livada] ${sig} — opresc serverul`);
  server.close(() => { closeDb(); process.exit(0); });
  setTimeout(() => process.exit(1), 8000).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
