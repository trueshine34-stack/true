export type ApiCreds = {
  apiKey: string;
  secret: string;
  passphrase: string;
};

/** Matches SignatureTypeV2 in py-clob-client-v2. */
export enum SignatureType {
  /** Plain EOA — funds sit in the key's own address. */
  EOA = 0,
  /** Polymarket proxy wallet (email / Magic login). */
  POLY_PROXY = 1,
  /** Polymarket Gnosis Safe (browser-wallet login). */
  POLY_GNOSIS_SAFE = 2,
}

export type Side = 'BUY' | 'SELL';
export type OrderType = 'FOK' | 'FAK' | 'GTC' | 'GTD';

export type SignedOrderV2 = {
  salt: number;
  maker: string;
  signer: string;
  tokenId: string;
  makerAmount: string;
  takerAmount: string;
  side: Side;
  expiration: string;
  signatureType: number;
  timestamp: string;
  metadata: string;
  builder: string;
  signature: string;
};

export type BookLevel = { price: string; size: string };

export type OrderBook = {
  market: string;
  asset_id: string;
  timestamp: string;
  hash: string;
  bids: BookLevel[];
  asks: BookLevel[];
};

export type PostOrderResult = {
  success?: boolean;
  errorMsg?: string;
  error?: unknown;
  orderID?: string;
  orderHashes?: string[];
  transactionsHashes?: string[];
  status?: string;
  makingAmount?: string;
  takingAmount?: string;
};

/** One side of a 5-minute market. */
export type Outcome = {
  label: 'Up' | 'Down';
  tokenId: string;
};

export type Btc5mMarket = {
  slug: string;
  conditionId: string;
  question: string;
  /** Unix seconds, aligned to a 300s boundary — the window open. */
  windowStart: number;
  /** windowStart + 300. */
  windowEnd: number;
  negRisk: boolean;
  tickSize: number;
  minimumOrderSize: number;
  acceptingOrders: boolean;
  up: Outcome;
  down: Outcome;
};
