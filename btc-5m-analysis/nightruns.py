#!/usr/bin/env python3
"""Long runs of same-direction 5m candles: when they happen and how often.

Two questions:
  1) are runs of 6+ concentrated in the Vietnam night (03:00-07:00 ICT = UTC+7)?
  2) does every day contain a run of 6 / 7 / 8 / 9 / 10 in a row?

A run is a maximal block of consecutive candles closing the same way; a doji
breaks it. A run is credited to the hour and the day (ICT) of its first candle.
"""
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

import search

ICT = timezone(timedelta(hours=7))
NIGHT = range(3, 7)          # 03:00-06:59 ICT
LEVELS = tuple(range(6, 26))


def runs_of(k):
    """[(start_index, length, direction)] for every maximal same-direction block."""
    out = []
    start, cur = 0, 0
    for i, x in enumerate(k):
        d = (x[4] > x[1]) - (x[4] < x[1])
        if d == 0:
            cur = 0
            continue
        if cur and d == prev:
            cur += 1
        else:
            start, cur = i, 1
        prev = d
        if i + 1 == len(k) or True:
            pass
        # record only at the end of the block
        nxt = None
        if i + 1 < len(k):
            nxt = (k[i + 1][4] > k[i + 1][1]) - (k[i + 1][4] < k[i + 1][1])
        if nxt != d:
            out.append((start, cur, d))
    return out


def main(path="candles240.json.gz"):
    k = search.load(path)
    t = [datetime.fromtimestamp(x[0] / 1000, ICT) for x in k]
    days = sorted({d.strftime("%Y-%m-%d") for d in t})
    rs = runs_of(k)
    print(f"{len(k)} candles of 5m BTC, {t[0]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d} "
          f"({len(days)} days), all times ICT (UTC+7, Vietnam)\n")

    # --- 1. by hour -----------------------------------------------------
    per_hour_candles = defaultdict(int)
    for d in t:
        per_hour_candles[d.hour] += 1
    print("runs of 6+ in a row, by hour of the run's first candle")
    hdr = f"{'hour ICT':<10}{'runs 6+':>9}{'runs 8+':>9}{'runs 10+':>10}{'per 1000 candles':>18}"
    print(hdr); print("-" * len(hdr))
    by_hour = defaultdict(lambda: [0, 0, 0])
    for i, ln, d in rs:
        h = t[i].hour
        if ln >= 6: by_hour[h][0] += 1
        if ln >= 8: by_hour[h][1] += 1
        if ln >= 10: by_hour[h][2] += 1
    for h in range(24):
        a, b, c = by_hour[h]
        rate = 1000 * a / per_hour_candles[h]
        bar = "#" * round(rate * 3)
        mark = " <- night" if h in NIGHT else ""
        print(f"{h:02d}:00{'':<5}{a:>9}{b:>9}{c:>10}{rate:>18.2f}  {bar}{mark}")
    n_night = sum(by_hour[h][0] for h in NIGHT)
    c_night = sum(per_hour_candles[h] for h in NIGHT)
    n_rest = sum(by_hour[h][0] for h in range(24) if h not in NIGHT)
    c_rest = sum(per_hour_candles[h] for h in range(24) if h not in NIGHT)
    print(f"\n03:00-07:00 ICT : {n_night} runs of 6+ per {c_night} candles = "
          f"{1000*n_night/c_night:.2f} per 1000")
    print(f"rest of the day : {n_rest} runs of 6+ per {c_rest} candles = "
          f"{1000*n_rest/c_rest:.2f} per 1000")
    print(f"ratio night / day: {(n_night/c_night)/(n_rest/c_rest):.3f}x")

    # --- 2. per-day coverage --------------------------------------------
    longest = defaultdict(int)
    counts = defaultdict(lambda: defaultdict(int))
    for i, ln, d in rs:
        day = t[i].strftime("%Y-%m-%d")
        longest[day] = max(longest[day], ln)
        for L in LEVELS:
            if ln >= L:
                counts[day][L] += 1
    print(f"\ndays containing at least one run of N in a row ({len(days)} days total)")
    hdr = f"{'N':<5}{'days with it':>14}{'share':>9}{'avg per day':>13}{'days without':>14}"
    print(hdr); print("-" * len(hdr))
    for L in LEVELS:
        have = sum(1 for d in days if longest.get(d, 0) >= L)
        tot = sum(counts[d][L] for d in days)
        print(f"{L:<5}{have:>14}{100*have/len(days):>8.1f}%{tot/len(days):>13.2f}{len(days)-have:>14}")

    print("\nthe longest runs of the year")
    hdr = f"{'start (ICT)':<19}{'len':>5}{'dir':>6}{'move %':>9}"
    print(hdr); print("-" * len(hdr))
    for i, ln, d in sorted(rs, key=lambda r: -r[1])[:20]:
        mv = (k[i + ln - 1][4] - k[i][1]) / k[i][1] * 100
        print(f"{t[i]:%Y-%m-%d %H:%M}{'':<3}{ln:>5}{'up' if d > 0 else 'down':>6}{mv:>9.2f}")

    misses = [d for d in days if longest.get(d, 0) < 6]
    print(f"\ndays with no run of 6+: {len(misses)}" + (f" -> {', '.join(misses)}" if misses else ""))
    for L in (8, 10):
        m = [d for d in days if longest.get(d, 0) < L]
        print(f"days with no run of {L}+: {len(m)}" + (f" -> {', '.join(m[:12])}{' ...' if len(m) > 12 else ''}" if m else ""))

    # --- 3. what a coin flip would give ---------------------------------
    up = sum(1 for x in k if x[4] > x[1]); dn = sum(1 for x in k if x[4] < x[1])
    p = up / (up + dn)
    n = len(k)
    print("\nexpected if candles were independent coin flips "
          f"(p_up = {p:.4f}, {n} candles):")
    hdr = f"{'N':<5}{'expected runs':>15}{'observed':>10}{'obs/exp':>9}"
    print(hdr); print("-" * len(hdr))
    for L in LEVELS:
        # maximal runs of length >= L: n * P(run starts here) * P(L-1 more same)
        exp = n * (p ** L * (1 - p) + (1 - p) ** L * p)
        obs = sum(1 for _, ln, _ in rs if ln >= L)
        print(f"{L:<5}{exp:>15.0f}{obs:>10}{obs/exp:>9.2f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz")
