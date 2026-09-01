#!/usr/bin/env python3
"""Adds the 'ride the winner' rule to the user's plan.

Sequence: fade a 7-run; double on every loss until it wins; then place ONE more
bet in the direction that just won, at double the winning stake. The sequence
ends there whatever happens (variant "one"), or the martingale continues behind
that bet too (variant "chase").

The ride bet is priced off the stake that just won, so a deep martingale hands it
an enormous stake — that is what moves the deposit requirement.
"""
import random
import statistics
import sys

import mycompound as M

B = M.B
FIRST = M.FIRST


def walk(k, t, d, run, lo, hi, trigger=7, night=None, ride=True, chase_ride=False):
    """The exact bet-by-bet script of one pass, as (stake_multiple, won) steps."""
    seqs, i = [], lo
    while i < hi - 1:
        if run[i] != trigger or d[i] == 0 or (night and t[i].hour >= 8):
            i += 1
            continue
        side, steps, mult, j = -d[i], [], 1.0, i + 1
        # martingale on the fade
        while j < hi:
            if d[j] == 0:
                j += 1
                continue
            if d[j] == side:
                steps.append((mult, True))
                break
            steps.append((mult, False))
            mult *= 2
            j += 1
        if j >= hi:
            break
        won_mult = mult
        j += 1
        # ride the winner, same side, double the winning stake
        if ride:
            rm = won_mult * 2
            while j < hi:
                if d[j] == 0:
                    j += 1
                    continue
                if d[j] == side:
                    steps.append((rm, True))
                    break
                steps.append((rm, False))
                if not chase_ride:
                    break
                rm *= 2
                j += 1
            j += 1
        seqs.append(steps)
        i = j
    return seqs


def play(seqs, bank, frac):
    peak, dd, maxbet = bank, 0.0, 0.0
    for n, steps in enumerate(seqs):
        base = bank * frac
        for mult, won in steps:
            stake = base * mult
            if stake > bank:
                return {"bank": bank, "dd": 1.0, "maxbet": maxbet, "ruin": True, "seq": n}
            maxbet = max(maxbet, stake)
            bank += stake * B if won else -stake
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return {"bank": bank, "dd": dd, "maxbet": maxbet, "ruin": False, "seq": len(seqs)}


def mc(seqs, bank, frac, paths=3000, seed=9):
    rnd = random.Random(seed)
    fin, ruin = [], 0
    for _ in range(paths):
        s = [seqs[rnd.randrange(len(seqs))] for _ in range(len(seqs))]
        r = play(s, bank, frac)
        fin.append(r["bank"]); ruin += r["ruin"]
    fin.sort()
    return statistics.median(fin), fin[int(0.05 * len(fin))], ruin / paths


def main(path="candles330.json.gz", days_list=(100, 300)):
    k, t, d, run = M.prep(path)
    for days in days_list:
        lo = max(0, len(k) - days * 288)
        print(f"\n{'='*92}\n{days} days: {t[lo]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d}")
        for label, ride, chase in (("no ride (baseline)", False, False),
                                   ("+ ride the winner, one bet", True, False),
                                   ("+ ride, and chase it too", True, True)):
            seqs = walk(k, t, d, run, lo, len(k), 7, None, ride, chase)
            bets = sum(len(s) for s in seqs)
            wins = sum(1 for s in seqs for _, w in s if w)
            worst = max(max(m for m, _ in s) for s in seqs)
            print(f"\n  {label}: {len(seqs)} sequences, {bets} bets, "
                  f"hit rate {100*wins/bets:.2f}%, largest stake {worst:.0f}x the base "
                  f"(= ${FIRST*worst:,.0f} at a $50 base)")
            hdr = (f"    {'deposit':>9}{'final $':>12}{'x':>7}{'max bet':>11}"
                   f"{'max DD':>9}{'ruin?':>7}   {'MC median':>11}{'MC 5th':>10}{'MC ruin':>9}")
            print(hdr); print("    " + "-" * (len(hdr) - 4))
            for D in (2500, 5000, 12750, 25550, 51150):
                frac = FIRST / D
                r = play(seqs, D, frac)
                m5, p5, ru = mc(seqs, D, frac)
                print(f"    {D:>9,.0f}{r['bank']:>12,.0f}{r['bank']/D:>7.2f}"
                      f"{r['maxbet']:>11,.0f}{100*r['dd']:>8.1f}%"
                      f"{('RUIN' if r['ruin'] else 'no'):>7}   "
                      f"{m5:>11,.0f}{p5:>10,.0f}{100*ru:>8.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles330.json.gz")
