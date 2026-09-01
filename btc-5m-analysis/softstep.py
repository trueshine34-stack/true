#!/usr/bin/env python3
"""The same 7-run fade, but each losing step raises the stake by 50% or 75%.

$50 on red after seven greens; if the eighth closes green, $75 (x1.5) or $87.50
(x1.75) on red for the ninth, and so on. The base stake stays a constant share of
the bankroll, so it grows as profit accumulates.

A gentler step needs far less capital than doubling, but it stops recovering the
earlier losses after a couple of steps — the two effects are what this weighs.
"""
import random
import statistics
import sys

import mycompound as M

B = M.B
FIRST = M.FIRST


def sequences(d, run, t, lo, hi, trigger=7, night=None):
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
        out.append(losses if won else None)
        i = j + 1
    return [s for s in out if s is not None]


def play(seqs, bank, frac, mult):
    peak, dd, maxbet, worst = bank, 0.0, 0.0, 0
    for n, losses in enumerate(seqs):
        stake = bank * frac
        for step in range(losses + 1):
            if stake > bank:
                return {"bank": bank, "dd": 1.0, "maxbet": maxbet, "ruin": True, "seq": n}
            maxbet = max(maxbet, stake)
            if step == losses:
                bank += stake * B
            else:
                bank -= stake
                stake *= mult
        worst = max(worst, losses)
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return {"bank": bank, "dd": dd, "maxbet": maxbet, "ruin": False, "worst": worst}


def mc(seqs, bank, frac, mult, paths=4000, seed=17):
    rnd = random.Random(seed)
    fin, ruin = [], 0
    for _ in range(paths):
        s = [seqs[rnd.randrange(len(seqs))] for _ in range(len(seqs))]
        r = play(s, bank, frac, mult)
        fin.append(r["bank"]); ruin += r["ruin"]
    fin.sort()
    return statistics.median(fin), fin[int(0.05 * len(fin))], ruin / paths


def recovery(mults=(1.5, 1.75, 2.0), base=FIRST, kmax=8):
    print(f"net result of a sequence that wins after k losses (base ${base:.0f})")
    hdr = f"{'k losses':<10}" + "".join(f"{'x'+format(m,'.2f'):>12}" for m in mults)
    print(hdr); print("-" * len(hdr))
    for k in range(kmax + 1):
        row = f"{k:<10}"
        for m in mults:
            row += f"{B*base*m**k - base*(m**k-1)/(m-1):>+12,.0f}"
        print(row)
    print(f"\ncapital to fund k losses, and the largest single bet")
    hdr = f"{'k':<4}" + "".join(f"{'x'+format(m,'.2f')+' need/max':>22}" for m in mults)
    print(hdr); print("-" * len(hdr))
    for k in range(4, kmax + 1):
        row = f"{k:<4}"
        for m in mults:
            need = base * (m ** (k + 1) - 1) / (m - 1)
            row += f"{'$'+format(need,',.0f')+' / $'+format(base*m**k,',.0f'):>22}"
        print(row)


def main(path="candles330.json.gz", days_list=(100, 300)):
    k_, t, d, run = M.prep(path)
    recovery()
    for days in days_list:
        lo = max(0, len(k_) - days * 288)
        seqs = sequences(d, run, t, lo, len(k_))
        print(f"\n{'='*86}\n{days} days: {t[lo]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d}, "
              f"{len(seqs)} sequences, deepest {max(seqs)} losses")
        for mult in (1.5, 1.75, 2.0):
            print(f"\n  step x{mult}")
            hdr = (f"    {'deposit':>9}{'final $':>12}{'x':>7}{'max bet':>10}{'max DD':>9}"
                   f"{'ruin?':>7}   {'MC median':>11}{'MC 5th':>10}{'MC ruin':>9}")
            print(hdr); print("    " + "-" * (len(hdr) - 4))
            for D in (1000, 2500, 5000, 12750, 25550):
                frac = FIRST / D
                r = play(seqs, D, frac, mult)
                m5, p5, ru = mc(seqs, D, frac, mult)
                print(f"    {D:>9,.0f}{r['bank']:>12,.0f}{r['bank']/D:>7.2f}"
                      f"{r['maxbet']:>10,.0f}{100*r['dd']:>8.1f}%"
                      f"{('RUIN' if r['ruin'] else 'no'):>7}   "
                      f"{m5:>11,.0f}{p5:>10,.0f}{100*ru:>8.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles330.json.gz")
