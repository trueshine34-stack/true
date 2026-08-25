import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type { StrategySettings } from '../core/settings';

/**
 * Handle on the native trading service.
 *
 * Everything that has to survive the app being backgrounded — the Chainlink
 * socket, the 5-minute loop, order signing — lives on the other side of this
 * bridge. The WebView only unlocks the key, hands it over, and observes.
 */

export type NativeTick = { timestamp: number; value: number };

export type NativeEntry = {
  side: 'Up' | 'Down';
  price: number;
  shares: number;
  costUsd: number;
  orderId?: string | null;
  dryRun: boolean;
};

export type NativeMarket = {
  conditionId: string;
  question: string;
  upTokenId: string;
  downTokenId: string;
  tickSize: number;
  minimumOrderSize: number;
  windowStart?: number;
  windowEnd?: number;
};

export type NativeQuote = {
  bestBid?: number | null;
  bestAsk?: number | null;
  mid?: number | null;
};

export type NativeQuotes = {
  up?: NativeQuote | null;
  down?: NativeQuote | null;
  atMs: number;
};

export type NativePosition = {
  asset: string;
  conditionId: string;
  title: string;
  outcome: string;
  size: number;
  avgPrice: number;
  curPrice: number;
  cashPnl: number;
  redeemable: boolean;
};

export type NativeExit = {
  orderId: string;
  price: number;
  size: number;
  matched: number;
  cancelled: boolean;
};

export type OpenOrder = {
  id: string;
  status: string;
  market: string;
  assetId: string;
  side: 'BUY' | 'SELL';
  price: number;
  originalSize: number;
  sizeMatched: number;
  remaining: number;
  outcome?: string | null;
};

export type PlaceOrderArgs = {
  tokenId: string;
  conditionId: string;
  side: 'BUY' | 'SELL';
  price: number;
  /** Shares for a limit order, USDC for a FOK/FAK buy. */
  size: number;
  orderType?: 'GTC' | 'FOK' | 'FAK';
};

export type PlaceOrderResult = {
  success: boolean;
  orderId?: string | null;
  status?: string | null;
  error?: string | null;
};

export type NativeCycle = {
  windowStart: number;
  windowEnd: number;
  state: 'waiting' | 'armed' | 'entered' | 'skipped' | 'settled' | 'failed';
  strike?: number | null;
  spotAtEntry?: number | null;
  winner?: 'Up' | 'Down' | null;
  pnlUsd?: number | null;
  note?: string | null;
  fair?: { pUp: number; rawPUp?: number; sigmaHorizon: number; drift: number };
  entry?: NativeEntry;
  market?: NativeMarket;
  exits?: NativeExit[];
  exitFrozen?: boolean;
  takeProfitDone?: boolean;
  averageDownCount?: number;
  soldAtMarket?: number;
  marketProceedsUsd?: number;
};

export type NativeStats = {
  trades: number;
  wins: number;
  losses: number;
  consecutiveLosses: number;
  realisedPnlUsd: number;
  stakedUsd: number;
};

export type NativeState = {
  serviceAlive: boolean;
  running: boolean;
  haltReason?: string | null;
  feedStatus: 'live' | 'connecting' | 'stalled' | 'closed';
  clockOffsetSec: number;
  /** Local calendar day the stats belong to, yyyy-MM-dd. */
  statsDay?: string;
  /** What the model has learned about its own confidence. */
  calibration?: {
    samples: number;
    /** How much of the model's lean is actually traded on, 0–1. */
    shrinkage: number;
    /** Mean Brier score; 0.25 is a coin flip, lower is better. */
    brier?: number | null;
  };
  lastTick?: NativeTick;
  spotTick?: NativeTick;
  quotes?: NativeQuotes;
  positions?: NativePosition[];
  stats?: NativeStats;
  current?: NativeCycle;
  history?: NativeCycle[];
};

export type NativeLog = {
  id: number;
  at: number;
  level: 'info' | 'trade' | 'warn' | 'error';
  message: string;
};

export type ConnectArgs = {
  privateKey: string;
  funderAddress: string;
  signatureType: number;
  settings: StrategySettings;
};

export type ConnectResult = {
  /** Address derived natively from the key. */
  address: string;
  clockOffsetSec: number;
  usdc?: number;
  /** Set when credentials worked but the balance read did not. */
  balanceError?: string;
};

export type DiagnosticCheck = {
  name: string;
  /** True for the neutral connectivity probe, false for Polymarket hosts. */
  control: boolean;
  ok: boolean;
  ms: number;
  error?: string;
};

