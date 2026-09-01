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

## Question 5 — does the fade signal pay over several candles?

Answer: gross yes, net no. The edge is real but it is worth 0.01–0.05% per trade
and a taker round trip costs 0.10%.

`horizon.py` measures every signal from the next candle's open to the close h
candles later, and subtracts the unconditional return over the same horizon and
side, so the market's own uptrend over this sample does not flatter the longs.

| horizon | train edge/trade | train t | test edge/trade | test t |
|---|---|---|---|---|
| 3 candles (15 min) | +0.019% | 3.83 | −0.001% | −0.15 |
| 8 candles (40 min) | +0.024% | 3.14 | +0.004% | 0.47 |
| 20 candles (100 min) | +0.045% | 3.88 | −0.003% | −0.14 |
| 60 candles (5 h) | +0.055% | 2.97 | −0.009% | −0.23 |

Statistically solid in-sample (t ≈ 3–4), gone out-of-sample (t ≈ 0). Splitting the
test set by side shows longs at t ≈ 2–2.9 for 40–150 min and shorts negative
throughout — that is the bull leg, not the signal.

### Month by month (`stability.py`)

Gross % per trade, holding 20 candles: Jan +0.025, Feb +0.038, Mar +0.076,
Apr +0.039, May +0.013, Jun +0.049, Jul +0.043, Aug −0.040. Seven months of eight
are positive, so the effect is not imaginary — but the **break-even round-trip fee
is 0.030%**, i.e. 0.015% per side. Standard OKX taker is 0.05% per side and maker
0.02%; only maker fills at a VIP tier clear that bar, and a fade signal filled by a
resting limit order is adversely selected, so maker assumptions flatter it further.

### Making the signal stricter (`variants.py`)

Fewer, better trades is the only route past the fee. Holding 20 candles:

| variant | trades | trades/month | win% | gross/trade | break-even fee |
|---|---|---|---|---|---|
| base: 20-extreme, close in far 25% | 6076 | 760 | 55.5% | 0.0298% | 0.030% |
| 50-candle extreme | 3125 | 391 | 56.8% | 0.0413% | 0.041% |
| **50-extreme + close in far 15%** | 2190 | 274 | 57.3% | **0.0460%** | 0.046% |
| 50-extreme + range > 2× avg10 | 1038 | 130 | 55.1% | 0.0500% | 0.050% |
| 100-extreme + range>1.5 + run≥3 | 488 | 61 | 53.9% | 0.0123% | 0.012% |

The best variant doubles the edge per trade. Then check it honestly: train
(Jan–19 Jun) n=1496, win 58.1%, gross +0.0758%, t=3.31; test (19 Jun–30 Aug) n=694,
win 55.5%, gross **−0.0183%**, t=−0.74. Month by month it is positive in seven
months and −0.099% in August, which erases the rest.

**Conclusion.** Fading an exhausted extreme is a genuine statistical effect on 5m
BTC — 55–58% hit rate, positive in most months, well clear of the shuffled-label
noise floor. It is not a tradeable edge: it earns about a third of a taker round
trip, it needs sub-0.02%-per-side costs plus zero slippage to break even, and it
turns negative for months at a time. Anything found on 5m candles alone lands in
this same band; a profitable 5m system needs an input candles do not contain —
order-flow, funding, cross-venue basis — or a slower timeframe where the moves are
large relative to costs.

### Files added

* `horizon.py` — forward returns at 1…60 candles, drift-adjusted, train vs test
* `stability.py` — month-by-month edge and the break-even fee
* `variants.py` — stricter signal definitions ranked by gross per trade
* `report_horizon.txt` — raw output

## Question 6 — night runs (Vietnam time) and daily coverage

All times ICT (UTC+7). Sample: 69 120 candles, 3 Jan – 31 Aug 2026, 239 complete days.

### Are runs of 6+ concentrated at 03:00–07:00?

No, not by frequency. 170 runs of 6+ start in that window against 151 expected if
they were spread evenly — 14.76 per 1000 candles vs 12.81 for the rest of the day,
1.15×, z = 1.66, **p = 0.096**. For runs of 8+ the night is actually below
expectation (25 observed, 29.7 expected). The busiest single hour of the year is
13:00 ICT, not a night hour.

What *is* true about those hours is the volatility. Average 5m candle range by hour:

