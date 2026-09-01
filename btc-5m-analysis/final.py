#!/usr/bin/env python3
"""Head-to-head of the finalists on the same 300 days, same $50 first bet.

Each row is a complete plan: how much to deposit, what the first bet is, and what
happens on a loss. Ranked by what actually matters — return per unit of drawdown,
and whether the plan has a path to zero at all.
"""
import random
import statistics
import sys

import mycompound as M

B = M.B


def seqs_of(d, run, t, lo, hi, trigger=7):
    """Each sequence as its number of losses before the win."""
    out, i = [], lo
    while i < hi - 1:
        if run[i] != trigger or d[i] == 0:
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
        if won:
            out.append(losses)
        i = j + 1
    return out


def play(seqs, bank, frac, mult, cap=None):
    """cap = give up after this many losses and take the loss (None = never)."""
    peak, dd, maxbet = bank, 0.0, 0.0
    for losses in seqs:
        stake = bank * frac
        steps = losses if cap is None else min(losses, cap)
        for step in range(steps + 1):
            if stake > bank:
                return None, 1.0, maxbet          # ruin
            maxbet = max(maxbet, stake)
            if step == losses:
                bank += stake * B
            else:
                bank -= stake
                stake *= mult
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return bank, dd, maxbet


def mc(seqs, bank, frac, mult, cap=None, paths=4000, seed=23):
    rnd = random.Random(seed)
    fin, ruin = [], 0
    for _ in range(paths):
        s = [seqs[rnd.randrange(len(seqs))] for _ in range(len(seqs))]
        b, _, _ = play(s, bank, frac, mult, cap)
        if b is None:
            ruin += 1
            fin.append(0.0)
        else:
            fin.append(b)
    fin.sort()
    return statistics.median(fin), fin[int(0.05 * len(fin))], ruin / paths


def survives(frac, mult):
    """How many consecutive losses the bankroll can fund at this base fraction."""
    k, total = 0, 0.0
    while True:
        total += frac * mult ** k
        if total > 1.0:
            return k - 1
        k += 1
        if k > 40:
            return k


def main(path="candles330.json.gz", days=300, first=50.0):
    k_, t, d, run = M.prep(path)
    lo = max(0, len(k_) - days * 288)
    seqs = seqs_of(d, run, t, lo, len(k_))
    print(f"{days} days, {t[lo]:%Y-%m-%d} .. {t[-1]:%Y-%m-%d}, {len(seqs)} sequences, "
          f"deepest {max(seqs)} losses, first bet ${first:.0f}\n")
    plans = [
        ("flat 2.0%, no chase", 2500, 1.0, 0),
        ("flat 2.5%, no chase", 2000, 1.0, 0),
        ("flat 3.0%, no chase", 1667, 1.0, 0),
        ("flat 4.0%, no chase", 1250, 1.0, 0),
        ("chase at a constant stake", 2500, 1.0, None),
        ("x1.5, no cap", 5000, 1.5, None),
        ("x1.5, no cap", 5667, 1.5, None),
        ("x1.5, no cap", 8550, 1.5, None),
        ("x1.5, cap 4 steps", 2500, 1.5, 4),
        ("x1.5, cap 6 steps", 3000, 1.5, 6),
        ("x1.75, no cap", 12750, 1.75, None),
        ("x2.0, no cap", 25550, 2.0, None),
    ]
    hdr = (f"{'plan':<22}{'deposit':>9}{'1st bet':>9}{'final':>11}{'x':>7}"
           f"{'max bet':>10}{'max DD':>9}{'x/DD':>7}{'funds':>7}{'MC ruin':>9}")
    print(hdr); print("-" * len(hdr))
    rows = []
    for label, D, mult, cap in plans:
        frac = first / D
        b, dd, mb = play(seqs, D, frac, mult, cap)
        med, p5, ru = mc(seqs, D, frac, mult, cap)
        depth = "n/a" if mult == 1.0 else str(survives(frac, mult))
        if b is None:
            print(f"{label:<22}{D:>9,.0f}{first:>9,.0f}{'RUIN':>11}{'—':>7}"
                  f"{mb:>10,.0f}{'100%':>9}{'—':>7}{depth:>7}{100*ru:>8.1f}%")
            continue
        rows.append((b / D / dd if dd else 0, label, D, b, dd))
        print(f"{label:<22}{D:>9,.0f}{first:>9,.0f}{b:>11,.0f}{b/D:>7.2f}"
              f"{mb:>10,.0f}{100*dd:>8.1f}%{(b/D)/dd if dd else 0:>7.1f}"
              f"{depth:>7}{100*ru:>8.1f}%")
    print("\n'funds' = consecutive losses the plan can pay for before it cannot "
          "place the next bet\n'x/DD'  = multiple earned per unit of worst drawdown")
    print("Read x/DD carefully: a martingale shows almost no drawdown right up until "
          "it dies,\nso for those rows the honest risk number is 'funds' and "
          "'MC ruin', not the drawdown.")
    rows.sort(reverse=True)
    print(f"\nbest return per unit of drawdown: {rows[0][1]} on ${rows[0][2]:,.0f} "
          f"-> {rows[0][3]/rows[0][2]:.2f}x with a {100*rows[0][4]:.1f}% drawdown")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles330.json.gz")
