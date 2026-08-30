#!/usr/bin/env python3
"""After a run of N same-direction candles and one reversal candle, how often
does the candle after the reversal repeat the reversal's direction?"""
import sys
from collections import defaultdict
import analyze


def sign(x):
    return (x > 0) - (x < 0)


def main(path="candles.json", min_run=4):
    c = analyze.load(path)
    d = [sign(x[4] - x[1]) for x in c]

    # run length ending at i (doji breaks any run and starts nothing)
    runs = [0] * len(d)
    for i, s in enumerate(d):
        if s == 0:
            runs[i] = 0
        elif i and d[i - 1] == s:
            runs[i] = runs[i - 1] + 1
        else:
            runs[i] = 1

    by_len = defaultdict(lambda: [0, 0, 0, 0.0])  # n, same, opposite, sum of ret
    tot = [0, 0, 0, 0.0]
    for i in range(len(d) - 2):
        n = runs[i]
        if n < min_run or d[i] == 0:
            continue
        if runs[i + 1] != 1 or d[i + 1] != -d[i]:   # need exactly one reversal candle
            continue
        rev = d[i + 1]
        nxt = d[i + 2]
        o, cl = c[i + 2][1], c[i + 2][4]
        ret = rev * (cl - o) / o * 100
        key = min(n, 7)
        for bucket in (by_len[key], tot):
            bucket[0] += 1
            bucket[1] += nxt == rev
            bucket[2] += nxt == -rev
            bucket[3] += ret

    print(f"BTC-USDT 5m, {len(c)} candles. Run of >={min_run} same-direction candles, "
          f"then exactly one reversal candle; direction of the candle after it:\n")
    hdr = f"{'run length':<14}{'cases':>7}{'continues rev':>15}{'back to run':>13}{'doji':>6}{'P(same as rev)':>17}{'gross %':>10}"
    print(hdr); print("-" * len(hdr))
    for k in sorted(by_len):
        n, s, o_, r = by_len[k]
        label = f"{k}" if k < 7 else "7+"
        print(f"{label:<14}{n:>7}{s:>15}{o_:>13}{n-s-o_:>6}{100*s/n:>16.2f}%{r:>10.2f}")
    n, s, o_, r = tot
    print("-" * len(hdr))
    print(f"{'ALL >=%d' % min_run:<14}{n:>7}{s:>15}{o_:>13}{n-s-o_:>6}{100*s/n:>16.2f}%{r:>10.2f}")
    print(f"\nexcluding doji outcomes: {100*s/(s+o_):.2f}%   avg per trade {r/n:+.4f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles.json",
         int(sys.argv[2]) if len(sys.argv) > 2 else 4)