| hour ICT | 03 | 04 | 05 | 06 | … | 20 | 21 | 22 |
|---|---|---|---|---|---|---|---|---|
| avg range | 0.152% | 0.147% | 0.169% | 0.148% | | 0.213% | 0.265% | 0.252% |

03:00–07:00 is the quietest part of the day; 20:00–23:00 (US session) is 1.7× wider.
A 6-candle run at 04:00 moves 0.47–0.73% in small, clean, same-size steps; the same
run at 21:00 moves ~1.0% in a mess of long wicks. The night runs are not more
frequent, they are more *legible* — which is what makes them memorable.

And the wider context: long runs are **rarer** on 5m BTC than a coin flip predicts —
908 runs of 6+ against 1080 expected (0.84×), 42 runs of 10+ against 68 (0.62×).
That is the same mean reversion the earlier sections found, and it tightens as the
runs get longer: 0.84× at 6, 0.62× at 9–11, 0.35× at 13, 0.24× at 14, and zero
observed at 15+ where a coin flip would have produced about two.

### Does every day have a run of 6 / 7 / 8 / 9 / 10?

| N in a row | days with it | share | avg per day | days without |
|---|---|---|---|---|
| 6+ | 236 / 239 | **98.7%** | 3.77 | 3 |
| 7+ | 202 / 239 | 84.5% | 1.74 | 37 |
| 8+ | 133 / 239 | 55.6% | 0.74 | 106 |
| 9+ | 74 / 239 | 31.0% | 0.35 | 165 |
| 10+ | 42 / 239 | 17.6% | 0.17 | 197 |
| 11+ | 21 / 239 | 8.8% | 0.09 | 218 |
| 12+ | 11 / 239 | 4.6% | 0.05 | 228 |
| 13+ | 3 / 239 | 1.3% | 0.01 | 236 |
| 14+ | 1 / 239 | 0.4% | 0.004 | 238 |
| 15+ … 25+ | 0 / 239 | 0% | 0 | 239 |

Practically every day carries a run of 6 — the three exceptions this year are
1 Mar, 16 May and 22 May. A run of 7 happens on 5 days out of 6, a run of 8 about
every other day, and a run of 10 roughly once a week. Above 12 the wall comes fast:
three runs of 13 all year, one of 14, and nothing longer — 15 and up simply did not
happen in 69 120 candles.

The longest runs of 2026 so far:

| start (ICT) | length | direction | move |
|---|---|---|---|
| 2026-03-03 14:55 | 14 | down | −1.84% |
| 2026-01-22 06:50 | 13 | up | +0.69% |
| 2026-03-09 13:00 | 13 | down | −1.07% |
| 2026-08-19 21:45 | 12 | up | +4.91% |
| 2026-06-09 20:50 | 12 | down | −1.97% |
| 2026-01-14 02:25 | 12 | up | +0.90% |

Note how little a long run has to move: the 14-candle run travelled 1.84% and the
12-candle run on 14 May only 0.60%. Length and size are close to unrelated — the
one outlier, +4.91% on 19 Aug, is a news candle.

### The one thing in this that looks tradeable

The candle *after* a run of 6+ reverses more often than it continues, and the effect
is concentrated in the Asian night (`nightfade.py`):

| window ICT | events | continues | reverses | p |
|---|---|---|---|---|
| 00:00–04:00 | 255 | 41.6% | **58.4%** | 0.007 |
| 04:00–08:00 | 273 | 41.8% | **58.2%** | 0.006 |
| 08:00–12:00 | 255 | 44.7% | 55.3% | 0.091 |
| 12:00–16:00 | 341 | 52.5% | 47.5% | 0.357 |
| 16:00–20:00 | 278 | 47.8% | 52.2% | 0.472 |
| 20:00–24:00 | 262 | 43.5% | 56.5% | 0.036 |
| **00:00–08:00** | **528** | **41.7%** | **58.3%** | **0.00013** |

It holds in both halves of the sample (41.8% continuation before 19 Jun, 36.7%
after) and in 7 of 8 months. Unlike everything else in this repo, 58% on a binary
payout does clear its cost — a 51¢ contract paying $1 breaks even at 51%.

Caveats: this is OKX's candle grid, so an app using another feed or another 5-minute
alignment is not measuring the same thing; a contract that settles on a rolling
window rather than on the candle close is a different bet; and the window was chosen
after looking at six of them, so forward-test it before sizing up.

