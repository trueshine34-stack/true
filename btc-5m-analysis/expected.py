#!/usr/bin/env python3
"""What the recommended plan actually pays, month by month, and what it needs.

Plan: fade a run of exactly 7, one bet per signal, no chasing, stake = a fixed
percentage of the current bankroll, night signals weighted double.
"""
import random
import statistics
import sys
from datetime import datetime

import nightruns
import strategy as S

FEE_FREE = True


def signal_list(d, run, t, trigger=7):
    """[(month, is_night, won)] in time order."""
    out = []
    for i in range(len(d) - 1):
        if run[i] != trigger or d[i] == 0 or d[i + 1] == 0:
            continue
        out.append((t[i].strftime("%Y-%m"), t[i].hour < 8, d[i + 1] == -d[i]))
    return out


def run_plan(sigs, bank, day_frac, night_frac):
    """Walk the real sequence once; returns bankroll history and monthly P&L."""
    月 = {}
    peak, dd = bank, 0.0
    for month, night, won in sigs:
        stake = bank * (night_frac if night else day_frac)
        before = bank
        bank += stake * S.B if won else -stake
        月[month] = 月.get(month, 0.0) + (bank - before)
        peak = max(peak, bank)
        dd = max(dd, 1 - bank / peak)
    return bank, 月, dd


def mc(sigs, bank, day_frac, night_frac, paths=5000, seed=11):
    """Same plan on resampled orderings, to get the spread rather than one path."""
    rnd = random.Random(seed)
    finals, dds, worst_months = [], [], []
    for _ in range(paths):
        s = [sigs[rnd.randrange(len(sigs))] for _ in range(len(sigs))]
        # relabel months evenly so每 path has the same 8 buckets
        per = len(s) // 8
        s = [(f"m{min(i // per, 7)}", a, b) for i, (_, a, b) in enumerate(s)]
        f, months, dd = run_plan(s, bank, day_frac, night_frac)
        finals.append(f); dds.append(dd)
        worst_months.append(min(months.values()))
    finals.sort(); dds.sort(); worst_months.sort()
    return {
        "median": statistics.median(finals),
        "p05": finals[int(0.05 * len(finals))],
        "p25": finals[int(0.25 * len(finals))],
        "p75": finals[int(0.75 * len(finals))],
        "dd_median": statistics.median(dds),
        "dd_p95": dds[int(0.95 * len(dds))],
        "worst_month_median": statistics.median(worst_months),
        "worst_month_p05": worst_months[int(0.05 * len(worst_months))],
        "p_loss": sum(1 for f in finals if f < bank) / len(finals),
    }


def main(path="candles240.json.gz", bank=1000.0):
    k, t, d, run = S.prep(path)
    sigs = signal_list(d, run, t, 7)
    months = sorted({m for m, _, _ in sigs})
    n_night = sum(1 for _, ng, _ in sigs if ng)
    print(f"trigger 7, {len(sigs)} signals over {len(months)} months "
          f"({len(sigs)/len(months):.0f} per month, of which "
          f"{n_night/len(months):.0f} at night)")
    print(f"win rate: all {100*sum(w for _,_,w in sigs)/len(sigs):.2f}%, "
          f"night {100*sum(w for _,ng,w in sigs if ng)/n_night:.2f}%\n")

    for label, day_f, night_f in (("2% flat, all signals", 0.02, 0.02),
                                  ("2% day / 4% night", 0.02, 0.04),
                                  ("night only, 3%", 0.0, 0.03),
                                  ("1% flat (cautious)", 0.01, 0.01)):
        final, per_month, dd = run_plan(sigs, bank, day_f, night_f)
        r = mc(sigs, bank, day_f, night_f)
        print(f"{label}   — bankroll ${bank:,.0f}")
        print(f"  actual 8-month path : ${final:,.0f} ({final/bank:.2f}x), "
              f"worst drawdown {100*dd:.1f}%")
        print(f"  month by month      : " +
              " ".join(f"{m[-2:]}:{v:+,.0f}" for m, v in sorted(per_month.items())))
        print(f"  resampled median    : ${r['median']:,.0f}  "
              f"(25-75%: ${r['p25']:,.0f}-${r['p75']:,.0f}, 5th pct ${r['p05']:,.0f})")
        print(f"  drawdown            : median {100*r['dd_median']:.1f}%, "
              f"95th pct {100*r['dd_p95']:.1f}%")
        print(f"  worst month         : median ${r['worst_month_median']:,.0f}, "
              f"5th pct ${r['worst_month_p05']:,.0f}")
        print(f"  chance of ending below the start: {100*r['p_loss']:.1f}%\n")

    print("same plan (2% flat) scaled to different starting bankrolls")
    hdr = (f"{'bankroll':>10}{'bet size':>10}{'median after 8 mo':>20}"
           f"{'median $/month':>17}{'worst month (5%)':>19}{'min bet needed':>16}")
    print(hdr); print("-" * len(hdr))
    for b in (100, 250, 500, 1000, 2500, 5000):
        r = mc(sigs, b, 0.02, 0.02, paths=2000)
        gain = r["median"] - b
        print(f"{b:>10,.0f}{0.02*b:>10,.2f}{r['median']:>20,.0f}"
              f"{gain/8:>17,.0f}{r['worst_month_p05']:>19,.0f}"
              f"{0.02*b*(1-0.35):>16,.2f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz",
         float(sys.argv[2]) if len(sys.argv) > 2 else 1000.0)
