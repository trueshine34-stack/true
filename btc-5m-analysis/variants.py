#!/usr/bin/env python3
"""Does a stricter version of the fade signal earn more per trade?

Fewer, better trades is the only way past the fee: the base signal's gross edge
is ~0.03% and a round trip costs 0.04-0.10%. Each variant tightens the filter
and the question is whether gross % per trade grows faster than the count falls.
"""
import sys

import search
from horizon import describe, fwd

H = 20  # candles held


def build(k, lookback, pos_q, min_range_mult, min_run):
    n = len(k)
    h = [x[2] for x in k]; lo = [x[3] for x in k]
    o = [x[1] for x in k]; c = [x[4] for x in k]
    rng = [h[i] - lo[i] for i in range(n)]
    r10 = [0.0] * n
    s = sum(rng[:10])
    for i in range(10, n):
        r10[i] = s / 10
        s += rng[i] - rng[i - 10]
    run = [0] * n
    for i in range(1, n):
        d = (c[i] > o[i]) - (c[i] < o[i])
        p = (c[i-1] > o[i-1]) - (c[i-1] < o[i-1])
        run[i] = run[i - 1] + 1 if d and d == p else (1 if d else 0)
    out = []
    for i in range(max(lookback, 20), n - H - 1):
        if rng[i] <= 0 or rng[i] < min_range_mult * r10[i] or run[i] < min_run:
            continue
        pos = (c[i] - lo[i]) / rng[i]
        if lo[i] <= min(lo[i - lookback + 1:i + 1]) and pos < pos_q:
            out.append((i, 1))
        elif h[i] >= max(h[i - lookback + 1:i + 1]) and pos > 1 - pos_q:
            out.append((i, -1))
    return out


def main(path="candles240.json.gz"):
    k = search.load(path)
    variants = [
        ("base: 20-extreme, close in far 25%", 20, 0.25, 0.0, 0),
        ("far 15% of range", 20, 0.15, 0.0, 0),
        ("50-candle extreme", 50, 0.25, 0.0, 0),
        ("100-candle extreme", 100, 0.25, 0.0, 0),
        ("50-extreme + far 15%", 50, 0.15, 0.0, 0),
        ("50-extreme + range > 1.5 avg10", 50, 0.25, 1.5, 0),
        ("50-extreme + range > 2 avg10", 50, 0.25, 2.0, 0),
        ("100-extreme + range>1.5 + run>=3", 100, 0.25, 1.5, 3),
        ("100-extreme + far 15% + range>2", 100, 0.15, 2.0, 0),
    ]
    hdr = (f"{'variant':<36}{'n':>6}{'per month':>11}{'win%':>8}"
           f"{'gross%':>9}{'t':>7}{'break-even fee%':>17}")
    print(f"holding {H} candles ({5*H} min), 8 months of 5m BTC\n")
    print(hdr); print("-" * len(hdr))
    for label, lb, pq, rm, mr in variants:
        sigs = build(k, lb, pq, rm, mr)
        vals = [fwd(k, i, H, side) for i, side in sigs]
        n, avg, win, t = describe(vals)
        if not n:
            continue
        print(f"{label:<36}{n:>6}{n/8:>11.0f}{win:>8.2f}{avg:>9.4f}{t:>7.2f}{avg:>17.4f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz")
