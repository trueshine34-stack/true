#!/usr/bin/env python3
"""Adds the 'buy more at 33c inside the candle' rule to the 15m trigger-4 plan.

The 15m entry candle is three 5m bars, so the 5m file shows what happened inside
it. If the price is still moving against us partway through, the contract is
cheap; the rule buys a second lot for the same dollar amount at 33c and both lots
settle at 99c when the candle closes our way.

    base lot : $S at 50c -> +0.98S on a win, -S on a loss
    add-on   : $S at 33c -> +2.00S on a win, -S on a loss
    both     : +2.98S on 2S risked, or -2S

so the pair needs 2 / 4.98 = 40.2% to break even.
"""
import sys
from math import erfc, sqrt

import mycompound as M
import search

BUY, ADD, SELL = 0.50, 0.33, 0.99


def prep15_with_5m(f15="candles330_15m.json.gz", f5="candles330.json.gz"):
    k15, t15, d15, run15 = M.prep(f15)
    k5 = search.load(f5)
    by_ts = {}
    for row in k5:
        by_ts.setdefault(row[0] // 900_000 * 900_000, []).append(row)
    subs = [sorted(by_ts.get(x[0], []))[:3] for x in k15]
    return k15, t15, d15, run15, subs


def analyse(k15, t15, d15, run15, subs, trigger=4, after=1):
    """after = how many 5m bars into the candle the add-on decision is made."""
    tot = adverse = win_adv = win_ok = 0
    for i in range(len(k15) - 1):
        if run15[i] != trigger or d15[i] == 0:
            continue
        j = i + 1
        if len(subs[j]) < 3 or d15[j] == 0:
            continue
        side = -d15[i]
        o = k15[j][1]
        mid = subs[j][after - 1][4]           # close of the 1st or 2nd 5m bar
        went_against = side * (mid - o) < 0
        won = d15[j] == side
        tot += 1
        if went_against:
            adverse += 1
            win_adv += won
        else:
            win_ok += won
    return {"n": tot, "adverse": adverse,
            "p_adverse": adverse / tot,
            "p_win_given_adverse": win_adv / adverse if adverse else 0,
            "p_win_given_ok": win_ok / (tot - adverse) if tot > adverse else 0,
            "p_win": (win_adv + win_ok) / tot}


def ev(r):
    """Expected profit per $1 of the base lot, with and without the add-on."""
    base = r["p_win"] * (SELL / BUY - 1) - (1 - r["p_win"])
    pa, pw = r["p_adverse"], r["p_win_given_adverse"]
    add_leg = pw * (SELL / ADD - 1) - (1 - pw)      # per $1 of the add-on lot
    total = base + pa * add_leg
    risked = 1 + pa                                   # average dollars at risk
    return base, add_leg, total, total / risked


def main(f15="candles330_15m.json.gz", f5="candles330.json.gz", trigger=4):
    k15, t15, d15, run15, subs = prep15_with_5m(f15, f5)
    print(f"15m trigger {trigger}; the add-on is priced at {ADD*100:.0f}c and sells "
          f"at {SELL*100:.0f}c (+{100*(SELL/ADD-1):.0f}%)")
    print(f"break-even for the pair: {100*2/(SELL/BUY-1 + SELL/ADD-1 + 2):.1f}%\n")
    hdr = (f"{'decide after':<14}{'signals':>9}{'add-on fires':>14}{'P(win|fired)':>14}"
           f"{'P(win|not)':>12}{'overall':>10}")
    print(hdr); print("-" * len(hdr))
    for after in (1, 2):
        r = analyse(k15, t15, d15, run15, subs, trigger, after)
        z = (r["p_win_given_adverse"] - 0.402) * 2 * sqrt(r["adverse"])
        print(f"{after*5:>3} minutes  {'':<2}{r['n']:>9}{r['adverse']:>9} "
              f"({100*r['p_adverse']:.0f}%){100*r['p_win_given_adverse']:>13.2f}%"
              f"{100*r['p_win_given_ok']:>11.2f}%{100*r['p_win']:>9.2f}%")
    print()
    for after in (1, 2):
        r = analyse(k15, t15, d15, run15, subs, trigger, after)
        b, a, tot, per_risk = ev(r)
        print(f"decide after {after*5} minutes:")
        print(f"  base lot alone            : {b:+.4f} per $1")
        print(f"  add-on lot alone          : {a:+.4f} per $1 (needs 33.3%, has "
              f"{100*r['p_win_given_adverse']:.1f}%)")
        print(f"  base + add-on, per signal : {tot:+.4f} per $1 of base stake")
        print(f"  ... per $1 actually risked: {per_risk:+.4f}  "
              f"(avg ${1+r['p_adverse']:.2f} at risk per signal)")
    print("\nWhat the add-on price would have to be to be fair:")
    for after in (1, 2):
        r = analyse(k15, t15, d15, run15, subs, trigger, after)
        p = r["p_win_given_adverse"]
        print(f"  after {after*5} min: P(win) = {100*p:.1f}%  ->  fair price "
              f"{100*p*SELL:.0f}c against the {ADD*100:.0f}c assumed")


def compound(f15="candles330_15m.json.gz", f5="candles330.json.gz", trigger=4,
             days=300, bank0=2000.0, add_price=ADD):
    """Run the bankroll through the real sequence, with and without the add-on."""
    k15, t15, d15, run15, subs = prep15_with_5m(f15, f5)
    lo = max(0, len(k15) - days * 96)
    out = {}
    for label, use_add, frac in (("flat 2.5%, no add-on", False, 0.025),
                                 ("flat 2.5% + add-on at 5 min", True, 0.025),
                                 ("flat 3.6% (same money at risk)", False, 0.036),
                                 ("flat 1.7% + add-on (same risk)", True, 0.017)):
        bank, peak, dd, n, w = bank0, bank0, 0.0, 0, 0
        for i in range(lo, len(k15) - 1):
            if run15[i] != trigger or d15[i] == 0:
                continue
            j = i + 1
            if len(subs[j]) < 3 or d15[j] == 0:
                continue
            side, o = -d15[i], k15[j][1]
            stake = bank * frac
            won = d15[j] == side
            bank += stake * (SELL / BUY - 1) if won else -stake
            if use_add and side * (subs[j][0][4] - o) < 0:
                bank += stake * (SELL / add_price - 1) if won else -stake
            n += 1
            w += won
            peak = max(peak, bank)
            dd = max(dd, 1 - bank / peak)
        out[label] = (bank, dd, n, w / n if n else 0)
    print(f"\n\n{days} days on a ${bank0:,.0f} bankroll, 15m trigger {trigger}, "
          f"add-on bought at {add_price*100:.0f}c")
    hdr = f"{'plan':<34}{'bets':>7}{'win %':>8}{'final $':>12}{'x':>9}{'max DD':>9}"
    print(hdr); print("-" * len(hdr))
    for label, (bank, dd, n, p) in out.items():
        print(f"{label:<34}{n:>7}{100*p:>7.2f}%{bank:>12,.0f}{bank/bank0:>9.2f}"
              f"{100*dd:>8.1f}%")

    print("\nadd-on price sensitivity (per $1 of the add-on lot, P(win)=35.3%)")
    hdr = f"{'price':>8}{'payout':>10}{'break-even':>12}{'EV':>10}"
    print(hdr); print("-" * len(hdr))
    for pr in (0.25, 0.30, 0.33, 0.35, 0.38, 0.40, 0.45):
        m = SELL / pr - 1
        print(f"{pr*100:>7.0f}c{100*m:>9.0f}%{100*pr/SELL:>11.1f}%"
              f"{0.3526*m - 0.6474:>+10.4f}")


if __name__ == "__main__":
    main(*(sys.argv[1:3] or ["candles330_15m.json.gz", "candles330.json.gz"]))
    compound()