### Files added

* `nightruns.py` — runs by hour, per-day coverage, coin-flip baseline
* `nightfade.py` — what the candle after a 6+ run does, by session and by month
* `report_nightruns.txt` — raw output

## Question 7 — martingale on the fade-after-6 signal

Rules simulated (`martingale.py`): when a run hits exactly 6 same-direction 5m
candles, buy the opposite side at 50¢ and close at 99¢ — a win pays +98% of the
stake, a loss costs 100%. Stake starts at $100 and doubles on the same side after
every loss until the sequence wins. The deposit reported is the deepest point of
the cash line, i.e. what the account actually has to hold.

### Last 100 days (23 May – 31 Aug 2026)

| | sequences | bets | worst losing streak | largest bet | profit | deposit needed |
|---|---|---|---|---|---|---|
| all hours | 405 | 733 | 6 | $6 400 | **$37 944** | **$9 456** |
| 00:00–08:00 ICT | 140 | 218 | 5 | $3 200 | $13 414 | $2 810 |
| all hours, capped at 4 doublings | 405 | 721 | 5 | $1 600 | $13 112 | $4 616 |

Hit rate on the signal in this window: 54.84% over all hours (403 bets), 64.75% at
night (139 bets). Break-even at 50¢/99¢ is 50.5%, so the signal itself is
profitable — that is where all the money comes from, not from the doubling.

### The doubling makes it worse, not better

Flat staking on exactly the same signals, scaled up until it needs the same deposit:

| | martingale | flat stake, same deposit |
|---|---|---|
| all hours, $9 456 deposit | $37 944 | **$53 429** (at $1 545 a bet) |
| night only, $2 810 deposit | $13 414 | **$36 474** (at $930 a bet) |

Same signals, same capital, same 100 days: flat betting earns 41% more over all
hours and nearly 3× more at night. The martingale spends its capital sitting in
recovery bets instead of in fresh ones.

At a 98% payout the doubling does not even recover fully. Winning on the k-th
double nets `100 − 2·2^k` dollars: +$98 with no losses, +$36 after five, and
**−$28 after six** — the $6 400 bet wins and the sequence still ends red. After
seven losses it is −$156, after eight −$412.

### The deposit is set by the tail, and 100 days did not contain it

Run the same rules over all 8 months and the picture changes:

| window | worst streak | largest bet | profit | deposit needed |
|---|---|---|---|---|
| last 100 days | 6 | $6 400 | $37 944 | $9 456 |
| full 8 months | **8** | **$25 600** | $84 042 | **$33 210** |

The 14-candle run on 3 March means 8 losses in a row: stake $25 600, $51 100
committed to one sequence, and the win that closes it books −$412. A 15-candle run
— which did not occur this year but a coin flip would have produced two — takes the
next bet to $51 200 and the deposit past $100 000. There is no streak length at
which the strategy is safe; there is only the streak you have not met yet.

### And it all hangs on the 99¢ exit

| buy | sell | payout | break-even | EV @ 53.6% | EV @ 58.3% |
|---|---|---|---|---|---|
| 50¢ | 99¢ | 98% | 50.5% | +$6.13 | +$15.43 |
| 50¢ | 90¢ | 80% | 55.6% | −$3.52 | +$4.94 |
| 51¢ | 95¢ | 86% | 53.7% | −$0.16 | +$8.60 |
| 51¢ | 85¢ | 67% | 60.0% | −$10.67 | −$2.83 |

Selling at 99¢ every time assumes a fill at one cent under settlement on every
winner. Give up nine cents of that and the all-hours version is already negative;
only the night filter survives.

**Verdict.** The edge is real and it is in the signal, not in the money management.
Flat stakes, night filter, and a hard check that the app really fills at those
prices. The martingale converts a modest positive expectation into a strategy whose
worst case is bounded only by the longest run that has not happened yet.

### Files added

* `martingale.py` — the doubling simulation, the flat-stake comparison at equal
  deposit, and the payout sensitivity table
* `report_martingale.txt` — raw output for 100 days and for the full sample

## Question 8 — bigger steps (×1.5 / ×2 / ×2.5 / ×3) and smaller base stakes

`martingale.py` now takes a step multiplier and a base stake. "+150% on each new
bet" is a ×2.5 step and "+200%" is a ×3 step; ×1.5 and ×2 are kept as reference.

