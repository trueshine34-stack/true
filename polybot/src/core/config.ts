/**
 * Network endpoints and on-chain constants.
 *
 * Contract addresses mirror py-clob-client-v2 `config.get_contract_config(137)`.
 * The CLOB currently reports `{"version": 2}` from GET /version, which selects
 * the V2 exchange and the V2 order struct; the client re-checks at runtime.
 */

export const CHAIN_ID = 137; // Polygon mainnet

export const CLOB_HOST = 'https://clob.polymarket.com';
export const GAMMA_HOST = 'https://gamma-api.polymarket.com';
export const DATA_HOST = 'https://data-api.polymarket.com';
export const RTDS_WS = 'wss://ws-live-data.polymarket.com';

export const CONTRACTS = {
  exchangeV1: '0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E',
  exchangeV2: '0xE111180000d2663C0091e4f400237545B87B996B',
  negRiskExchangeV1: '0xC5d563A36AE78145C45a50134d48A1215220f80a',
  negRiskExchangeV2: '0xe2222d279d744050d28e00520010520000310F59',
  negRiskAdapter: '0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296',
  collateral: '0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB', // USDC.e
  conditionalTokens: '0x4D97DCd97eC945f40cF65F87097ACe5EA0476045',
} as const;

export const ZERO_ADDRESS = '0x0000000000000000000000000000000000000000';
export const BYTES32_ZERO =
  '0x0000000000000000000000000000000000000000000000000000000000000000';

/** Slug prefix of the recurring 5-minute BTC up/down series on Gamma. */
export const BTC_5M_SLUG_PREFIX = 'btc-updown-5m-';

/** Window length of one market, in seconds. */
export const WINDOW_SECONDS = 300;

/**
 * Chainlink publishes a TWAP for these markets rather than a spot tick. The
 * 5-minute series averages over the final 30 seconds of the window, so the
 * settlement value is centred ~15s before the close.
 */
export const TWAP_WINDOW_SECONDS = 30;
