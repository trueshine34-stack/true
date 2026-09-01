#!/usr/bin/env python3
"""The user's plan: fade a 7-run, double on a loss, and let the base stake grow.

Base stake starts at $50 and stays a constant fraction of the bankroll, so profits
raise the size of every future sequence. Inside a sequence the stake doubles after
each loss, on the same side, until the sequence wins.

A sequence that needs more than the bankroll holds is a bust: the account cannot
place the next double, so the loss stands and the run is recorded as ruin.
"""
import random
import statistics
import sys
from datetime import datetime

import nightruns
import search

BUY, SELL = 0.50, 0.99
B = SELL / BUY - 1          # +0.98 on a win
FIRST = 50.0                # the first entry


def prep(path):
    k = search.load(path)
    t = [datetime.fromtimestamp(x[0] / 1000, nightruns.ICT) for x in k]
    d = [(x[4] > x[1]) - (x[4] < x[1]) for x in k]
    run = [0] * len(k)
    for i in range(len(k)):
        run[i] = run[i - 1] + 1 if i and d[i] and d[i] == d[i - 1] else (1 if d[i] else 0)
    return k, t, d, run


def sequences(d, run, t, lo, hi, trigger=7, night=None):
    """[(month, losses_before_win, resolved)] — the shape of each real sequence."""
    out, i = [], lo
    while i < hi - 1:
        if run[i] != trigger or d[i] == 0 or (night and t[i].hour >= 8):
            i += 1
            continue
        side, losses, j = -d[i], 0, i + 1
        won = False
        while j < hi:
            if d[j] == 0:
                j += 1
                continue
            if d[j] == side:
                won = True
                break
            losses += 1
            j += 1
        out.append((t[i].strftime("%Y-%m-%d"), losses, won))
        i = j + 1
    return out


def play(seqs, bank, frac):
    """frac = base stake as a share of the bankroll. Returns the whole picture."""
    peak, dd, maxbet, worst, busted = bank, 0.0, 0.0, 0, None
    for n, (day, losses, won) in enumerate(seqs):
        stake = bank * frac
        for step in range(losses + 1):
            if stake > bank:                     # cannot fund the next double
                return {"bank": bank, "dd": 1.0, "maxbet": maxbet, "worst": worst,
                        "busted": day, "seq": n, "ruin": True}
            maxbet = max(maxbet, stake)
            if step == losses and won:
                bank += stake * B
            else:
                bank -= stake
                stake *= 2
        worst = max(worst, losses)
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return {"bank": bank, "dd": dd, "maxbet": maxbet, "worst": worst,
            "busted": None, "seq": len(seqs), "ruin": False}


def mc(seqs, bank, frac, paths=4000, seed=5):
    rnd = random.Random(seed)
    finals, ruins, dds = [], 0, []
    for _ in range(paths):
        s = [seqs[rnd.randrange(len(seqs))] for _ in range(len(seqs))]
        r = play(s, bank, frac)
        finals.append(r["bank"]); ruins += r["ruin"]; dds.append(r["dd"])
    finals.sort()
    return {"median": statistics.median(finals),
            "p05": finals[int(0.05 * len(finals))],
            "p25": finals[int(0.25 * len(finals))],
            "p95": finals[int(0.95 * len(finals))],
            "ruin": ruins / paths,
            "dd_median": statistics.median(dds)}


def main(path, days_list=(100, 300), trigger=7, night=None):
    k, t, d, run = prep(path)
    for days in days_list:
        lo = max(0, len(k) - days * 288)
        if lo == 0 and days * 288 > len(k):
            print(f"\n!! only {len(k)//288} days of data available, "
                  f"{days} requested — using what there is")
        seqs = sequences(d, run, t, lo, len(k), trigger, night)
        wins = sum(1 for _, _, w in seqs if w)
        deepest = max(l for _, l, _ in seqs)
        print(f"\n{'='*78}\n{days} days: {t[lo]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d}, "
              f"{len(seqs)} sequences ({len(seqs)/days*30:.0f} per month), "
              f"deepest run of losses {deepest}")
        print(f"first-bet hit rate {100*sum(1 for _, l, _ in seqs if l == 0)/len(seqs):.2f}%, "
              f"sequences that eventually won {wins}/{len(seqs)}")
        need = FIRST * (2 ** (deepest + 1) - 1)
        print(f"cash needed to fund the deepest sequence at a $50 base: ${need:,.0f}")

        hdr = (f"{'deposit':>9}{'base bet':>10}{'final $':>12}{'x':>7}{'max bet':>10}"
               f"{'max DD':>9}{'ruin?':>7}   {'MC median':>11}{'MC 5th':>10}{'MC ruin':>9}")
        print(hdr); print("-" * len(hdr))
        for D in (500, 1000, 2500, 5000, 12750, 25550):
            frac = FIRST / D
            r = play(seqs, D, frac)
            m = mc(seqs, D, frac)
            print(f"{D:>9,.0f}{FIRST:>10,.0f}{r['bank']:>12,.0f}{r['bank']/D:>7.2f}"
                  f"{r['maxbet']:>10,.0f}{100*r['dd']:>8.1f}%"
                  f"{('RUIN' if r['ruin'] else 'no'):>7}   "
                  f"{m['median']:>11,.0f}{m['p05']:>10,.0f}{100*m['ruin']:>8.1f}%")


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz"
    main(p)
