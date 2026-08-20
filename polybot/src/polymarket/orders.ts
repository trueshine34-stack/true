import { Wallet } from 'ethers';
import { BYTES32_ZERO, CHAIN_ID, CONTRACTS } from '../core/config';
import { SignatureType, type Side, type SignedOrderV2 } from './types';

/**
 * Order construction, ported from py-clob-client-v2 `OrderBuilder`.
 *
 * The V2 exchange signs 11 fields; `expiration` travels in the request body
 * but is deliberately outside the signed struct.
 */

type RoundConfig = { price: number; size: number; amount: number };

const ROUNDING: Record<string, RoundConfig> = {
  '0.1': { price: 1, size: 2, amount: 3 },
  '0.01': { price: 2, size: 2, amount: 4 },
  '0.005': { price: 3, size: 2, amount: 5 },
  '0.0025': { price: 4, size: 2, amount: 6 },
  '0.001': { price: 3, size: 2, amount: 5 },
  '0.0001': { price: 4, size: 2, amount: 6 },
};

export function roundConfigFor(tickSize: number): RoundConfig {
  const cfg = ROUNDING[String(tickSize)];
  if (!cfg) throw new Error(`unsupported tick size ${tickSize}`);
  return cfg;
}

const pow10 = (n: number) => Math.pow(10, n);

const roundDown = (x: number, d: number) => Math.floor(x * pow10(d)) / pow10(d);
const roundUp = (x: number, d: number) => Math.ceil(x * pow10(d)) / pow10(d);
const roundNormal = (x: number, d: number) => Math.round(x * pow10(d)) / pow10(d);

function decimalPlaces(x: number): number {
  const s = String(x);
  if (s.includes('e') || s.includes('E')) {
    const [mantissa, exp] = s.split(/[eE]/);
    const frac = mantissa.split('.')[1]?.length ?? 0;
    return Math.max(0, frac - Number(exp));
  }
  return s.split('.')[1]?.length ?? 0;
}

/** USDC and CTF shares both carry 6 decimals. */
function toTokenDecimals(x: number): string {
  let f = 1e6 * x;
  if (decimalPlaces(f) > 0) f = Math.round(f);
  return String(Math.round(f));
}

/**
 * Amounts for a market order, where `amount` is denominated in USDC for a BUY
 * and in shares for a SELL.
 */
export function marketOrderAmounts(
  side: Side,
  amount: number,
  price: number,
  cfg: RoundConfig,
): { makerAmount: string; takerAmount: string } {
  const rawPrice = roundDown(price, cfg.price);

  if (side === 'BUY') {
    const rawMaker = roundDown(amount, cfg.size);
    let rawTaker = rawMaker / rawPrice;
    if (decimalPlaces(rawTaker) > cfg.amount) {
      rawTaker = roundUp(rawTaker, cfg.amount + 4);
      if (decimalPlaces(rawTaker) > cfg.amount) {
        rawTaker = roundDown(rawTaker, cfg.amount);
      }
    }
    return {
      makerAmount: toTokenDecimals(rawMaker),
      takerAmount: toTokenDecimals(rawTaker),
    };
  }

  const rawMaker = roundDown(amount, cfg.size);
  let rawTaker = rawMaker * rawPrice;
  if (decimalPlaces(rawTaker) > cfg.amount) {
    rawTaker = roundUp(rawTaker, cfg.amount + 4);
    if (decimalPlaces(rawTaker) > cfg.amount) {
      rawTaker = roundDown(rawTaker, cfg.amount);
    }
  }
  return {
    makerAmount: toTokenDecimals(rawMaker),
    takerAmount: toTokenDecimals(rawTaker),
  };
}

/** Amounts for a limit order, where `size` is always in shares. */
export function limitOrderAmounts(
  side: Side,
  size: number,
  price: number,
  cfg: RoundConfig,
): { makerAmount: string; takerAmount: string } {
  const rawPrice = roundNormal(price, cfg.price);

  if (side === 'BUY') {
    const rawTaker = roundDown(size, cfg.size);
    let rawMaker = rawTaker * rawPrice;
    if (decimalPlaces(rawMaker) > cfg.amount) {
      rawMaker = roundUp(rawMaker, cfg.amount + 4);
      if (decimalPlaces(rawMaker) > cfg.amount) {
        rawMaker = roundDown(rawMaker, cfg.amount);
      }
    }
    return {
      makerAmount: toTokenDecimals(rawMaker),
      takerAmount: toTokenDecimals(rawTaker),
    };
  }

  const rawMaker = roundDown(size, cfg.size);
  let rawTaker = rawMaker * rawPrice;
  if (decimalPlaces(rawTaker) > cfg.amount) {
    rawTaker = roundUp(rawTaker, cfg.amount + 4);
    if (decimalPlaces(rawTaker) > cfg.amount) {
      rawTaker = roundDown(rawTaker, cfg.amount);
    }
  }
  return {
    makerAmount: toTokenDecimals(rawMaker),
    takerAmount: toTokenDecimals(rawTaker),
  };
}

function generateSalt(): number {
  return Math.floor(Math.random() * Date.now());
}

export type BuildOrderArgs = {
  wallet: Wallet;
  /** Address that holds the USDC — the proxy for POLY_PROXY / SAFE. */
  funder: string;
  signatureType: SignatureType;
  tokenId: string;
  side: Side;
  makerAmount: string;
  takerAmount: string;
  negRisk: boolean;
  /** Test hooks; production callers leave these unset. */
  salt?: number;
  timestampMs?: number;
};

export async function buildAndSignOrder(
  args: BuildOrderArgs,
): Promise<SignedOrderV2> {
  const verifyingContract = args.negRisk
    ? CONTRACTS.negRiskExchangeV2
    : CONTRACTS.exchangeV2;

  const order = {
    salt: args.salt ?? generateSalt(),
    maker: args.funder,
    signer: args.wallet.address,
    tokenId: args.tokenId,
    makerAmount: args.makerAmount,
    takerAmount: args.takerAmount,
    side: args.side === 'BUY' ? 0 : 1,
    signatureType: Number(args.signatureType),
    timestamp: String(args.timestampMs ?? Date.now()),
    metadata: BYTES32_ZERO,
    builder: BYTES32_ZERO,
  };

  const domain = {
    name: 'Polymarket CTF Exchange',
    version: '2',
    chainId: CHAIN_ID,
    verifyingContract,
  };
  const types = {
    Order: [
      { name: 'salt', type: 'uint256' },
      { name: 'maker', type: 'address' },
      { name: 'signer', type: 'address' },
      { name: 'tokenId', type: 'uint256' },
      { name: 'makerAmount', type: 'uint256' },
      { name: 'takerAmount', type: 'uint256' },
      { name: 'side', type: 'uint8' },
      { name: 'signatureType', type: 'uint8' },
      { name: 'timestamp', type: 'uint256' },
      { name: 'metadata', type: 'bytes32' },
      { name: 'builder', type: 'bytes32' },
    ],
  };

  const signature = await args.wallet.signTypedData(domain, types, order);

  return {
    salt: order.salt,
    maker: order.maker,
    signer: order.signer,
    tokenId: order.tokenId,
    makerAmount: order.makerAmount,
    takerAmount: order.takerAmount,
    side: args.side,
    expiration: '0',
    signatureType: order.signatureType,
    timestamp: order.timestamp,
    metadata: order.metadata,
    builder: order.builder,
    signature,
  };
}