export interface PolyBotPlugin {
  connect(args: ConnectArgs): Promise<ConnectResult>;
  diagnose(): Promise<{ checks: DiagnosticCheck[] }>;
  updateSettings(args: { settings: StrategySettings }): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
  resetStats(): Promise<void>;
  getState(): Promise<NativeState>;
  getLogs(): Promise<{ entries: NativeLog[] }>;
  getBalance(): Promise<{ usdc: number }>;
  exportJournal(): Promise<{ file: string; bytes: number }>;
  clearJournal(): Promise<void>;
  getJournalSize(): Promise<{ bytes: number }>;
  getOpenOrders(args?: { market?: string }): Promise<{ orders: OpenOrder[] }>;
  getCurrentMarket(): Promise<NativeMarket>;
  placeOrder(args: PlaceOrderArgs): Promise<PlaceOrderResult>;
  cancelOrder(args: { orderId: string }): Promise<{ cancelled: boolean }>;
  cancelMarketOrders(args: { conditionId: string }): Promise<{ cancelled: number }>;
  replaceOrder(
    args: PlaceOrderArgs & { orderId: string },
  ): Promise<PlaceOrderResult>;
  requestBatteryExemption(): Promise<{ exempt: boolean }>;
  isBatteryExempt(): Promise<{ exempt: boolean }>;
  pairStart(args?: { settings?: PairSettings }): Promise<void>;
  pairStop(): Promise<void>;
  pairReset(): Promise<void>;
  pairUpdateSettings(args: { settings: PairSettings }): Promise<void>;
  pairGetState(): Promise<PairState>;
  addListener(
    event: 'state',
    fn: (state: NativeState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'log',
    fn: (entry: NativeLog) => void,
  ): Promise<PluginListenerHandle>;
}

// ------------------------------------------------------------ pair strategy

export type PairSettings = {
  dryRun: boolean;
  lotShares: number;
  /** Extra size on the cheaper side, and how far it may lead the other. */
  cheapSideBonusPct: number;
  minIntervalSec: number;
  maxIntervalSec: number;
  maxSeedPrice: number;
  maxPairAvg: number;
  minPairProfitPct: number;
  rotateProfitPct: number;
  cheapLegUnder: number;
  cheapRotateProfitPct: number;
  rotateFraction: number;
  takerEntry: boolean;
  maxExposureUsd: number;
  maxImbalanceShares: number;
  flattenSec: number;
  paperStartUsd: number;
};

export type PairOrder = {
  localId: number;
  orderId?: string | null;
  side: 'Up' | 'Down';
  action: 'BUY' | 'SELL';
  price: number;
  size: number;
  matched: number;
  dryRun: boolean;
  placedAt: number;
  note: string;
};

export type PairFill = {
  at: number;
  side: 'Up' | 'Down';
  action: 'BUY' | 'SELL';
  shares: number;
  price: number;
  feeUsd: number;
  dryRun: boolean;
  note: string;
};

export type PairBook = {
  windowStart: number;
  windowEnd: number;
  upShares: number;
  upAvg: number;
  downShares: number;
  downAvg: number;
  pairs: number;
  pairAvg: number;
  imbalance: number;
  exposureUsd: number;
  spentUsd: number;
  proceedsUsd: number;
  feesUsd: number;
  lockedProfitUsd: number;
};

export type PairWindow = {
  windowStart: number;
  pairs: number;
  pairAvg: number;
  winner?: 'Up' | 'Down' | null;
  pnlUsd?: number | null;
  feesUsd: number;
};

export type PairState = {
  running: boolean;
  dryRun: boolean;
  haltReason?: string | null;
  quotes?: NativeQuotes;
  book?: PairBook;
  orders: PairOrder[];
  fills: PairFill[];
  windows: PairWindow[];
  stats: PairStats;
  /** Kept apart so a paper run can never flatter the live figures. */
  testStats?: PairStats;
  liveStats?: PairStats;
  /** Cash in the paper account, carried across every session. */
  paperCash?: number;
  /** Paper cash plus what the open legs would fetch at the bid. */
  paperEquity?: number;
};

export type PairStats = {
  windows: number;
  buys: number;
  sells: number;
  pairsLocked: number;
  feesUsd: number;
  realisedPnlUsd: number;
};

const IDLE_STATE: NativeState = {
  serviceAlive: false,
  running: false,
  feedStatus: 'closed',
  clockOffsetSec: 0,
};

const IDLE_PAIR_STATE: PairState = {
  running: false,
  dryRun: true,
  orders: [],
  fills: [],
  windows: [],
  stats: {
    windows: 0,
    buys: 0,
    sells: 0,
    pairsLocked: 0,
    feesUsd: 0,
    realisedPnlUsd: 0,
  },
};

/**
 * Browser fallback so `npm run dev` still renders. It reports an idle service
 * rather than pretending to trade — the engine genuinely does not exist here.
 */
const webStub: PolyBotPlugin = {
  connect: async () => {
    throw new Error('Подключение доступно только в приложении Android');
  },
  diagnose: async () => ({ checks: [] }),
  updateSettings: async () => {},
  start: async () => {
    throw new Error('Торговый сервис доступен только в приложении Android');
  },
  stop: async () => {},
  resetStats: async () => {},
  getState: async () => IDLE_STATE,
  getLogs: async () => ({ entries: [] }),
  getBalance: async () => {
    throw new Error('Баланс доступен только в приложении Android');
  },
  exportJournal: async () => {
    throw new Error('Экспорт доступен только в приложении Android');
  },
  clearJournal: async () => {},
  getJournalSize: async () => ({ bytes: 0 }),
  getOpenOrders: async () => ({ orders: [] }),
  getCurrentMarket: async () => {
    throw new Error('Рынок доступен только в приложении Android');
  },
  placeOrder: async () => {
    throw new Error('Ордера доступны только в приложении Android');
  },
  cancelOrder: async () => ({ cancelled: false }),
  cancelMarketOrders: async () => ({ cancelled: 0 }),
  replaceOrder: async () => {
    throw new Error('Ордера доступны только в приложении Android');
  },
  requestBatteryExemption: async () => ({ exempt: true }),
  isBatteryExempt: async () => ({ exempt: true }),
  pairStart: async () => {
    throw new Error('Торговый сервис доступен только в приложении Android');
  },
  pairStop: async () => {},
  pairReset: async () => {},
  pairUpdateSettings: async () => {},
  pairGetState: async () => IDLE_PAIR_STATE,
  addListener: async () => ({ remove: async () => {} }) as PluginListenerHandle,
};

export const PolyBot = registerPlugin<PolyBotPlugin>('PolyBot', {
  web: webStub,
});
