export type ApiCreds = {
  apiKey: string;
  secret: string;
  passphrase: string;
};

/** Matches SignatureTypeV2 in py-clob-client-v2 and the native SignatureType. */
export enum SignatureType {
  /** Plain EOA — funds sit in the key's own address. */
  EOA = 0,
  /** Polymarket proxy wallet (email / Magic login). */
  POLY_PROXY = 1,
  /** Polymarket Gnosis Safe (browser-wallet login). */
  POLY_GNOSIS_SAFE = 2,
}
