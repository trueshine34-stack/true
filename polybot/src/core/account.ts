/** Matches SignatureTypeV2 in py-clob-client-v2 and the native SignatureType. */
export enum SignatureType {
  /** Plain EOA — funds sit in the key's own address. */
  EOA = 0,
  /** Polymarket proxy wallet (email / Magic login). */
  POLY_PROXY = 1,
  /** Polymarket Gnosis Safe (browser-wallet login). */
  POLY_GNOSIS_SAFE = 2,
}

export type AccountConfig = {
  /** Address of the signing key, as derived natively. */
  signerAddress: string;
  /** Address holding the USDC — equals signerAddress for a plain EOA. */
  funderAddress: string;
  signatureType: SignatureType;
};

const PRIVATE_KEY_RE = /^(0x)?[0-9a-fA-F]{64}$/;
const ADDRESS_RE = /^0x[0-9a-fA-F]{40}$/;

/** Shape check only — the native side does the real validation. */
export const looksLikePrivateKey = (value: string): boolean =>
  PRIVATE_KEY_RE.test(value.trim());

export const looksLikeAddress = (value: string): boolean =>
  ADDRESS_RE.test(value.trim());
