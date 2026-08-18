/**
 * Serwerowa oprawa wspoldzielonego klienta OLX: transport na fetch,
 * tokeny w pliku JSON, dane logowania z .env.
 */
import { config, hasOlxCredentials } from './config.js';
import { read, write, log } from './store.js';
import { createOlxClient } from '../../shared/olx-client.js';

async function request(url, init) {
  const res = await fetch(url, init);
  return { status: res.status, text: await res.text() };
}

export const olx = createOlxClient({
  request,
  tokens: {
    read: () => read('tokens'),
    write: (value) => write('tokens', value),
  },
  credentials: () => ({
    clientId: config.olx.clientId,
    clientSecret: config.olx.clientSecret,
    refreshToken: config.olx.refreshToken,
    scope: config.olx.scope,
    baseUrl: config.olx.baseUrl,
    tokenUrl: config.olx.tokenUrl,
  }),
  log,
});

export const getAccessToken = olx.getAccessToken;
export const exchangeAuthorizationCode = olx.exchangeAuthorizationCode;
export const authorizeUrl = olx.authorizeUrl;
export const api = olx.api;

export { toAdvertPayload, validatePayload } from '../../shared/olx-payload.js';
export { hasOlxCredentials };
