import { Wallet } from 'ethers';
import { CLOB_HOST } from '../core/config';
import { createLevel1Headers, createLevel2Headers } from './auth';
import type { ApiCreds, SignatureType } from './types';

/**
 * The setup-time slice of the CLOB API.
 *
 * Trading itself lives in the native service, which signs and posts its own
 * orders. What stays here is only what the UI needs before handing over: the
 * wallet's API credentials, and a balance read that proves the wallet type and
 * funder address line up.
 */

export class ClobError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'ClobError';
  }
}

async function request<T>(
  path: string,
  init: RequestInit & { query?: Record<string, string | number> } = {},
): Promise<T> {
  const url = new URL(CLOB_HOST + path);
  for (const [k, v] of Object.entries(init.query ?? {})) {
    url.searchParams.set(k, String(v));
  }
  const res = await fetch(url.toString(), {
    method: init.method ?? 'GET',
    headers: { Accept: 'application/json', ...(init.headers ?? {}) },
    body: init.body,
  });

  const text = await res.text();
  let parsed: unknown = text;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    /* leave as text */
  }

  if (!res.ok) {
    const detail =
      typeof parsed === 'object' && parsed !== null
        ? JSON.stringify(parsed)
        : String(parsed).slice(0, 300);
    throw new ClobError(`${res.status} ${path}: ${detail}`, res.status);
  }
  return parsed as T;
}

/** Server clock in seconds, used to keep signed timestamps inside tolerance. */
export async function getServerTime(): Promise<number> {
  const r = await request<number | { timestamp: number }>('/time');
  return typeof r === 'number' ? r : Number(r.timestamp);
}

/**
 * Fetch this wallet's CLOB credentials. Creating is idempotent-ish: the server
 * returns the existing key on a repeat call, and we fall back to /derive so a
 * wallet that already has a key never gets locked out.
 */
export async function createOrDeriveApiCreds(wallet: Wallet): Promise<ApiCreds> {
  const shape = (r: {
    apiKey: string;
    secret: string;
    passphrase: string;
  }): ApiCreds => ({
    apiKey: r.apiKey,
    secret: r.secret,
    passphrase: r.passphrase,
  });

  try {
    const headers = await createLevel1Headers(wallet);
    const r = await request<{
      apiKey: string;
      secret: string;
      passphrase: string;
    }>('/auth/api-key', { method: 'POST', headers });
    if (r?.apiKey) return shape(r);
  } catch {
    /* fall through to derive */
  }

  const headers = await createLevel1Headers(wallet);
  const r = await request<{
    apiKey: string;
    secret: string;
    passphrase: string;
  }>('/auth/derive-api-key', { headers });
  if (!r?.apiKey) throw new ClobError('could not create or derive API credentials');
  return shape(r);
}

export type BalanceAllowance = { balance: string };

export async function getBalanceAllowance(
  wallet: Wallet,
  creds: ApiCreds,
  signatureType: SignatureType,
): Promise<BalanceAllowance> {
  const headers = await createLevel2Headers(
    wallet.address,
    creds,
    'GET',
    '/balance-allowance',
  );
  return request<BalanceAllowance>('/balance-allowance', {
    headers,
    query: {
      signature_type: Number(signatureType),
      asset_type: 'COLLATERAL',
    },
  });
}