### Does the step actually recover? (net result of one sequence, base $100)

| losses before the win | ×1.5 | ×2.0 | ×2.5 | ×3.0 |
|---|---|---|---|---|
| 0 | +$98 | +$98 | +$98 | +$98 |
| 1 | +$47 | +$96 | +$145 | +$194 |
| 2 | −$30 | +$92 | +$262 | +$482 |
| 4 | −$316 | +$68 | +$1 291 | +$3 938 |
| 6 | −$962 | −$28 | +$7 716 | +$35 042 |
| 8 | −$2 414 | −$412 | +$47 878 | +$314 978 |

At a 98% payout a step recovers at every depth only if it is at least
**×2.020** (= 1 + 1/0.98). So ×1.5 stops recovering after 2 losses, ×2 after 6,
and ×2.5 / ×3 always recover — that is the real difference between them.

### Last 100 days, all hours (profit and deposit scale linearly with the base)

| step | base $10 | base $25 | base $50 | base $100 | profit / deposit |
|---|---|---|---|---|---|
| ×1.5 | $1 366 / dep $176 | $3 415 / $441 | $6 830 / $882 | $13 661 / $1 764 | **7.74×** |
| ×2.0 | $3 794 / $946 | $9 486 / $2 364 | $18 972 / $4 728 | $37 944 / $9 456 | 4.01× |
| ×2.5 | $11 318 / $3 445 | $28 295 / $8 613 | $56 590 / $17 227 | $113 180 / $34 454 | 3.28× |
| ×3.0 | $30 868 / $9 810 | $77 170 / $24 524 | $154 341 / $49 048 | $308 682 / $98 096 | 3.15× |

Largest single bet at base $10: $114 (×1.5), $640 (×2), $2 441 (×2.5), $7 290 (×3).
Worst streak in this window: 6 losses.

### Last 100 days, 00:00–08:00 ICT only

| step | base $10 | base $25 | base $50 | base $100 | profit / deposit |
|---|---|---|---|---|---|
| ×1.5 | $827 / dep $113 | $2 067 / $282 | $4 133 / $563 | $8 266 / $1 127 | **7.34×** |
| ×2.0 | $1 341 / $281 | $3 354 / $702 | $6 707 / $1 405 | $13 414 / $2 810 | 4.77× |
| ×2.5 | $2 423 / $606 | $6 058 / $1 514 | $12 117 / $3 028 | $24 233 / $6 056 | 4.00× |
| ×3.0 | $4 521 / $1 298 | $11 302 / $3 246 | $22 604 / $6 492 | $45 208 / $12 984 | 3.48× |

Worst streak at night: 5 losses. Fewer signals (140 vs 405) but a better hit rate.

### The same grid over the full 8 months, where the worst streak is 8

| step | deposit, base $10 | deposit, base $100 | largest bet, base $100 | profit / deposit |
|---|---|---|---|---|
| ×1.5 | $392 | $3 920 | $2 563 | 6.22× |
| ×2.0 | $3 321 | $33 210 | $25 600 | 2.53× |
| ×2.5 | $18 539 | $185 388 | $152 588 | 1.83× |
| ×3.0 | $75 264 | $752 642 | $656 100 | 1.60× |

One extra losing candle multiplies the requirement by the step, so ×3 at base $100
needs three quarters of a million dollars to survive a run that already happened
this year. Return on capital moves the other way from the step size: the mildest
progression is the most efficient, and flat staking at the same deposit
($9 456 → $1 545 a bet) still earned $53 429 over these 100 days — more than ×2
and at a fraction of ×2.5's exposure.

**What the grid says.** Steeper steps buy a guarantee that any single sequence
closes green, and pay for it with a deposit that grows as `stepᵏ`. The profit
column grows too, but only because more money is at risk — profit per dollar of
deposit falls monotonically from ×1.5 to ×3. Nothing here changes the edge; it only
changes how much capital is standing behind the same 54–65% hit rate.

## Question 9 — the same thing, but entering after 7 in a row

`martingale.py` now takes the trigger length as a third argument
(`python3 martingale.py candles240.json.gz 100 7`).

### The signal itself is better after 7 than after 6

