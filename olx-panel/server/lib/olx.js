/**
 * Klient OLX.pl Partner API (https://developer.olx.pl).
 * Obsluguje OAuth2 (client_credentials oraz refresh_token), cache tokenu i mapowanie ogloszenia.
 */
import { config, hasOlxCredentials } from './config.js';
import { read, write, log } from './store.js';

const TOKEN_SKEW_MS = 60_000;

async function requestToken(body) {
  const res = await fetch(config.olx.tokenUrl, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  if (!res.ok) {
    throw new Error(`OAuth ${res.status}: ${data.error_description || data.error || text.slice(0, 300)}`);
  }
  return data;
}

export async function getAccessToken({ force = false } = {}) {
  if (!hasOlxCredentials()) throw new Error('Brak OLX_CLIENT_ID / OLX_CLIENT_SECRET w .env');
  const cached = read('tokens');
  if (!force && cached.accessToken && cached.expiresAt > Date.now() + TOKEN_SKEW_MS) {
    return cached.accessToken;
  }

  const refreshToken = cached.refreshToken || config.olx.refreshToken;
  let data;
  if (refreshToken) {
    try {
      data = await requestToken({
        grant_type: 'refresh_token',
        client_id: config.olx.clientId,
        client_secret: config.olx.clientSecret,
        refresh_token: refreshToken,
      });
    } catch (err) {
      log('warn', 'Odswiezenie tokenu nie powiodlo sie, probuje client_credentials', { error: err.message });
    }
  }
  if (!data) {
    data = await requestToken({
      grant_type: 'client_credentials',
      client_id: config.olx.clientId,
      client_secret: config.olx.clientSecret,
      scope: config.olx.scope,
    });
  }

  write('tokens', {
    accessToken: data.access_token,
    refreshToken: data.refresh_token || refreshToken || '',
    expiresAt: Date.now() + (Number(data.expires_in || 3600) * 1000),
  });
  return data.access_token;
}

/** Wymienia kod autoryzacyjny (flow "authorization_code") na tokeny. */
export async function exchangeAuthorizationCode(code, redirectUri) {
  const data = await requestToken({
    grant_type: 'authorization_code',
    client_id: config.olx.clientId,
    client_secret: config.olx.clientSecret,
    code,
    redirect_uri: redirectUri,
    scope: config.olx.scope,
  });
  write('tokens', {
    accessToken: data.access_token,
    refreshToken: data.refresh_token || '',
    expiresAt: Date.now() + (Number(data.expires_in || 3600) * 1000),
  });
  return data;
}

export function authorizeUrl(redirectUri, state = '') {
  const params = new URLSearchParams({
    client_id: config.olx.clientId,
    response_type: 'code',
    scope: config.olx.scope,
    redirect_uri: redirectUri,
  });
  if (state) params.set('state', state);
  return `https://www.olx.pl/oauth/authorize/?${params}`;
}

export async function api(path, { method = 'GET', body, query, retry = true } = {}) {
  const token = await getAccessToken();
  const url = new URL(path.startsWith('http') ? path : `${config.olx.baseUrl}${path}`);
  for (const [k, v] of Object.entries(query || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
  }

  const res = await fetch(url, {
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

  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!res.ok) {
    const detail = data?.error?.detail || data?.detail || data?.message || text.slice(0, 500);
    const validation = data?.error?.validation || data?.validation;
    const err = new Error(`OLX API ${res.status}: ${detail}`);
    err.status = res.status;
    err.validation = validation;
    throw err;
  }
  return data.data !== undefined ? data.data : data;
}

export const olx = {
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

/**
 * Mapuje nasze ogloszenie na payload ogloszenia OLX.
 * @param {object} listing ogloszenie po formatowaniu (ma .formatted i .priced)
 * @param {object} settings ustawienia panelu
 */
export function toAdvertPayload(listing, settings) {
  const { formatted = {}, priced = {} } = listing;
  const location = { ...settings.location, ...listing.location };
  const contact = { ...settings.contact, ...listing.contact };

  const payload = {
    title: formatted.title || listing.title,
    description: formatted.description || listing.description,
    category_id: Number(listing.olxCategoryId || listing.categoryId),
    advertiser_type: listing.advertiserType || settings.advertiserType || 'private',
    external_id: listing.id,
    contact: { name: contact.name || undefined, phone: contact.phone || undefined },
    location: {
      city_id: location.cityId ? Number(location.cityId) : undefined,
      district_id: location.districtId ? Number(location.districtId) : undefined,
      latitude: location.lat ?? undefined,
      longitude: location.lon ?? undefined,
    },
    images: (listing.images || []).map((url) => ({ url })),
    price: {
      value: Number(priced.price ?? listing.basePrice ?? 0),
      currency: priced.currency || 'PLN',
      negotiable: priced.negotiable !== false,
      trade: false,
      budget: false,
    },
    attributes: Object.entries(listing.olxAttributes || {})
      .filter(([, v]) => v !== '' && v !== null && v !== undefined)
      .map(([code, value]) => (Array.isArray(value) ? { code, values: value } : { code, value: String(value) })),
  };

  if (!payload.contact.name) delete payload.contact.name;
  if (!payload.contact.phone) delete payload.contact.phone;
  if (!Object.keys(payload.contact).length) delete payload.contact;
  for (const key of Object.keys(payload.location)) {
    if (payload.location[key] === undefined) delete payload.location[key];
  }
  return payload;
}

/** Sprawdza payload lokalnie - taniej niz dostac 400 z API. */
export function validatePayload(payload) {
  const errors = [];
  if (!payload.title || payload.title.length < 6) errors.push('Tytul musi mieć min. 6 znaków');
  if (payload.title && payload.title.length > 70) errors.push('Tytul dłuższy niż 70 znaków');
  if (!payload.description || payload.description.length < 30) errors.push('Opis musi mieć min. 30 znaków');
  if (!payload.category_id) errors.push('Brak category_id (kategoria OLX)');
  if (!payload.location?.city_id) errors.push('Brak city_id (miasto)');
  if (!payload.images?.length) errors.push('Brak zdjęć');
  if (!(payload.price?.value > 0)) errors.push('Cena musi być większa od 0');
  return errors;
}
