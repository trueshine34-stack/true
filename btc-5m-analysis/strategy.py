#!/usr/bin/env python3
"""Pick the sizing rule, not the progression: what the numbers actually support.

Three things get measured here that the earlier scripts did not separate:
  1. the edge of the FIRST bet of a sequence vs the chase bets behind it,
  2. flat-fraction / Kelly sizing against the martingale families, on one bankroll,
  3. the spread of outcomes, by bootstrapping the real trade sequence.
"""
import random
import statistics
import sys
from datetime import datetime
from math import erfc, sqrt

import nightruns
import search

BUY, SELL = 0.50, 0.99
B = SELL / BUY - 1              # net odds on a win: +0.98


def prep(path):
    k = search.load(path)
    t = [datetime.fromtimestamp(x[0] / 1000, nightruns.ICT) for x in k]
    d = [(x[4] > x[1]) - (x[4] < x[1]) for x in k]
    run = [0] * len(k)
    for i in range(len(k)):
        run[i] = run[i - 1] + 1 if i and d[i] and d[i] == d[i - 1] else (1 if d[i] else 0)
    return k, t, d, run


def fade_rate(d, run, t, lo, length, night=None):
    """Win rate of one fade bet placed when the run is exactly `length`."""
    w = l = 0
    for i in range(lo, len(d) - 1):
        if run[i] != length or d[i] == 0 or d[i + 1] == 0:
            continue
        if night is True and t[i].hour >= 8:
            continue
        if night is False and t[i].hour < 8:
            continue
        w += d[i + 1] == -d[i]
        l += d[i + 1] == d[i]
    n = w + l
    return n, (w / n if n else 0.0)


def kelly(p, b=B):
    return max(0.0, (p * b - (1 - p)) / b)


def ci(p, n, z=1.96):
    if not n:
        return 0.0, 0.0
    h = z * sqrt(p * (1 - p) / n)
    return max(0.0, p - h), min(1.0, p + h)


def trades(d, run, t, lo, trigger, night=None):
    """One bet per signal, no chasing: [True/False] in time order."""
    out = []
    for i in range(lo, len(d) - 1):
        if run[i] != trigger or d[i] == 0 or d[i + 1] == 0:
            continue
        if night is True and t[i].hour >= 8:
            continue
        if night is False and t[i].hour < 8:
            continue
        out.append(d[i + 1] == -d[i])
    return out


def equity(seq, frac, start=1000.0, compound=True):
    """Fixed-fraction staking. Returns final bankroll and worst drawdown."""
    bank = peak = start
    dd = 0.0
    for win in seq:
        stake = bank * frac if compound else start * frac
        if stake > bank:
            return 0.0, 1.0
        bank += stake * B if win else -stake
        if bank <= 0:
            return 0.0, 1.0
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return bank, dd


def bootstrap(seq, frac, paths=5000, start=1000.0, seed=7):
    rnd = random.Random(seed)
    finals, dds, ruins = [], [], 0
    for _ in range(paths):
        s = [seq[rnd.randrange(len(seq))] for _ in range(len(seq))]
        f, dd = equity(s, frac, start)
        finals.append(f); dds.append(dd)
        ruins += f < start * 0.5
    finals.sort()
    return {
        "median": statistics.median(finals),
        "p05": finals[int(0.05 * len(finals))],
        "p95": finals[int(0.95 * len(finals))],
        "worst": finals[0],
        "dd_median": statistics.median(dds),
        "dd_p95": sorted(dds)[int(0.95 * len(dds))],
        "p_halved": ruins / paths,
    }


def twostep(d, run, t, step, night=None):
    """Bet at run 6; if it loses, bet once more at run 7, then stop.

    Both bets carry a real edge, unlike a martingale that keeps going into run 8+.
    Returns per-sequence P&L in units of the first stake.
    """
    out, i = [], 0
    while i < len(d) - 2:
        if run[i] != 6 or d[i] == 0 or (night and t[i].hour >= 8):
            i += 1
            continue
        side, pnl = -d[i], 0.0
        if d[i + 1] == 0:
            i += 1
            continue
        if d[i + 1] == side:
            out.append(B)
            i += 2
            continue
        pnl -= 1.0
        j = i + 2
        if j < len(d) and d[j] != 0:
            pnl += B * step if d[j] == side else -step
        out.append(pnl)
        i = j + 1
    return out


def sim_rate(p, n, frac, paths=5000, start=1000.0, seed=3):
    """Same sizing, a hypothetical win rate — how fragile is the plan?"""
    rnd = random.Random(seed)
    fin = []
    for _ in range(paths):
        b = start
        for _ in range(n):
            b += b * frac * B if rnd.random() < p else -b * frac
        fin.append(b)
    fin.sort()
    return statistics.median(fin), fin[int(0.05 * len(fin))]