| trigger | window | bets | fade wins | p |
|---|---|---|---|---|
| after 6 | 8 months, all hours | 906 | 53.64% | 0.028 |
| **after 7** | 8 months, all hours | 418 | **57.42%** | 0.002 |
| after 8 | 8 months, all hours | 178 | 52.81% | 0.454 |
| after 6 | 8 months, night | 307 | 58.31% | 0.004 |
| **after 7** | 8 months, night | 128 | **64.84%** | 0.001 |
| after 8 | 8 months, night | 45 | 46.67% | 0.655 |

Waiting one more candle raises the hit rate by ~4 points and halves the number of
signals. Waiting two more destroys it — after 8 the sample is thin and the effect
is gone, so 7 is the last usable trigger, not a trend to extrapolate.

### Last 100 days, entry after 7, all hours

| step | base $10 | base $25 | base $50 | base $100 | profit / deposit |
|---|---|---|---|---|---|
| ×1.5 | $671 / dep $134 | $1 676 / $336 | $3 353 / $671 | $6 705 / $1 343 | **4.99×** |
| ×2.0 | $1 715 / $476 | $4 286 / $1 190 | $8 573 / $2 380 | $17 146 / $4 760 | 3.60× |
| ×2.5 | $4 379 / $1 381 | $10 948 / $3 452 | $21 896 / $6 904 | $43 792 / $13 807 | 3.17× |
| ×3.0 | $10 164 / $3 272 | $25 411 / $8 180 | $50 822 / $16 360 | $101 644 / $32 720 | 3.11× |

182 sequences, 327 bets, worst streak 5 losses, hit rate 55.25%.

### Last 100 days, entry after 7, 00:00–08:00 ICT

| step | base $10 | base $25 | base $50 | base $100 | profit / deposit |
|---|---|---|---|---|---|
| ×1.5 | $283 / dep $62 | $708 / $154 | $1 416 / $308 | $2 832 / $616 | **4.59×** |
| ×2.0 | $470 / $130 | $1 174 / $326 | $2 349 / $652 | $4 698 / $1 304 | 3.60× |
| ×2.5 | $809 / $234 | $2 022 / $585 | $4 043 / $1 171 | $8 086 / $2 342 | 3.45× |
| ×3.0 | $1 373 / $532 | $3 432 / $1 330 | $6 865 / $2 660 | $13 730 / $5 320 | 2.58× |

Only 49 sequences in 100 days — one every other day. Worst streak 4 losses.

### Full 8 months, entry after 7, all hours (worst streak 7)

| step | deposit, base $10 | deposit, base $100 | largest bet, base $100 | profit / deposit |
|---|---|---|---|---|
| ×1.5 | $217 | $2 172 | $1 709 | 5.71× |
| ×2.0 | $1 651 | $16 508 | $12 800 | 2.37× |
| ×2.5 | $7 408 | $74 078 | $61 035 | 1.80× |
| ×3.0 | $25 082 | $250 816 | $218 700 | 1.59× |

### Trigger 6 vs trigger 7, side by side

| | after 6 | after 7 |
|---|---|---|
| sequences per day | 4.05 | 1.82 |
| hit rate, 8 months | 53.6% | **57.4%** |
| worst streak, 100 days | 6 | **5** |
| worst streak, 8 months | 8 | **7** |
| ×2 deposit, base $100, 8 months | $33 210 | **$16 508** |
| ×2 profit, base $100, 8 months | $84 042 | $39 110 |
| ×2.5 deposit, base $10, 8 months | $18 539 | **$7 408** |

Entering after 7 halves the capital requirement — the run has to get one candle
longer before each doubling, so the tail is one step shorter — and it raises the
hit rate. It also halves the trade count and with it the profit. Per dollar of
deposit the two are close (2.53× vs 2.37× at ×2 over 8 months); the real gain is
that the worst case is half as bad.

Flat staking at the same deposit still wins on this trigger too: $3 013 a bet over
the last 100 days earns $51 215 against the ×2 martingale's $17 146.

## Question 10 — the best strategy the data actually supports

`strategy.py` answers the sizing question directly instead of ranking martingale
variants against each other.

### Why any progression is the wrong tool here

Fade win rate by the run length at which the bet is placed:

