#!/usr/bin/env python3
"""The mean-reversion signal the search kept finding, traded with a target.

Signal: the candle both makes a 20-candle extreme and closes in the far quarter
of its own range — an exhausted push. Fade it: long the new low, short the new
high. Exit on TP, SL, or after `hold` candles. Train/test split is the same 70/30
as search.py, so the test column is untouched data.
"""
import sys
from datetime import datetime, timezone

import search

FEE = 0.10  # % round trip, taker both sides


def signals(k):
    n = len(k)
    h = [x[2] for x in k]; lo = [x[3] for x in k]; c = [x[4] for x in k]
    out = []
    for i in range(20, n - 1):
        rng = h[i] - lo[i]
        if rng <= 0:
            continue
        pos = (c[i] - lo[i]) / rng
        if lo[i] <= min(lo[i - 19:i + 1]) and pos < 0.25:
            out.append((i, 1))                     # new 20-low, closed at bottom -> long
        elif h[i] >= max(h[i - 19:i + 1]) and pos > 0.75:
            out.append((i, -1))                    # new 20-high, closed at top -> short
    return out


def run(k, sigs, tp, sl, hold):
    wins = losses = 0
    pnl = 0.0
    for i, side in sigs:
        if i + 1 + hold >= len(k):
            continue
        entry = k[i + 1][1]
        tp_px = entry * (1 + side * tp / 100)
        sl_px = entry * (1 - side * sl / 100)
        done = False
        for j in range(i + 1, i + 1 + hold):
            hi, low = k[j][2], k[j][3]
            if (low <= sl_px) if side > 0 else (hi >= sl_px):
                losses += 1; pnl -= sl; done = True; break
            if (hi >= tp_px) if side > 0 else (low <= tp_px):
                wins += 1; pnl += tp; done = True; break
        if not done:
            r = side * (k[i + hold][4] - entry) / entry * 100
            pnl += r
            wins += r > 0
            losses += r <= 0
    n = wins + losses
    return {"n": n, "win": 100 * wins / n if n else 0,
            "avg": pnl / n if n else 0, "net": pnl / n - FEE if n else 0,
            "total_net": pnl - n * FEE}


def main(path="candles240.json"):
    k = search.load(path)
    split = int(len(k) * 0.7)
    sigs = signals(k)
    tr = [s for s in sigs if s[0] < split]
    te = [s for s in sigs if s[0] >= split]
    t0 = datetime.fromtimestamp(k[0][0] / 1000, timezone.utc)
    t1 = datetime.fromtimestamp(k[-1][0] / 1000, timezone.utc)
    print(f"{len(k)} candles {t0:%Y-%m-%d}..{t1:%Y-%m-%d}; "
          f"{len(sigs)} signals ({len(tr)} train / {len(te)} test)\n")
    hdr = (f"{'TP%':>6}{'SL%':>7}{'hold':>6} | {'n_tr':>6}{'win%_tr':>9}{'net%/tr':>9}"
           f" | {'n_te':>6}{'win%_te':>9}{'net%/tr':>9}{'net total%':>12}")
    print(hdr); print("-" * len(hdr))
    for tp, sl, hold in ((0.05, 0.50, 24), (0.10, 0.50, 24), (0.10, 1.00, 48),
                         (0.15, 0.60, 36), (0.20, 1.00, 48), (0.20, 0.60, 36),
                         (0.30, 0.60, 48), (0.30, 0.30, 24), (0.50, 0.50, 48)):
        a = run(k[:split], tr, tp, sl, hold)
        b = run(k, [s for s in te if s[0] + hold + 1 < len(k)], tp, sl, hold)
        print(f"{tp:>6.2f}{sl:>7.2f}{hold:>6} | {a['n']:>6}{a['win']:>9.2f}{a['net']:>9.4f}"
              f" | {b['n']:>6}{b['win']:>9.2f}{b['net']:>9.4f}{b['total_net']:>12.1f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json")
