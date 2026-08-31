#!/usr/bin/env python3
"""Month-by-month stability of the fade signal, and what fee level it needs.

A single train/test split can hide a signal that only worked in one regime, so
this walks the whole sample month by month and reports the gross edge per trade
at a few horizons. Fee columns show taker (0.10% round trip) and maker (0.04%),
since a fade signal can be entered with a resting limit order.
"""
import sys
from collections import defaultdict
from datetime import datetime, timezone

import search
import signal_tpsl
from horizon import describe, fwd

TAKER, MAKER = 0.10, 0.04


def main(path="candles240.json.gz", horizons=(3, 8, 20)):
    k = search.load(path)
    sigs = signal_tpsl.signals(k)
    month = [datetime.fromtimestamp(x[0] / 1000, timezone.utc).strftime("%Y-%m") for x in k]

    for h in horizons:
        buckets = defaultdict(list)
        for i, side in sigs:
            if i + h < len(k):
                buckets[month[i]].append(fwd(k, i, h, side))
        print(f"\nhorizon {h} candles ({5*h} min)")
        hdr = f"{'month':<9}{'n':>6}{'win%':>8}{'gross avg%':>12}{'t':>7}{'net@maker':>11}{'net@taker':>11}"
        print(hdr); print("-" * len(hdr))
        allv = []
        for m in sorted(buckets):
            v = buckets[m]; allv += v
            n, avg, win, t = describe(v)
            print(f"{m:<9}{n:>6}{win:>8.2f}{avg:>12.4f}{t:>7.2f}"
                  f"{avg-MAKER:>11.4f}{avg-TAKER:>11.4f}")
        n, avg, win, t = describe(allv)
        print("-" * len(hdr))
        print(f"{'ALL':<9}{n:>6}{win:>8.2f}{avg:>12.4f}{t:>7.2f}"
              f"{avg-MAKER:>11.4f}{avg-TAKER:>11.4f}")
        print(f"break-even round-trip fee: {avg:.4f}%  "
              f"(months with positive gross: {sum(1 for m in buckets if describe(buckets[m])[1] > 0)}/{len(buckets)})")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz")
