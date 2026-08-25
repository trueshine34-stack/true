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

/** How one five-minute event went: which side it closed on, and the money. */
export type EventSummary = {
  windowStart: number;
  /** "Up", "Down", or empty while the window is still running. */
  winner: string;
  settled: boolean;
  spent: number;
  got: number;
  held: number;
  settlement: number;
  pnl: number;
  trades: number;
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
  /** Thirty-second TWAP — the number Polymarket shows and settles on. */
  twapTick?: NativeTick;
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
  gmxCandles(args?: { symbol?: string; period?: string; limit?: number }): Promise<{
    candles: GmxCandle[];
    ticker?: GmxTicker;
  }>;
  polyCandles(args?: { minutes?: number }): Promise<{
    candles: GmxCandle[];
    ticker?: { mid: number; at: number };
  }>;
  getBookLevels(args: { tokenId: string; depth?: number }): Promise<BookLevels>;
  getPositions(): Promise<{ positions: NativePosition[] }>;
  getOrderLog(args?: { windowStart?: number }): Promise<{ orders: LoggedOrder[] }>;
  getEvents(args?: { limit?: number }): Promise<{ events: EventSummary[]; session: number }>;
  getMarketForWindow(args: { windowStart: number }): Promise<NativeMarket>;
  vaultStore(args: { privateKey: string }): Promise<void>;
  vaultLoad(): Promise<{ privateKey?: string | null }>;
  vaultClear(): Promise<void>;
  autoSellUpdate(args: {
    enabled?: boolean;
    ladder?: number[];
    retryEverySec?: number;
    rebuyEnabled?: boolean;
    rebuyDropPct?: number;
    watchSec?: number;
    rebuySlicePauseSec?: number;
    ladderLeadSec?: number;
    percentMode?: boolean;
    profitPct?: number;
    sliceGapSec?: number;
    panicSec?: number;
  }): Promise<void>;
  autoSellState(): Promise<AutoSellState>;
  addListener(
    event: 'state',
    fn: (state: NativeState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'log',
    fn: (entry: NativeLog) => void,
  ): Promise<PluginListenerHandle>;
}

// ---------------------------------------------------------- manual desk

export type GmxCandle = {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
};

export type GmxTicker = { min: number; max: number; mid: number; at: number };

/** An order this app sent, and what became of it. */
export type LoggedOrder = {
  id: number;
  orderId?: string | null;
  outcome: string;
  action: 'BUY' | 'SELL';
  price: number;
  size: number;
  matched: number;
  /** resting | partial | filled | cancelled */
  status: string;
  placedAt: number;
  auto: boolean;
};

export type BookLevel = { price: number; size: number };
export type BookLevels = { bids: BookLevel[]; asks: BookLevel[] };

export type AutoSellRow = {
  asset: string;
  title: string;
  outcome: string;
  size: number;
  resting: number;
  restingPrice?: number | null;
  status: string;
  attempts: number;
  /** When the last attempt on this position ran, and what came back. */
  lastTryAt: number;
  lastError?: string | null;
  /** Rung of the ladder this position is on, and the price it asks. */
  step: number;
  target: number;
};

export type AutoSellRebuy = {
  outcome?: string | null;
  shares: number;
  /** Still to buy back, and the clip it is bought in. */
  remaining: number;
  lot: number;
  soldAt: number;
  trigger: number;
  /** Set only when something is wrong; a plain wait leaves it empty. */
  note?: string | null;
  /** Live price, the closest it has come, and when it was last read. */
  lastAsk?: number | null;
  bestAsk?: number | null;
  lastCheckAt: number;
  checks: number;
};

/** How a buy-back ended. */
export type AutoSellRebuyDone = {
  outcome: string;
  shares: number;
  soldAt: number;
  trigger: number;
  bestAsk?: number | null;
  result: string;
  at: number;
};

export type AutoSellState = {
  enabled: boolean;
  running: boolean;
  ladder: number[];
  retryEverySec: number;
  lastSweepAt: number;
  /** Why the last sweep could not run at all, if it could not. */
  lastFault?: string | null;
  /** Purchases still being chased, and how long each is chased for. */
  watching?: number;
  watchSec?: number;
  rebuyEnabled: boolean;
  rebuyDropPct: number;
  rebuys: AutoSellRebuy[];
  rebuysDone?: AutoSellRebuyDone[];
  rows: AutoSellRow[];
};

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
  /** How far from the window low to bid, in cents. */
  lowBiasCents: number;
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

/** One price level and how many times the window's price arrived there. */
export type PairLevel = { level: number; visits: number };

export type PairTrack = {
  levels: PairLevel[];
  /** Cheapest offer seen this window — what the bot anchors its bids to. */
  lowAsk?: number | null;
  lowMid?: number | null;
  highMid?: number | null;
};

export type PairProfile = {
  tickSize: number;
  up: PairTrack;
  down: PairTrack;
};

export type PairState = {
  running: boolean;
  dryRun: boolean;
  haltReason?: string | null;
  quotes?: NativeQuotes;
  book?: PairBook;
  profile?: PairProfile;
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
  gmxCandles: async () => {
    throw new Error('График доступен только в приложении Android');
  },
  polyCandles: async () => {
    throw new Error('График доступен только в приложении Android');
  },
  getBookLevels: async () => ({ bids: [], asks: [] }),
  getPositions: async () => ({ positions: [] }),
  getOrderLog: async () => ({ orders: [] }),
  getEvents: async () => ({ events: [], session: 0 }),
  getMarketForWindow: async () => {
    throw new Error('Рынок доступен только в приложении Android');
  },
  vaultStore: async () => {
    throw new Error('Хранилище ключа доступно только в приложении Android');
  },
  vaultLoad: async () => ({ privateKey: null }),
  vaultClear: async () => {},
  autoSellUpdate: async () => {},
  autoSellState: async () => ({
    enabled: false,
    running: false,
    ladder: [0.77, 0.84, 0.89, 0.93, 0.97],
    retryEverySec: 7,
    lastSweepAt: 0,
    rebuyEnabled: false,
    rebuyDropPct: 0.2,
    rebuys: [],
    rows: [],
  }),
  addListener: async () => ({ remove: async () => {} }) as PluginListenerHandle,
};

export const PolyBot = registerPlugin<PolyBotPlugin>('PolyBot', {
  web: webStub,
});
