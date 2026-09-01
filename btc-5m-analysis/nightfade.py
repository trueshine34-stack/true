#!/usr/bin/env python3
"""After 6+ same-direction 5m candles, does the Asian night reverse more often?

Reports the continuation rate of the candle right after a run of 6 or more,
split by 4-hour ICT window, then month by month for the 00:00-08:00 block.
"""
import sys
from collections import defaultdict
from datetime import datetime
from math import erfc, sqrt

import nightruns
import search


def build(k):
    t = [datetime.fromtimestamp(x[0] / 1000, nightruns.ICT) for x in k]
    d = [(x[4] > x[1]) - (x[4] < x[1]) for x in k]
    run = [0] * len(k)
    for i in range(len(k)):
        run[i] = run[i - 1] + 1 if i and d[i] and d[i] == d[i - 1] else (1 if d[i] else 0)
    return t, d, run


def pval(cont, n):
    return erfc(abs((cont - n / 2) / sqrt(n / 4)) / sqrt(2)) if n else 1.0


def main(path="candles240.json.gz", min_run=6):
    k = search.load(path)
    t, d, run = build(k)
    events = [(i, t[i]) for i in range(len(k) - 1) if run[i] >= min_run and d[i + 1] != 0]
    cont = {i: d[i + 1] == d[i] for i, _ in events}

    print(f"runs of {min_run}+ 5m candles, {len(events)} events, ICT (UTC+7)\n")
    hdr = f"{'window ICT':<12}{'n':>6}{'continues':>11}{'reverses':>10}{'p':>8}"
    print(hdr); print("-" * len(hdr))
    for h0 in range(0, 24, 4):
        sub = [i for i, tt in events if h0 <= tt.hour < h0 + 4]
        c = sum(cont[i] for i in sub); n = len(sub)
        print(f"{h0:02d}:00-{h0+4:02d}:00{'':<1}{n:>6}{100*c/n:>10.2f}%{100*(n-c)/n:>9.2f}%{pval(c, n):>8.3f}")

    night = [i for i, tt in events if tt.hour < 8]
    c, n = sum(cont[i] for i in night), len(night)
    print(f"\n00:00-08:00 ICT: n={n}, continues {100*c/n:.2f}%, reverses {100*(n-c)/n:.2f}%, "
          f"p={pval(c, n):.5f}")
    day = [i for i, tt in events if tt.hour >= 8]
    c2, n2 = sum(cont[i] for i in day), len(day)
    print(f"08:00-24:00 ICT: n={n2}, continues {100*c2/n2:.2f}%, reverses {100*(n2-c2)/n2:.2f}%, "
          f"p={pval(c2, n2):.3f}")

    print("\n00:00-08:00 ICT month by month")
    hdr = f"{'month':<9}{'n':>6}{'reverses':>10}"
    print(hdr); print("-" * len(hdr))
    by_m = defaultdict(list)
    for i in night:
        by_m[t[i].strftime("%Y-%m")].append(i)
    for m in sorted(by_m):
        sub = by_m[m]; c = sum(cont[i] for i in sub)
        print(f"{m:<9}{len(sub):>6}{100*(len(sub)-c)/len(sub):>9.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz",
         int(sys.argv[2]) if len(sys.argv) > 2 else 6)
