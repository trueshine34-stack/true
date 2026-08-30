# BTC 5m: follow-the-previous-candle backtest

Data: **BTC-USDT, 5m, OKX public API**, 8640 candles = exactly 30 days
(2026-07-31 18:50 → 2026-08-30 18:45 UTC). Price went 63 092 → 79 064 (+25.3%).
Candle mix over the month: 4213 green / 4328 red / 99 doji.

## Rules tested

**A — momentum.** After every closed candle, enter at the open of the next candle
in the direction that candle closed (green → long, red → short) and exit at that
candle's close. One trade per candle, no stop, no target.

**B — with the volatility pause.** Same rule, but trading stops whenever a candle is
larger than the average size of the 10 candles before it. It stays paused until a
candle closes in the direction *opposite* to that oversized candle; that candle is
then the next signal and the same rule resumes. Size is measured two ways: full
range (high−low) and body (|close−open|).

## Results

| strategy | trades | win % | win % excl. doji | gross sum of % | avg per trade | after 0.1% round-trip fee |
|---|---|---|---|---|---|---|
| A — every candle | 8540 | **48.34** | 48.83 | +1.92% | +0.0002% | −852% |
| B — pause on oversized range | 3925 | **49.50** | 49.96 | +3.07% | +0.0008% | −389% |
| B — pause on oversized body | 3856 | **50.21** | 50.65 | +11.04% | +0.0029% | −375% |
| A inverted (fade the candle) | 8540 | 50.66 | 51.17 | −1.92% | −0.0002% | −856% |

Split by side (strategy A): long after green 47.57% (+12.30% gross),
short after red 49.09% (−10.38% gross). With the body filter (B):
49.31% long / 51.06% short, both gross-positive.
Longest run of consecutive losses in A: 12; longest run of wins: 11.

## Reading it

* Raw follow-through on 5m BTC is **below a coin flip** — 48.3%. 5m candles
  mean-revert slightly more often than they continue, which is why the inverted
  version wins 50.7% of the time. Neither edge is tradable: the inverted side's
  extra wins are smaller than its losses, so its gross P&L is negative too.
* The volatility pause is a genuine improvement in hit rate (48.3% → 50.2%) and it
  cuts trade count by more than half — it removes entries right after a spike,
  which is exactly where 5m follow-through fails hardest. But it lands on a coin
  flip, not on an edge.
* Fees decide everything. At 0.05% per side the strategy needs ~0.10% of average
  move per trade just to break even; the average 5m candle body here is far below
  that, so both variants lose ~375–850% of notional over the month in fees. Even
  at zero fees the gross result (+1.9% / +11.0% over 8640 or 3856 trades) is inside
  the noise for a month where BTC itself rose 25%.

## Files

* `fetch_okx.py` — downloads N days of 5m candles (`python3 fetch_okx.py 30 candles.json`)
* `analyze.py` — runs both strategies and prints the table
* `candles.json` — the month of data used here
* `report.txt` — raw output of the run above
