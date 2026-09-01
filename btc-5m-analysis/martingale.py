#!/usr/bin/env python3
"""Martingale on the fade-after-6-in-a-row signal, priced as a binary contract.

Rules simulated: when a run reaches exactly 6 same-direction 5m candles, buy the
opposite side at 50c and exit at 99c — a win pays +98% of the stake, a loss costs
100%. Stake starts at $100; after a loss the next candle is bought on the SAME
side at double the stake, until a win closes the sequence.

The capital line is what the account actually has to fund: cash goes out when a
bet is placed and comes back at 1.98x on a win, so the deepest point of that line
is the deposit the strategy needs.
"""
import sys
from datetime import datetime

import nightruns
import search

BUY, SELL = 0.50, 0.99          # contract bought at 50c, closed at 99c
WIN_MULT = SELL / BUY - 1        # +0.98 per 1 staked
BASE = 100.0


def simulate(k, t, d, run, lo, hi, night_only=False, max_doubles=None, base=BASE):
    """Walk the candles once; returns the trade log and the capital line."""
    seqs = []
    cash = 0.0            # running cash flow, starts at zero
    min_cash = 0.0
    i = lo
    max_stake = 0.0
    while i < hi - 1:
        if run[i] != 6 or d[i] == 0 or (night_only and t[i].hour >= 8):
            i += 1
            continue
        side = -d[i]                      # fade the run
        stake, losses, spent = base, 0, 0.0
        j = i + 1
        won = False
        while j < hi:
            nd = d[j]
            if nd == 0:                   # doji settles neither way: skip the candle
                j += 1
                continue
            cash -= stake
            spent += stake
            max_stake = max(max_stake, stake)
            min_cash = min(min_cash, cash)
            if nd == side:
                cash += stake * (1 + WIN_MULT)
                won = True
                break
            losses += 1
            if max_doubles is not None and losses > max_doubles:
                break                     # give up, take the loss
            stake *= 2
            j += 1
        seqs.append({"start": t[i], "losses": losses, "won": won,
                     "spent": spent, "pnl": cash})
        i = j + 1                          # next signal only after this one closes
    return seqs, cash, -min_cash, max_stake


def report(label, seqs, cash, need, max_stake, days):
    n = len(seqs)
    if not n:
        print(f"{label}: no signals")
        return
    lost = sum(1 for s in seqs if not s["won"])
    worst = max(s["losses"] for s in seqs)
    print(f"\n{label}")
    print(f"  sequences            : {n}  ({n/days:.2f} per day)")
    print(f"  bets placed          : {sum(s['losses'] + (1 if s['won'] else 0) for s in seqs)}")
    print(f"  worst losing streak  : {worst} losses in a row")
    print(f"  sequences never won  : {lost}")
    print(f"  profit               : ${cash:,.2f}")
    print(f"  deposit required     : ${need:,.2f}")
    print(f"  largest single bet   : ${max_stake:,.0f}")


def main(path="candles240.json.gz", days=100):
    k = search.load(path)
    t, d, run = nightruns.__dict__["datetime"], None, None
    t = [datetime.fromtimestamp(x[0] / 1000, nightruns.ICT) for x in k]
    d = [(x[4] > x[1]) - (x[4] < x[1]) for x in k]
    run = [0] * len(k)
    for i in range(len(k)):
        run[i] = run[i - 1] + 1 if i and d[i] and d[i] == d[i - 1] else (1 if d[i] else 0)

    lo = max(0, len(k) - days * 288)
    print(f"last {days} days: {t[lo]:%Y-%m-%d %H:%M} .. {t[-1]:%Y-%m-%d %H:%M} ICT, "
          f"{len(k)-lo} candles")
    print(f"contract: buy {BUY*100:.0f}c, close {SELL*100:.0f}c -> win +{WIN_MULT*100:.0f}%, "
          f"loss -100%; base stake ${BASE:.0f}")
    print(f"break-even hit rate at this price: {100*BUY/(SELL):.1f}%"
          f"  (1 / (1 + {WIN_MULT:.2f}))")

    mart_needs = {}
    for label, night, cap in (("MARTINGALE, all hours", False, None),
                              ("MARTINGALE, 00:00-08:00 ICT only", True, None),
                              ("MARTINGALE, all hours, cap 4 doublings", False, 4),
                              ("MARTINGALE, night only, cap 4 doublings", True, 4)):
        seqs, cash, need, ms = simulate(k, t, d, run, lo, len(k), night, cap)
        if cap is None:
            mart_needs[night] = need
        report(label, seqs, cash, need, ms, days)

    # flat stake on the same signal, sized to the same deposit, for comparison
    for label, night in (("FLAT stake, all hours", False),
                         ("FLAT stake, 00:00-08:00 ICT", True)):
        wins = losses = 0
        cash = 0.0; low = 0.0
        for i in range(lo, len(k) - 1):
            if run[i] != 6 or d[i] == 0 or (night and t[i].hour >= 8) or d[i + 1] == 0:
                continue
            cash -= BASE
            low = min(low, cash)
            if d[i + 1] == -d[i]:
                cash += BASE * (1 + WIN_MULT); wins += 1
            else:
                losses += 1
        n = wins + losses
        need = -low
        # scale the flat bet up until it needs the same deposit as the martingale
        mart_need = mart_needs[night]
        scale = mart_need / need if need else 1
        print(f"\n{label}")
        print(f"  bets {n}, hit rate {100*wins/n:.2f}%")
        print(f"  at ${BASE:.0f} a bet : profit ${cash:,.2f}, deposit ${need:,.2f}")
        print(f"  at ${BASE*scale:,.0f} a bet (same deposit as the martingale): "
              f"profit ${cash*scale:,.2f}")


def sensitivity(rates=(0.536, 0.583, 0.6475)):
    """The whole thing lives or dies on the payout, so price it out."""
    print("\n\nprice sensitivity, flat $100 per bet")
    hdr = f"{'buy':>6}{'sell':>7}{'payout':>9}{'break-even':>12}" + "".join(
        f"{'EV @ ' + format(100*r, '.1f') + '%':>15}" for r in rates)
    print(hdr); print("-" * len(hdr))
    for buy in (0.50, 0.51, 0.52):
        for sell in (0.99, 0.95, 0.90, 0.85):
            m = sell / buy - 1
            row = f"{buy*100:>5.0f}c{sell*100:>6.0f}c{m*100:>8.0f}%{100*buy/sell:>11.1f}%"
            for r in rates:
                row += f"{r*100*m - (1-r)*100:>+14.2f}$"
            print(row)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles240.json.gz",
         int(sys.argv[2]) if len(sys.argv) > 2 else 100)
    sensitivity()
