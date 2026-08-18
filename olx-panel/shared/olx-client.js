/**
 * Klient OLX.pl Partner API bez zaleznosci od srodowiska.
 * Transport HTTP i miejsce na tokeny wstrzykuje wolajacy:
 *   - serwer Node podaje fetch i plik JSON,
 *   - aplikacja Android podaje natywny most i pamiec telefonu.
 */

const TOKEN_SKEW_MS = 60_000;

export const DEFAULT_ENDPOINTS = {
  baseUrl: 'https://www.olx.pl/api/partner',
  tokenUrl: 'https://www.olx.pl/api/open/oauth/token',
  authorizeUrl: 'https://www.olx.pl/oauth/authorize',
  scope: 'v2 read write',
};

/**
 * @param {object} deps
 * @param {(url: string, init: object) => Promise<{status:number, text:string}>} deps.request transport HTTP
 * @param {{read: () => object, write: (value: object) => void}} deps.tokens pamiec tokenow
 * @param {() => object} deps.credentials zwraca { clientId, clientSecret, scope, baseUrl, tokenUrl }
 * @param {(level: string, message: string, meta?: object) => void} [deps.log]
 */
export function createOlxClient({ request, tokens, credentials, log = () => {} }) {
  const conf = () => ({ ...DEFAULT_ENDPOINTS, ...credentials() });

  const hasCredentials = () => {
    const { clientId, clientSecret } = conf();
    return Boolean(clientId && clientSecret);
  };

  async function requestToken(body) {
    const { tokenUrl } = conf();
    const res = await request(tokenUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify(body),
    });
    let data;
    try { data = JSON.parse(res.text); } catch { data = { raw: res.text }; }
    if (res.status < 200 || res.status >= 300) {
      throw new Error(`OAuth ${res.status}: ${data.error_description || data.error || String(res.text).slice(0, 300)}`);
    }
    return data;
  }

  function storeTokens(data, fallbackRefresh = '') {
    const saved = {
      accessToken: data.access_token,
      refreshToken: data.refresh_token || fallbackRefresh || '',
      expiresAt: Date.now() + (Number(data.expires_in || 3600) * 1000),
    };
    tokens.write(saved);
    return saved;
  }

  async function getAccessToken({ force = false } = {}) {
    const { clientId, clientSecret, scope, refreshToken: configuredRefresh } = conf();
    if (!hasCredentials()) throw new Error('Nie podano client_id / client_secret OLX');

    const cached = tokens.read() || {};
    if (!force && cached.accessToken && cached.expiresAt > Date.now() + TOKEN_SKEW_MS) {
      return cached.accessToken;
    }

    const refreshToken = cached.refreshToken || configuredRefresh || '';
    let data = null;
    if (refreshToken) {
      try {
        data = await requestToken({
          grant_type: 'refresh_token',
          client_id: clientId,
          client_secret: clientSecret,
          refresh_token: refreshToken,
        });
      } catch (err) {
        log('warn', 'Odswiezenie tokenu nie powiodlo sie, probuje client_credentials', { error: err.message });
      }
    }
    if (!data) {
      data = await requestToken({
        grant_type: 'client_credentials',
        client_id: clientId,
        client_secret: clientSecret,
        scope,
      });
    }
    return storeTokens(data, refreshToken).accessToken;
  }

  /** Wymienia kod autoryzacyjny na tokeny (flow "authorization_code"). */
  async function exchangeAuthorizationCode(code, redirectUri) {
    const { clientId, clientSecret, scope } = conf();
    const data = await requestToken({
      grant_type: 'authorization_code',
      client_id: clientId,
      client_secret: clientSecret,
      code,
      redirect_uri: redirectUri,
      scope,
    });
    storeTokens(data);
    return data;
  }

  function authorizeUrl(redirectUri, state = '') {
    const { clientId, scope, authorizeUrl: base } = conf();
    const params = new URLSearchParams({
      client_id: clientId,
      response_type: 'code',
      scope,
      redirect_uri: redirectUri,
    });
    if (state) params.set('state', state);
    return `${base}?${params}`;
  }

  async function api(path, { method = 'GET', body, query, retry = true } = {}) {
    const token = await getAccessToken();
    const { baseUrl } = conf();
    const url = new URL(path.startsWith('http') ? path : `${baseUrl}${path}`);
    for (const [k, v] of Object.entries(query || {})) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
    }

    const res = await request(url.toString(), {
      method,
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/json',
        accept: 'application/json',
        version: '2.0',
      },
      body: body ? JSON.stringify(body) : undefined,
    });

    if (res.status === 401 && retry) {
      await getAccessToken({ force: true });
      return api(path, { method, body, query, retry: false });
    }

    let data;
    try { data = res.text ? JSON.parse(res.text) : {}; } catch { data = { raw: res.text }; }
    if (res.status < 200 || res.status >= 300) {
      const detail = data?.error?.detail || data?.detail || data?.message || String(res.text).slice(0, 500);
      const err = new Error(`OLX API ${res.status}: ${detail}`);
      err.status = res.status;
      err.validation = data?.error?.validation || data?.validation;
      throw err;
    }
    return data.data !== undefined ? data.data : data;
  }

  return {
    hasCredentials,
    getAccessToken,
    exchangeAuthorizationCode,
    authorizeUrl,
    api,
    me: () => api('/users/me'),
    categories: (parentId) => api('/categories', { query: { parent_id: parentId } }),
    categoryAttributes: (categoryId) => api(`/categories/${categoryId}/attributes`),
    cities: (q) => api('/cities', { query: { name: q, limit: 20 } }),
    districts: (cityId) => api('/districts', { query: { city_id: cityId } }),
    adverts: (query) => api('/adverts', { query }),
    getAdvert: (id) => api(`/adverts/${id}`),
    createAdvert: (payload) => api('/adverts', { method: 'POST', body: payload }),
    updateAdvert: (id, payload) => api(`/adverts/${id}`, { method: 'PUT', body: payload }),
    deleteAdvert: (id) => api(`/adverts/${id}`, { method: 'DELETE' }),
    command: (id, command) => api(`/adverts/${id}/commands`, { method: 'POST', body: { command } }),
    activate: (id) => api(`/adverts/${id}/commands`, { method: 'POST', body: { command: 'activate' } }),
    deactivate: (id) => api(`/adverts/${id}/commands`, { method: 'POST', body: { command: 'deactivate' } }),
  };
}
