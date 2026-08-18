/**
 * Serwerowa oprawa importu: pobieranie stron przez fetch,
 * caly rozbior danych siedzi we wspoldzielonym module.
 */
import { config } from './config.js';
import { importFromUrl as importFromUrlShared } from '../../shared/importer.js';

export async function fetchHtml(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.importer.timeoutMs);
  try {
    const res = await fetch(url, {
      headers: {
        'user-agent': config.importer.userAgent,
        accept: 'text/html,application/xhtml+xml',
        'accept-language': 'pl-PL,pl;q=0.9,en;q=0.8',
      },
      signal: controller.signal,
      redirect: 'follow',
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} dla ${url}`);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

export const importFromUrl = (url, extra = {}) => importFromUrlShared(url, fetchHtml, extra);

export {
  EMPTY_LISTING, normalizeListing, toNumber, extractJsonLd, extractMeta,
  extractOlxState, parseHtml, parseCsv, mapRow, importFromCsv, importFromJson,
} from '../../shared/importer.js';
