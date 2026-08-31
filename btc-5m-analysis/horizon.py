#!/usr/bin/env python3
"""Does the fade-the-extreme signal pay over more than one candle?

For every signal, forward return is measured from the next candle's open to the
close h candles later, and compared with the unconditional return over the same
horizon and the same side — that subtracts the market's own drift, which on this
sample is strongly upward and would otherwise flatter every long.
"""
import math
import sys

import search
import signal_tpsl

FEE = 0.10  # % round trip
HORIZONS = (1, 2, 3, 5, 8, 10, 15, 20, 30, 40, 60)


def fwd(k, i, h, side):
    """Return in % of entering at open of i+1 and exiting at close of i+h."""
    entry = k[i + 1][1]
    return side * (k[i + h][4] - entry) / entry * 100


def describe(vals):
    n = len(vals)
    if n < 2:
        return 0, 0.0, 0.0, 0.0
    m = sum(vals) / n
    sd = math.sqrt(sum((v - m) ** 2 for v in vals) / (n - 1))
    t = m / (sd / math.sqrt(n)) if sd else 0.0
    win = 100 * sum(1 for v in vals if v > 0) / n
    return n, m, win, t


def baseline(k, h, side, lo, hi):
    """Unconditional return over the same horizon, same side, same slice."""
    vals = [fwd(k, i, h, side) for i in range(lo, min(hi, len(k) - h - 1))]
    return sum(vals) / len(vals) if vals else 0.0


def table(k, sigs, lo, hi, title):
    print(f"\n{title}  ({len(sigs)} signals)")
    hdr = (f"{'h (candles)':<12}{'minutes':>8}{'n':>7}{'win%':>8}{'avg%':>9}"
           f"{'t-stat':>8}{'drift%':>9}{'edge%':>9}{'net%':>9}{'total net%':>12}")
    print(hdr); print("-" * len(hdr))
    for h in HORIZONS:
        vals, sides = [], []
        for i, side in sigs:
            if i + h < hi and i + 1 < len(k) and i + h < len(k):
                vals.append(fwd(k, i, h, side))
                sides.append(side)
        n, m, win, t = describe(vals)
        if not n:
            continue
        # drift for this mix of longs/shorts
        share_long = sum(1 for s in sides if s > 0) / n
        drift = share_long * baseline(k, h, 1, lo, hi) + (1 - share_long) * baseline(k, h, -1, lo, hi)
        print(f"{h:<12}{5*h:>8}{n:>7}{win:>8.2f}{m:>9.4f}{t:>8.2f}"
              f"{drift:>9.4f}{m - drift:>9.4f}{m - FEE:>9.4f}{(m - FEE) * n:>12.1f}")


def main(path="candles240.json.gz"):
    k = search.load(path)
    split = int(len(k) * 0.7)
    sigs = signal_tpsl.signals(k)
    tr = [s for s in sigs if s[0] < split]
    te = [s for s in sigs if s[0] >= split]
    print(f"{len(k)} candles, split at {split}. Fade signal: 20-candle extreme "
          f"closing in the far quarter of its range.")
    table(k, tr, 60, split, "TRAIN")
    table(k, te, split, len(k), "TEST (untouched)")
    for lbl, side in (("longs only", 1), ("shorts only", -1)):
        table(k, [s for s in te if s[1] == side], split, len(k), f"TEST — {lbl}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz")
