import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

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

export type NativeState = {
  serviceAlive: boolean;
  feedStatus: 'live' | 'connecting' | 'stalled' | 'closed';
  clockOffsetSec: number;
  lastTick?: NativeTick;
  spotTick?: NativeTick;
  /** Thirty-second TWAP — the number Polymarket shows and settles on. */
  twapTick?: NativeTick;
  quotes?: NativeQuotes;
  positions?: NativePosition[];
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
  start(): Promise<void>;
  stop(): Promise<void>;
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
  gmxCandles(args?: { symbol?: string; period?: string; limit?: number }): Promise<{
    candles: GmxCandle[];
    ticker?: GmxTicker;
  }>;
  polyCandles(args?: { minutes?: number }): Promise<{
    candles: GmxCandle[];
    ticker?: { mid: number; at: number };
  }>;
  /** Binance's candles for one interval: open time in seconds, then o/h/l/c. */
  binanceCandles(args?: {
    interval?: string;
  }): Promise<{ candles: [number, number, number, number, number][] }>;
  /** Binance's book as a depth curve, from the locally kept order book. */
  binanceDepth(): Promise<{
    ready: boolean;
    bid?: number;
    ask?: number;
    at?: number;
    span?: number;
    /** Size per bucket walking away from the mid, nearest bucket first. */
    bids?: number[];
    asks?: number[];
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
    ladderStepSec?: number;
    percentMode?: boolean;
    profitPct?: number;
    sliceGapSec?: number;
    panicSec?: number;
    closeFloor?: number;
    lateFloor?: number;
    lateBandSec?: number;
  }): Promise<void>;
  autoSellState(): Promise<AutoSellState>;
  pulseUpdate(args: {
    enabled?: boolean;
    bankUsd?: number;
    shares?: number;
    minEdge?: number;
    minLean?: number;
    minVolume?: number;
    takePct?: number;
    cutUsd?: number;
    demo?: boolean;
  }): Promise<void>;
  pulseReset(): Promise<void>;
  pulseState(): Promise<PulseState>;
  /**
   * What one address holds off the venue: USDT on BSC and USDC on Polygon.
   * Read-only — the app has no key for BSC and never sends there.
   */
  chainBalance(args: {
    address: string;
  }): Promise<{ usdt: number; polygon: number; total: number }>;
  /** What a withdrawal would have to work with, before one is attempted. */
  withdrawInfo(): Promise<{
    signer: string;
    funder: string;
    /** Whether the collateral sits on a Polymarket proxy rather than the key. */
    proxy: boolean;
    usdcE: number;
    usdc: number;
    pol: number;
    sendable: number;
    gasReady: boolean;
  }>;
  takeUpdate(args: { enabled?: boolean; gain?: number }): Promise<void>;
  takeState(): Promise<TakeState>;
  probeUpdate(args: {
    enabled?: boolean;
    stakeUsd?: number;
    leadSec?: number;
    roomShare?: number;
    roundBand?: number;
    demo?: boolean;
    bankUsd?: number;
  }): Promise<void>;
  probeReset(): Promise<void>;
  probeState(): Promise<ProbeState>;
  /** One transfer of USDC on Polygon, to the address given. */
  withdraw(args: { to: string; usd: number }): Promise<{ hash: string }>;
  /**
   * The two numbers Polymarket prints over its own chart: the price this
   * window has to beat, and where the price is right now.
   *
   * Both are read out of memory on the device, so it may be asked as often as
   * the screen can draw. `target` is missing only until the window's opening
   * reading has been found, and `change` only while it is.
   */
  polyMark(args: { windowStart?: number }): Promise<{
    windowStart: number;
    target?: number | null;
    price?: number | null;
    /** When the live reading arrived, in milliseconds. */
    at: number;
    change?: number | null;
  }>;
  /** Binance's five-minute candle in progress: its open and the last price. */
  binancePrice(): Promise<{
    openTime: number;
    open: number;
    last: number;
    at: number;
  }>;
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
  /** Token id — the unambiguous name of the side, where the label may be empty. */
  asset?: string;
  outcome: string;
  action: 'BUY' | 'SELL';
  /** The price asked for. */
  price: number;
  /**
   * The average price the matched part actually went at.
   *
   * A marketable limit at 81c that sweeps offers at 78 and 79 costs neither,
   * and every later decision — what the exit asks for, what the round made —
   * rests on this rather than on the ask.
   */
  fillPrice?: number | null;
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

/** What the pulse bot is looking at right now. */
export type PulseRead = {
  /** Dollars this window has moved from its own open. */
  lead: number;
  /** Where the last few one-minute closes went, in dollars. */
  momentum: number;
  /** Last completed minute's volume over the average of the ten before. */
  volume: number;
  /** Share of Binance's resting size sitting on the bid, 0..1. */
  lean: number;
  upAsk?: number | null;
  downAsk?: number | null;
};

export type PulseLot = {
  outcome: string;
  shares: number;
  price: number;
  sellPrice: number;
  note?: string | null;
};

export type PulseState = {
  enabled: boolean;
  running: boolean;
  bankUsd: number;
  shares: number;
  minEdge: number;
  takePct: number;
  /** Paper money, which is how it runs unless told otherwise. */
  demo: boolean;
  cash: number;
  /** Why it is not buying, in its own words. */
  note?: string | null;
  lastFault?: string | null;
  rounds: number;
  wins: number;
  losses: number;
  spent: number;
  got: number;
  settled: number;
  pnl: number;
  read?: PulseRead | null;
  lot?: PulseLot | null;
};