def main(path="candles240.json.gz"):
    k, t, d, run = prep(path)
    lo = 0
    print("edge by run length — the first bet of a sequence vs the chase bets\n")
    hdr = f"{'run length':<12}{'all hours n':>13}{'fade win%':>11}{'p':>8}   {'night n':>9}{'fade win%':>11}{'p':>8}"
    print(hdr); print("-" * len(hdr))
    for L in range(5, 12):
        n1, p1 = fade_rate(d, run, t, lo, L)
        n2, p2 = fade_rate(d, run, t, lo, L, night=True)
        z1 = ((p1 - .5) * sqrt(n1) * 2) if n1 else 0
        z2 = ((p2 - .5) * sqrt(n2) * 2) if n2 else 0
        print(f"{L:<12}{n1:>13}{100*p1:>10.2f}%{erfc(abs(z1)/sqrt(2)):>8.3f}   "
              f"{n2:>9}{100*p2:>10.2f}%{erfc(abs(z2)/sqrt(2)):>8.3f}")
    print("\nA martingale after a 7-run buys its next bets at run 8, 9, 10 — read the "
          "rows above:\nthe chase bets carry less edge than the trigger bet, at several "
          "times the size.")

    print("\n\nsizing for the trigger-7 signal (bankroll $1000, 8 months of trades)")
    for label, night in (("all hours", None), ("00:00-08:00 ICT", True)):
        seq = trades(d, run, t, lo, 7, night)
        n = len(seq); p = sum(seq) / n
        lo_ci, hi_ci = ci(p, n)
        print(f"\n{label}: {n} trades, win {100*p:.2f}% (95% CI {100*lo_ci:.1f}-{100*hi_ci:.1f}%)")
        print(f"  full Kelly on the point estimate : {100*kelly(p):.1f}% of bankroll")
        print(f"  full Kelly on the CI lower bound : {100*kelly(lo_ci):.1f}%")
        hdr = (f"  {'stake':<10}{'median $':>10}{'5th pct':>10}{'95th pct':>10}"
               f"{'median DD':>12}{'95th DD':>10}{'P(bank halved)':>16}")
        print(hdr); print("  " + "-" * (len(hdr) - 2))
        for frac in (0.01, 0.02, 0.03, 0.05, 0.10, kelly(p)):
            r = bootstrap(seq, frac)
            tag = f"{100*frac:.1f}%" + (" K" if abs(frac - kelly(p)) < 1e-9 else "")
            print(f"  {tag:<10}{r['median']:>10,.0f}{r['p05']:>10,.0f}{r['p95']:>10,.0f}"
                  f"{100*r['dd_median']:>11.1f}%{100*r['dd_p95']:>9.1f}%"
                  f"{100*r['p_halved']:>15.1f}%")


def extras(path="candles240.json.gz"):
    k, t, d, run = prep(path)
    print("\n\nEV of a single bet, in units of the stake")
    for L in (6, 7):
        for night in (None, True):
            n, p = fade_rate(d, run, t, 0, L, night)
            print(f"  run {L}, {'night   ' if night else 'all hours'}: n={n:4d} "
                  f"p={100*p:.2f}%  EV={p*B-(1-p):+.4f} per 1 risked")

    print("\ntwo-step scheme: bet at run 6, one more at run 7, then stop")
    hdr = f"{'2nd bet':>10}{'session':>9}{'seqs':>7}{'EV/seq':>10}{'max risk':>10}{'EV per risk':>13}"
    print(hdr); print("-" * len(hdr))
    for step in (1.0, 1.5, 2.0, 2.5):
        for night in (False, True):
            r = twostep(d, run, t, step, night)
            ev = sum(r) / len(r)
            print(f"{step:>9.1f}x{'night' if night else 'all':>9}{len(r):>7}"
                  f"{ev:>+10.4f}{1+step:>10.2f}{ev/(1+step):>13.4f}")
    print("  compare: one bet at run 7 returns +0.1368 per 1 risked (all hours), "
          "+0.2839 at night —\n  about twice the efficiency of any two-step version.")

    print("\nhow fragile is 2% flat staking? bankroll $1000, 418 trades")
    for p, lbl in ((0.574, "point estimate 57.4%"), (0.527, "CI lower bound 52.7%"),
                   (0.505, "break-even 50.5%")):
        m, q = sim_rate(p, 418, 0.02)
        print(f"  {lbl:<24} median ${m:,.0f}   5th pct ${q:,.0f}")


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz"
    main(p)
    extras(p)
