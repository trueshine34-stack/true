#!/usr/bin/env python3
"""The same fade strategy on 15m candles, where its best trigger turns out to be 4.

15m candles are built exactly from the 5m file (make15m.py). On this timeframe the
mean-reversion shows up after four candles rather than seven, and there are ten
times as many signals, which is what changes the arithmetic.
"""
import sys
from math import erfc, sqrt

import final as F
import mycompound as M
import strategy as S

BUY, SELL = 0.50, 0.99
B = M.B


def rates(d, run, t, lo=0):
    print(f"{'run':<6}{'all n':>8}{'fade %':>9}{'p':>8}   {'night n':>9}{'fade %':>9}{'p':>8}")
    print("-" * 58)
    for L in range(3, 11):
        n1, p1 = S.fade_rate(d, run, t, lo, L)
        n2, p2 = S.fade_rate(d, run, t, lo, L, night=True)
        z1 = (p1 - .5) * 2 * sqrt(n1) if n1 else 0
        z2 = (p2 - .5) * 2 * sqrt(n2) if n2 else 0
        print(f"{L:<6}{n1:>8}{100*p1:>8.2f}%{erfc(abs(z1)/sqrt(2)):>8.3f}   "
              f"{n2:>9}{100*p2:>8.2f}%{erfc(abs(z2)/sqrt(2)):>8.3f}")


def flat(d, run, t, lo, hi, trigger, frac, bank, night=None):
    peak, dd, n, w = bank, 0.0, 0, 0
    for i in range(lo, hi - 1):
        if run[i] != trigger or d[i] == 0 or d[i + 1] == 0:
            continue
        if night and t[i].hour >= 8:
            continue
        s = bank * frac
        n += 1
        if d[i + 1] == -d[i]:
            bank += s * B
            w += 1
        else:
            bank -= s
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return bank, dd, n, (w / n if n else 0)


def main(path="candles330_15m.json.gz", days=300, bars=96):
    k, t, d, run = M.prep(path)
    lo = max(0, len(k) - days * bars)
    print(f"15m candles: {len(k)} bars, {t[0]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d} ICT\n")
    print("fade win rate by run length, whole sample")
    rates(d, run, t)

    print(f"\n\nout-of-sample check on the best trigger (4), 70/30 split")
    split = int(len(k) * 0.7)
    for lbl, a, b in (("train", 0, split), ("test ", split, len(k))):
        _, _, n, p = flat(d, run, t, a, b, 4, 0.0, 1.0)
        z = (p - .5) * 2 * sqrt(n)
        print(f"  {lbl}: n={n:5d}  fade {100*p:.2f}%  p={erfc(abs(z)/sqrt(2)):.4f}")

    print(f"\n\nflat staking, last {days} days, $2000 bankroll, first bet $50 (2.5%)")
    hdr = f"{'trigger':<9}{'signals':>9}{'per month':>11}{'win %':>8}{'final $':>12}{'x':>8}{'max DD':>9}"
    print(hdr); print("-" * len(hdr))
    for trig in (4, 5, 6, 7):
        for night in (False, True):
            bank, dd, n, p = flat(d, run, t, lo, len(k), trig, 0.025, 2000.0, night)
            if n < 20:
                continue
            tag = f"{trig}{' night' if night else ''}"
            print(f"{tag:<9}{n:>9}{n/(days/30):>11.0f}{100*p:>7.2f}%{bank:>12,.0f}"
                  f"{bank/2000:>8.2f}{100*dd:>8.1f}%")

    print(f"\n\nstake size on the trigger-4 signal, {days} days, $2000 bankroll")
    hdr = f"{'stake':<8}{'first bet':>11}{'final $':>12}{'x':>8}{'per month':>12}{'max DD':>9}"
    print(hdr); print("-" * len(hdr))
    for frac in (0.01, 0.015, 0.02, 0.025, 0.03, 0.04):
        bank, dd, n, p = flat(d, run, t, lo, len(k), 4, frac, 2000.0)
        print(f"{100*frac:<7.1f}%{2000*frac:>11,.0f}{bank:>12,.0f}{bank/2000:>8.2f}"
              f"{(bank/2000)**(30/days):>11.2f}x{100*dd:>8.1f}%")

    print(f"\n\nprogressions on the trigger-4 signal, {days} days")
    seqs = F.seqs_of(d, run, t, lo, len(k), trigger=4)
    print(f"  {len(seqs)} sequences, deepest {max(seqs)} losses in a row")
    hdr = f"  {'plan':<20}{'deposit':>9}{'final':>11}{'x':>8}{'max bet':>11}{'max DD':>9}{'MC ruin':>9}"
    print(hdr); print("  " + "-" * (len(hdr) - 2))
    for lbl, D, mult, cap in (("flat 2.5%", 2000, 1.0, 0), ("flat 3%", 1667, 1.0, 0),
                              ("x1.5 base 1%", 5000, 1.5, None),
                              ("x2 base 0.5%", 10000, 2.0, None)):
        frac = 50.0 / D
        b, dd, mb = F.play(seqs, D, frac, mult, cap)
        _, _, ru = F.mc(seqs, D, frac, mult, cap)
        val = "RUIN" if b is None else f"{b:,.0f}"
        x = "—" if b is None else f"{b/D:.2f}"
        print(f"  {lbl:<20}{D:>9,.0f}{val:>11}{x:>8}{mb:>11,.0f}"
              f"{(100*dd):>8.1f}%{100*ru:>8.1f}%")

    print("\n\nprice sensitivity of the trigger-4 signal (flat $100 a bet)")
    hdr = f"{'buy':>6}{'sell':>7}{'break-even':>12}{'EV @ 55.9%':>13}{'EV @ 53.5%':>13}"
    print(hdr); print("-" * len(hdr))
    for buy in (0.50, 0.51):
        for sell in (0.99, 0.95, 0.90):
            m = sell / buy - 1
            row = f"{buy*100:>5.0f}c{sell*100:>6.0f}c{100*buy/sell:>11.1f}%"
            for r in (0.559, 0.535):
                row += f"{r*100*m - (1-r)*100:>+12.2f}$"
            print(row)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles330_15m.json.gz",
         int(sys.argv[2]) if len(sys.argv) > 2 else 300)