| run length | all hours | n | night 00–08 | n |
|---|---|---|---|---|
| 5 | 51.6% | 1876 | 52.1% | 643 |
| 6 | 53.6% | 906 | 58.3% | 307 |
| **7** | **57.4%** | 418 | **64.8%** | 128 |
| 8 | 52.8% | 178 | 46.7% | 45 |
| 9 | 50.0% | 84 | 50.0% | 24 |
| 10 | 50.0% | 42 | 46.2% | 13 |

A martingale triggered at 7 places its recovery bets at runs 8, 9, 10 — rows where
the edge is gone. It scales the stake up exactly as the edge goes to zero. That is
the whole case against it, and it is not a risk-tolerance question.

Measured per dollar at risk, the step multiplier changes nothing:

| scheme | EV per $1 of maximum exposure |
|---|---|
| one bet at run 7, all hours | **+0.137** |
| one bet at run 7, night | **+0.284** |
| bet at 6, then ×1.5 at 7 | +0.063 |
| bet at 6, then ×2.0 at 7 | +0.063 |
| bet at 6, then ×2.5 at 7 | +0.063 |

Steeper steps raise both the profit and the exposure by the same factor. The single
trigger-7 bet is twice as efficient as any two-step version.

### Sizing: flat fraction of bankroll, 8 months of trades, $1000 start

All hours, 418 trades at 57.42% (95% CI 52.7–62.2%), full Kelly = 14.0%:

| stake | median | 5th pct | 95th pct | median DD | 95th DD | P(bankroll halved) |
|---|---|---|---|---|---|---|
| 1% | $1 736 | $1 240 | $2 383 | 10.5% | 17.8% | 0.0% |
| **2%** | **$2 893** | $1 475 | $5 453 | 20.2% | 33.1% | 0.0% |
| 3% | $4 630 | $1 686 | $11 983 | 29.1% | 46.1% | 0.0% |
| 5% | $10 493 | $1 946 | $51 257 | 44.9% | 66.0% | 0.1% |
| 10% | $39 786 | $1 354 | $958 265 | 73.5% | 91.7% | 1.4% |
| 14% (Kelly) | $55 154 | $483 | $4.7M | 87.1% | 98.1% | 5.5% |

Night only, 128 trades at 64.84%: 2% → median $2 018, 95th-pct drawdown 16.9%;
5% → median $5 271, drawdown 38.1%.

### Fragility

Same 2% plan, 418 trades, if the true win rate is not 57.4%:

| assumed rate | median | 5th pct |
|---|---|---|
| 57.4% (point estimate) | $2 893 | $1 475 |
| 52.7% (CI lower bound) | $1 310 | $668 |
| 50.5% (break-even price) | $917 | $468 |

The plan survives a moderate overestimate and dies at break-even, which is what a
2% stake is for: it keeps the 5th percentile above water while the estimate is
still being confirmed live.

### The recommendation

1. **Signal**: fade a run of exactly 7 — enter opposite on the next candle. Highest
   measured edge, and one candle later than 6 means half the capital at risk.
2. **No progression, ever.** One bet per signal. Run 8 and beyond is a coin flip;
   that is where every doubling scheme puts its biggest money.
3. **Stake 2% of the current bankroll**, recomputed as it moves. That is roughly
   quarter-Kelly on the CI lower bound — the level whose 5th percentile stays above
   the starting bankroll. 3% if the night filter is on and you can watch it.
4. **Weight the night**: 00:00–08:00 ICT signals are worth about twice the day ones
   (+0.284 vs +0.137 per $1). Double the stake there, or trade only that window and
   accept ~16 trades a month instead of ~52.
5. **Stop rule**: log every bet. After 200 live bets, if the hit rate is under 52%,
   the edge is not there at your fill prices — stop.
6. **If you cannot resist a recovery bet**: allow exactly one, at run 7 after a
   run-6 loss, step ×2 maximum, and never a third. Both of those bets are +EV; a
   third is not.

Bet size for a given bankroll at 2%: $500 → $10, $1 000 → $20, $2 500 → $50,
$5 000 → $100. Every number above assumes fills at 50¢ in and 99¢ out; check that
first on ten live trades, because at 51¢/90¢ the all-hours version is already flat.

## Question 11 — expected profit and the deposit it needs

`expected.py` walks the real 8-month signal list under the recommended plan (fade a
run of 7, one bet per signal, stake as a % of the current bankroll) and then
resamples it 5000 times for the spread.

