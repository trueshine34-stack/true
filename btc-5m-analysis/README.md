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

## Question 3 — reversal after a run

Setup: a run of same-direction candles, then exactly one candle that closes the
other way (the reversal candle). Question: does the candle *after* the reversal
repeat the reversal's direction? (`streaks.py`, doji breaks a run.)

| run length | cases | continues the reversal | goes back to the run | P(same as reversal) |
|---|---|---|---|---|
| 3 | 550 | 297 | 248 | **54.00%** |
| 4 | 257 | 120 | 137 | 46.69% |
| 5 | 112 | 61 | 51 | 54.46% |
| 6 | 58 | 28 | 30 | 48.28% |
| 7+ | 48 | 23 | 24 | 47.92% |
| **all ≥ 4** | **475** | **232** | **242** | **48.84%** (48.95% excl. doji) |
| all ≥ 3 | 1025 | 529 | 490 | 51.61% (51.91% excl. doji) |

For runs longer than 3 the answer is a coin flip — 48.9%, binomial p = 0.68
against 50%, gross −3.19% over 475 trades. The 54.5% on runs of exactly 3
(p = 0.04) is one bucket out of five tested, so it is most likely noise, not an
edge; it would need out-of-sample confirmation on other months.

## Question 4 — is there a pattern above 70%?

Short answer: not for predicting the next candle's direction. Yes, trivially, if
you let an asymmetric target buy the win rate — but that version does not make money.

The search (`search.py`) uses 8 months of 5m candles (69 120 candles,
2026-01-02 … 2026-08-30), 32 predicates (candle directions, runs, body/range/volume
vs the 10-candle average, wick shape, close position in range, SMA20/50 side,
20-candle extremes, UTC session, weekday/weekend) and every conjunction of up to
4 of them. Patterns are mined on the first 70% of the history and then read once
on the untouched last 30%.

### What the search manufactures out of nothing

Run the same search with the up/down labels randomly shuffled (`python3 search.py
candles240.json.gz 3 <seed>`) and it still returns "patterns" at 57–58% in-sample.
That is the noise floor of the search itself. Real data returns 130 patterns above
56% in-sample against 4–7 on shuffled labels, and the real ones keep 55–58% out of
sample where the shuffled ones drop to ~51%. So there is a signal — it is just small.

### Best directional patterns (out-of-sample column is untouched data)

| pattern | side | n train | win train | n test | win test |
|---|---|---|---|---|---|
| close_top25% + new_high20 + run_up≥3 + weekend | SHORT | 226 | 65.5% | 103 | **67.0%** |
| close_top25% + green10≥7 + new_high20 | SHORT | 732 | 58.2% | 304 | 57.6% |
| close_bot25% + new_low20 + utc_18-24 | LONG | 442 | 59.7% | 228 | 59.6% |
| close_bot25% + new_low20 + c3_up | LONG | 737 | 59.2% | 335 | 56.4% |
| new_low20 + run_dn≥3 + weekend | LONG | 491 | 57.8% | 205 | 57.1% |

They are all the same idea: a candle that makes a 20-candle extreme *and* closes in
the far quarter of its own range is an exhausted push, and the next candle fades it.
Nothing reaches 70% on a sample large enough to trust; the 67% one has 103 test cases.

### Where 70%+ is real — and why it is still a loss

Take that fade signal (`signal_tpsl.py`), enter at the next open, exit on target,
stop, or time:

| TP% | SL% | hold | n test | win% test | net per trade after 0.1% fee |
|---|---|---|---|---|---|
| 0.05 | 0.50 | 24 | 1883 | **86.8%** | −0.103% |
| 0.10 | 0.50 | 24 | 1883 | **78.8%** | −0.097% |
| 0.15 | 0.60 | 36 | 1881 | **75.1%** | −0.091% |
| 0.20 | 0.60 | 36 | 1881 | **69.7%** | −0.089% |
| 0.50 | 0.50 | 48 | 1880 | 54.7% | −0.071% |

87% winners, and still a losing system: a small target next to a wide stop trades
frequency for size. The signal is genuinely better than a coin flip — the same
grid on unconditional entries (`tpsl.py`) gives worse gross at every setting — but
its gross edge is about +0.003…+0.03% per trade and a taker round trip costs 0.10%.

**Bottom line.** On 5m BTC the honest numbers are ~55–58% for the best verified
directional pattern and 70–87% for win rate bought with an asymmetric target. Any
single "closed-candle pattern" quoted above 70% for next-candle direction is a
small-sample artifact — this search produces those from shuffled data on demand.

### Files added

* `search.py` — bitmask pattern miner with a train/test split and a shuffled-label
  noise baseline (`MIN_N` / `MIN_WR` env vars set the filter)
* `signal_tpsl.py` — the fade signal with TP/SL/time exits, train vs test
* `tpsl.py` — the same targets on unconditional entries, as the baseline
* `candles240.json.gz` — 8 months of 5m candles
* `report_search.txt`, `report_signal_tpsl.txt` — raw output
