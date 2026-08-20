import { Wallet } from 'ethers';
import { CLOB_HOST } from '../core/config';
import { createLevel1Headers, createLevel2Headers } from './auth';
import {
  buildAndSignOrder,
  limitOrderAmounts,
  marketOrderAmounts,
  roundConfigFor,
} from './orders';
import type {
  ApiCreds,
  OrderBook,
  OrderType,
  PostOrderResult,
  Side,
  SignatureType,
} from './types';

export class ClobError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly body?: unknown,
  ) {
    super(message);
    this.name = 'ClobError';
  }
}

async function request<T>(
  path: string,
  init: RequestInit & { query?: Record<string, string | number | boolean> } = {},
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
    throw new ClobError(`${res.status} ${path}: ${detail}`, res.status, parsed);
  }
  return parsed as T;
}

export async function getClobVersion(): Promise<number> {
  const r = await request<{ version: number }>('/version');
  return r.version;
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

export function getBook(tokenId: string): Promise<OrderBook> {
  return request<OrderBook>('/book', { query: { token_id: tokenId } });
}

export async function getTickSize(tokenId: string): Promise<number> {
  const r = await request<{ minimum_tick_size: number }>('/tick-size', {
    query: { token_id: tokenId },
  });
  return r.minimum_tick_size;
}

export async function getNegRisk(tokenId: string): Promise<boolean> {
  const r = await request<{ neg_risk: boolean }>('/neg-risk', {
    query: { token_id: tokenId },
  });
  return r.neg_risk;
}

export type ClobMarket = {
  condition_id: string;
  question: string;
  market_slug: string;
  accepting_orders: boolean;
  minimum_order_size: number;
  minimum_tick_size: number;
  neg_risk: boolean;
  closed: boolean;
  tokens: { token_id: string; outcome: string; price: number; winner: boolean }[];
};

export function getMarket(conditionId: string): Promise<ClobMarket> {
  return request<ClobMarket>(`/markets/${conditionId}`);
}

/**
 * Price at which a marketable order of `amount` fully fills, walking the book
 * from the best level inward. `amount` is USDC for a BUY, shares for a SELL.
 *
 * Returns null when the book cannot absorb the order and the order type is
 * FOK, since such an order would be rejected outright.
 */
export function calculateMarketPrice(
  book: OrderBook,
  side: Side,
  amount: number,
  orderType: OrderType,
): number | null {
  const levels =
    side === 'BUY'
      ? [...(book.asks ?? [])].sort((a, b) => Number(a.price) - Number(b.price))
      : [...(book.bids ?? [])].sort((a, b) => Number(b.price) - Number(a.price));

  if (levels.length === 0) return null;

  let total = 0;
  for (const level of levels) {
    const price = Number(level.price);
    const size = Number(level.size);
    total += side === 'BUY' ? size * price : size;
    if (total >= amount) return price;
  }

  // Not enough depth. FAK/GTC can still rest or partially fill at the far
  // touch; FOK would be killed, so refuse it here.
  return orderType === 'FOK' ? null : Number(levels[levels.length - 1].price);
}

export type PostMarketOrderArgs = {
  wallet: Wallet;
  creds: ApiCreds;
  funder: string;
  signatureType: SignatureType;
  tokenId: string;
  side: Side;
  /** USDC for BUY, shares for SELL. */
  amount: number;
  price: number;
  tickSize: number;
  negRisk: boolean;
  orderType?: OrderType;
};

export async function postMarketOrder(
  args: PostMarketOrderArgs,
): Promise<PostOrderResult> {
  const cfg = roundConfigFor(args.tickSize);
  const { makerAmount, takerAmount } = marketOrderAmounts(
    args.side,
    args.amount,
    args.price,
    cfg,
  );
  return postSignedOrder(args, makerAmount, takerAmount, args.orderType ?? 'FOK');
}

export type PostLimitOrderArgs = Omit<PostMarketOrderArgs, 'amount'> & {
  /** Always shares, both sides. */
  size: number;
};

export async function postLimitOrder(
  args: PostLimitOrderArgs,
): Promise<PostOrderResult> {
  const cfg = roundConfigFor(args.tickSize);
  const { makerAmount, takerAmount } = limitOrderAmounts(
    args.side,
    args.size,
    args.price,
    cfg,
  );
  return postSignedOrder(args, makerAmount, takerAmount, args.orderType ?? 'GTC');
}

async function postSignedOrder(
  args: Omit<PostMarketOrderArgs, 'amount' | 'price'>,
  makerAmount: string,
  takerAmount: string,
  orderType: OrderType,
): Promise<PostOrderResult> {
  const order = await buildAndSignOrder({
    wallet: args.wallet,
    funder: args.funder,
    signatureType: args.signatureType,
    tokenId: args.tokenId,
    side: args.side,
    makerAmount,
    takerAmount,
    negRisk: args.negRisk,
  });

  const payload = {
    order,
    owner: args.creds.apiKey,
    orderType,
    deferExec: false,
    postOnly: false,
  };

  // The HMAC covers the exact bytes on the wire, so serialize once and reuse.
  const serialized = JSON.stringify(payload);
  const headers = await createLevel2Headers(
    args.wallet.address,
    args.creds,
    'POST',
    '/order',
    serialized,
  );

  return request<PostOrderResult>('/order', {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: serialized,
  });
}

export type BalanceAllowance = {
  balance: string;
  allowances?: Record<string, string>;
};

export async function getBalanceAllowance(
  wallet: Wallet,
  creds: ApiCreds,
  signatureType: SignatureType,
  assetType: 'COLLATERAL' | 'CONDITIONAL' = 'COLLATERAL',
  tokenId?: string,
): Promise<BalanceAllowance> {
  const headers = await createLevel2Headers(
    wallet.address,
    creds,
    'GET',
    '/balance-allowance',
  );
  const query: Record<string, string | number> = {
    signature_type: Number(signatureType),
    asset_type: assetType,
  };
  if (tokenId) query.token_id = tokenId;
  return request<BalanceAllowance>('/balance-allowance', { headers, query });
}

export type OpenOrder = {
  id: string;
  status: string;
  market: string;
  asset_id: string;
  side: Side;
  price: string;
  original_size: string;
  size_matched: string;
};

export async function getOpenOrders(
  wallet: Wallet,
  creds: ApiCreds,
  market?: string,
): Promise<OpenOrder[]> {
  const headers = await createLevel2Headers(
    wallet.address,
    creds,
    'GET',
    '/data/orders',
  );
  const query = market ? { market } : undefined;
  const r = await request<OpenOrder[]>('/data/orders', { headers, query });
  return Array.isArray(r) ? r : [];
}

export async function cancelMarketOrders(
  wallet: Wallet,
  creds: ApiCreds,
  conditionId: string,
): Promise<unknown> {
  const serialized = JSON.stringify({ market: conditionId });
  const headers = await createLevel2Headers(
    wallet.address,
    creds,
    'DELETE',
    '/cancel-market-orders',
    serialized,
  );
  return request('/cancel-market-orders', {
    method: 'DELETE',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: serialized,
  });
}

export type Trade = {
  id: string;
  taker_order_id: string;
  market: string;
  asset_id: string;
  side: Side;
  size: string;
  price: string;
  status: string;
  match_time: string;
  outcome: string;
};

export async function getTrades(
  wallet: Wallet,
  creds: ApiCreds,
  market?: string,
): Promise<Trade[]> {
  const headers = await createLevel2Headers(
    wallet.address,
    creds,
    'GET',
    '/data/trades',
  );
  const query = market ? { market } : undefined;
  const r = await request<Trade[]>('/data/trades', { headers, query });
  return Array.isArray(r) ? r : [];
}
