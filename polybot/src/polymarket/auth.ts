import { Wallet } from 'ethers';
import { nowSec } from '../core/clock';
import { CHAIN_ID } from '../core/config';
import { buildHmacSignature } from '../core/hmac';
import type { ApiCreds } from './types';

/**
 * L1 header set. The signature is an EIP-712 `ClobAuth` struct signed by the
 * EOA key; the domain deliberately carries no verifyingContract, matching
 * `make_domain(name, version, chainId)` on the server side.
 */
export async function createLevel1Headers(
  wallet: Wallet,
  nonce = 0,
  timestampSec?: number,
): Promise<Record<string, string>> {
  const ts = timestampSec ?? nowSec();

  const domain = {
    name: 'ClobAuthDomain',
    version: '1',
    chainId: CHAIN_ID,
  };
  const types = {
    ClobAuth: [
      { name: 'address', type: 'address' },
      { name: 'timestamp', type: 'string' },
      { name: 'nonce', type: 'uint256' },
      { name: 'message', type: 'string' },
    ],
  };
  const value = {
    address: wallet.address,
    timestamp: String(ts),
    nonce,
    message: 'This message attests that I control the given wallet',
  };

  const signature = await wallet.signTypedData(domain, types, value);

  return {
    POLY_ADDRESS: wallet.address,
    POLY_SIGNATURE: signature,
    POLY_TIMESTAMP: String(ts),
    POLY_NONCE: String(nonce),
  };
}

/**
 * L2 header set. `path` is the bare request path with no query string — the
 * server signs only the path, and a body is included verbatim as sent.
 */
export async function createLevel2Headers(
  address: string,
  creds: ApiCreds,
  method: string,
  path: string,
  serializedBody?: string,
  timestampSec?: number,
): Promise<Record<string, string>> {
  const ts = timestampSec ?? nowSec();
  const message = `${ts}${method}${path}${serializedBody ?? ''}`;
  const signature = await buildHmacSignature(creds.secret, message);

  return {
    POLY_ADDRESS: address,
    POLY_SIGNATURE: signature,
    POLY_TIMESTAMP: String(ts),
    POLY_API_KEY: creds.apiKey,
    POLY_PASSPHRASE: creds.passphrase,
  };
}