418 signals over 8 months = 52 a month, 16 of them at night. No chasing means no
escalating exposure, so **the deposit is just the bankroll** — there is no margin to
hold behind it.

### Starting from $1000

| plan | 8-month median | median $/month | median DD | 95th DD | worst month (5th pct) | P(ending below start) |
|---|---|---|---|---|---|---|
| 1% flat | $1 736 | +$92 | 10.4% | 17.3% | −$136 | 0.2% |
| **2% flat** | **$2 893** | **+$237** | 20.1% | 32.3% | −$401 | 0.4% |
| 2% day / 4% night | $5 628 | +$579 | 24.8% | 38.2% | −$791 | 0.1% |
| night only, 3% | $2 828 | +$229 | 14.5% | 24.4% | −$279 | 0.1% |

Month by month on the actual path at 2% flat: +268, +376, +269, +288, +133, **−97**,
+182, +474. One losing month in eight.

### Scaled to other bankrolls (2% flat)

| deposit | bet size | median after 8 months | median $/month | worst month (5th pct) |
|---|---|---|---|---|
| $100 | $2 | $289 | +$24 | −$40 |
| $250 | $5 | $723 | +$59 | −$100 |
| $500 | $10 | $1 447 | +$118 | −$200 |
| $1 000 | $20 | $2 893 | +$237 | −$401 |
| $2 500 | $50 | $7 233 | +$592 | −$1 002 |
| $5 000 | $100 | $14 467 | +$1 183 | −$2 005 |

Everything is linear in the deposit. The floor is set by the app's minimum bet: at
a $1 minimum you need about $100 so that 2% still clears it after a 35% drawdown.

### The same plan if the edge is weaker than measured

| assumed win rate | EV per bet | per month at 2% | over 8 months |
|---|---|---|---|
| 57.4% (measured) | +0.137 | **+14.1%** | ×2.88 |
| 52.7% (CI lower bound) | +0.044 | +3.6% | ×1.32 |
| 50.5% (break-even price) | −0.000 | −1.0% | ×0.92 |

That spread is the honest answer: the plan makes 14% a month if the historical rate
holds, 3.6% a month at the pessimistic end of the confidence interval, and slowly
bleeds if the fills are worse than 50¢/99¢. Which of the three you get is decided by
the fill prices and by whether 57% survives out of sample — not by the sizing.

## Question 12 — the user's plan: 7-run fade, double on loss, base grows with profit

`mycompound.py`. First entry $50; the base stake stays a constant share of the
bankroll, so every win raises the size of the next sequence; inside a sequence the
stake doubles on the same side after each loss. Data extended to 330 days
(95 040 candles, 5 Nov 2025 – 1 Sep 2026) so that a 300-day window is real data
rather than a repeat of the 8-month sample.

### How deep the account has to be able to go

| losses to fund | run reaches | deposit needed at a $50 base | largest bet |
|---|---|---|---|
| 4 | 11 | $1 550 | $800 |
| 5 | 12 | $3 150 | $1 600 |
| 6 | 13 | $6 350 | $3 200 |
| 7 | 14 | $12 750 | $6 400 |
| **8** | **15** | **$25 550** | $12 800 |
| 9 | 16 | $51 150 | $25 600 |

### 100 days (24 May – 1 Sep 2026): 185 sequences, deepest loss streak 5

| deposit | final | multiple | largest bet | max DD | ruin on this path | MC ruin |
|---|---|---|---|---|---|---|
| $500 | $218 | 0.44× | $291 | 100% | **RUIN** | 100% |
| $1 000 | $482 | 0.48× | $771 | 100% | **RUIN** | 100% |
| $2 500 | $1 240 | 0.50× | $1 044 | 100% | **RUIN** | 98.4% |
| $5 000 | $28 349 | **5.67×** | $7 198 | 0% | no | 0% |
| $12 750 | $25 228 | 1.98× | $2 891 | 0% | no | 0% |
| $25 550 | $35 927 | 1.41× | $2 150 | 0% | no | 0% |

### 300 days (5 Nov 2025 – 1 Sep 2026): 517 sequences, deepest loss streak **8**

| deposit | final | multiple | largest bet | ruin on this path | MC ruin |
|---|---|---|---|---|---|
| $2 500 | $1 287 | 0.51× | $1 084 | **RUIN** | 100% |
| $5 000 | $2 155 | 0.43× | $1 864 | **RUIN** | 99.5% |
| $12 750 | **$0** | 0.00× | $7 545 | **RUIN** | 75.9% |
| $25 550 | $65 018 | 2.54× | $13 897 | no | 10.7% |