/** A position the take rule is watching, and what closing it would pay. */
export type TakeWatch = {
  outcome: string;
  shares: number;
  /** What the shares cost, from the app's own record of the buys. */
  cost: number;
  bid: number;
  /** How far above cost the bid is paying, after the fee. */
  gain: number;
};

/** One window the test bot traded, from the entry to the settlement. */
export type ProbeRound = {
  windowStart: number;
  /** Paper money: nothing about this round reached the venue. */
  demo: boolean;
  /** The level the trade was taken for, and closed at when price got there. */
  target: number;
  /** The side the chart's line was pointing at. */
  side: string;
  /** How fast that line was moving, in dollars an hour. */
  perHour: number;
  shares: number;
  price: number;
  /** Shares the sell ladder got out before the close. */
  sold: number;
  proceeds: number;
  /** What the market paid on whatever the ladder never sold. */
  settled: number;
  /** "Up", "Down", or empty while the result is not known yet. */
  winner: string;
  pnl: number;
  /** Whether the line called the window right. */
  right: boolean;
  note?: string | null;
  /** Still riding: bought, not yet scored. */
  open: boolean;
};

export type ProbeState = {
  enabled: boolean;
  running: boolean;
  stakeUsd: number;
  leadSec: number;
  /**
   * How much room the trend needs in front of it before its direction is
   * worth paying for, as a share of what a window usually travels. Zero
   * switches the check off.
   */
  roomShare: number;
  /**
   * How close to a round five hundred the open may be, in dollars. Zero
   * switches the check off.
   */
  roundBand: number;
  /**
   * The five-minute candle in progress, as its body in dollars: positive is
   * green. It closes at the moment the window opens.
   */
  candleBody: number;
  /**
   * Why the last side was not simply the line's — "разворот" or "коррекция от
   * уровня" — and absent when it was.
   */
  chose?: string | null;
  /** The round five hundred nearest the settlement price, and how far off. */
  roundNear?: number | null;
  roomToRound?: number | null;
  /** Paper money, which is how it runs unless told otherwise. */
  demo: boolean;
  /** What the paper account starts at, and what it is worth now. */
  bankUsd: number;
  bank: number;
  /** The price the reversal is expected at, and how far off it is. */
  levelAhead?: number | null;
  roomToLevel?: number | null;
  note?: string | null;
  lastFault?: string | null;
  trend?: { way: string; perHour: number; fit: number } | null;
  /** Newest first. */
  rounds: ProbeRound[];
  riding: ProbeRound[];
};

export type TakeState = {
  enabled: boolean;
  running: boolean;
  gain: number;
  lastFault?: string | null;
  takes: number;
  shares: number;
  got: number;
  watching: TakeWatch[];
};

/** What the app has timed for itself about the venue's own delays. */
export type Timings = {
  /** Buy to the first sell the venue accepts. */
  sellReadyMs?: number | null;
  sellReadySamples?: number;
  /** Sale to the first balance that shows its money. */
  cashMs?: number | null;
  cashSamples?: number;
  cashPending?: boolean;
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
  timings?: Timings;
  rows: AutoSellRow[];
};

const webStub: PolyBotPlugin = {
  connect: async () => {
    throw new Error('Подключение доступно только в приложении Android');
  },
  diagnose: async () => ({ checks: [] }),
  start: async () => {
    throw new Error('Торговый сервис доступен только в приложении Android');
  },
  stop: async () => {},
  getState: async () => ({
    serviceAlive: false,
    feedStatus: 'closed' as const,
    clockOffsetSec: 0,
    positions: [],
  }),
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
  gmxCandles: async () => {
    throw new Error('График доступен только в приложении Android');
  },
  polyCandles: async () => {
    throw new Error('График доступен только в приложении Android');
  },
  binanceCandles: async () => ({ candles: [] }),
  binanceDepth: async () => ({ ready: false }),
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
    timings: {},
    rows: [],
  }),
  polyMark: async () => ({ windowStart: 0, at: 0 }),
  binancePrice: async () => ({ openTime: 0, open: 0, last: 0, at: 0 }),
  chainBalance: async () => ({ usdt: 0, polygon: 0, total: 0 }),
  withdrawInfo: async () => {
    throw new Error('Вывод доступен только в приложении Android');
  },
  withdraw: async () => {
    throw new Error('Вывод доступен только в приложении Android');
  },
  takeUpdate: async () => {},
  takeState: async () => ({
    enabled: false,
    running: false,
    gain: 0.15,
    takes: 0,
    shares: 0,
    got: 0,
    watching: [],
  }),
  probeUpdate: async () => {},
  probeReset: async () => {},
  probeState: async () => ({
    enabled: false,
    running: false,
    stakeUsd: 5,
    leadSec: 20,
    roomShare: 0.35,
    roundBand: 50,
    candleBody: 0,
    demo: true,
    bankUsd: 100,
    bank: 100,
    rounds: [],
    riding: [],
  }),
  pulseUpdate: async () => {},
  pulseReset: async () => {},
  pulseState: async () => ({
    enabled: false,
    running: false,
    bankUsd: 100,
    shares: 5,
    minEdge: 6,
    takePct: 0.12,
    demo: true,
    cash: 100,
    rounds: 0,
    wins: 0,
    losses: 0,
    spent: 0,
    got: 0,
    settled: 0,
    pnl: 0,
  }),
  addListener: async () => ({ remove: async () => {} }) as PluginListenerHandle,
};

export const PolyBot = registerPlugin<PolyBotPlugin>('PolyBot', {
  web: webStub,
});