The 100-day window contained nothing longer than a 12-run. The 300-day window
contains the 15-candle run down of **1 Dec 2025, 06:45 ICT (−4.03%)** — eight losses
in a row. That single sequence takes the $12 750 account, which was up several times
over by then, to exactly zero. $5 000 — the best-looking row over 100 days at 5.67× —
is dead within 300.

### Against the same first bet, staked flat

| plan | deposit | first bet | 300-day result | max DD | ruin risk |
|---|---|---|---|---|---|
| flat 2%, no chasing | $2 500 | $50 | $7 100 (**2.84×**) | 20.1% | none |
| flat 2%, no chasing | $25 550 | $511 | 2.84× → $72 600 | 20.1% | none |
| user's martingale | $25 550 | $50 | $65 018 (2.54×) | — | 10.7% |

Flat staking returns a higher multiple than the martingale, on a tenth of the
deposit, with no path to zero. Scaled to the same $25 550 it earns more in absolute
dollars too — $47 000 against $39 500 — because the martingale spends its capital
funding doubles at runs 8–15 where the win rate is 50%, while the flat plan spends
every dollar on the 57% bet.

**The number to take away.** For this plan the honest minimum deposit is $25 550 at a
$50 base — 511× the first bet — and even that carries a ~10% chance of ruin over 300
days. Compounding the base makes it worse, not better: the base rises after wins, so
the losing sequence, when it comes, is priced off a larger bankroll. To halve the
requirement, cut the first entry to $25.

## Question 13 — riding the winner: one more bet, same side, double stake

`ride.py`. After the fade sequence finally wins, place one more bet in the
direction that just won, at twice the winning stake.

### The ride bet on its own

| window | session | cases | same side again | P | p-value |
|---|---|---|---|---|---|
| 100 days | all hours | 99 | 47 | 47.47% | 0.615 |
| 100 days | night | 28 | 16 | 57.14% | 0.450 |
| 300 days | all hours | 288 | 148 | **51.39%** | 0.637 |
| 300 days | night | 99 | 57 | 57.58% | 0.132 |

51.4% against a 50.5% break-even. Statistically it is a coin flip — the reversal
candle that just paid says nothing about the one after it.

### 100 days — it looks like a discovery

| deposit | baseline | + ride | + ride and chase |
|---|---|---|---|
| $5 000 | 5.67× | **15.12×** | RUIN |
| $12 750 | 1.98× | 3.62× | RUIN |
| $25 550 | 1.41× | 1.98× | 9.58× |

### 300 days — it is the opposite

| deposit | baseline | + ride | + ride and chase |
|---|---|---|---|
| $5 000 | RUIN | RUIN | RUIN |
| $12 750 | RUIN ($0) | RUIN | RUIN |
| $25 550 | **2.54×** (ruin risk 11%) | **0.75× — RUIN** | RUIN |
| $51 150 | 1.59× | **0.50×** | RUIN (100% in MC) |

The ride turns the one deposit that survived into a loser, and the "chase the ride
too" version wipes out every deposit up to $51 150 with 100% ruin in simulation.
Overall hit rate falls from 55.0% to 52.8% — the added bets are coin flips — and the
bet count rises from 940 to 1457.

### Why it breaks the account when the bet is roughly break-even

The damage is the sizing, not the edge. Doubling the *winning* stake means a
sequence that just fought its way out of five losses hands the ride bet 64× the
base — $3 200 at a $50 base, and 512× ($25 600) at the worst point of the 300-day
sample. A near-coin-flip placed at that size relative to the bankroll has negative
geometric growth even with a slightly positive expectation: over-betting eats the
edge.

Proof that it is sizing and not the signal — the same extra bet, kept at the base
stake instead of double the winner:

| ride stake | 300 days at $25 550 | MC ruin |
|---|---|---|
| none (baseline) | 2.54× | 11.2% |
| 1× base | 2.45× | 11.7% |
| 2× base | 2.36× | 10.9% |
| 2× the winning stake | **0.75×, RUIN** | 63.7% |

At a small fixed size the ride is harmless and pointless; at double the winner it is
fatal. Do not add it.
