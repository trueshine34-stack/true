#!/usr/bin/env python3
"""A >70% win rate is easy to buy with an asymmetric target — this prices it.

Enter at every candle's open (both directions are symmetric enough on 5m that
the side barely matters; longs are shown), take profit at +tp%, stop at -sl%,
give up after `hold` candles and exit at market. When a candle's range covers
both levels the stop is assumed to hit first.
"""
import sys
import analyze

FEE = 0.10  # % round trip


def simulate(c, tp, sl, hold, side=1):
    wins = losses = timeouts = 0
    pnl = 0.0
    for i in range(len(c) - hold - 1):
        entry = c[i + 1][1]
        tp_px = entry * (1 + side * tp / 100)
        sl_px = entry * (1 - side * sl / 100)
        done = False
        for j in range(i + 1, i + 1 + hold):
            _, _, h, l, cl = c[j]
            hit_sl = (l <= sl_px) if side > 0 else (h >= sl_px)
            hit_tp = (h >= tp_px) if side > 0 else (l <= tp_px)
            if hit_sl:                 # pessimistic: stop first inside a candle
                losses += 1; pnl -= sl; done = True; break
            if hit_tp:
                wins += 1; pnl += tp; done = True; break
        if not done:
            r = side * (c[i + hold][4] - entry) / entry * 100
            pnl += r
            timeouts += 1
            wins += r > 0
            losses += r < 0
    n = wins + losses + 0
    total = wins + losses
    return {
        "trades": wins + losses,
        "wins": wins, "losses": losses, "timeouts": timeouts,
        "winrate": 100 * wins / total if total else 0,
        "gross": pnl,
        "avg": pnl / total if total else 0,
        "net_avg": pnl / total - FEE if total else 0,
    }


def main(path="candles.json"):
    c = analyze.load(path)
    print(f"{len(c)} candles. Long at every candle open, TP/SL in %, hold in candles.")
    hdr = f"{'TP%':>6}{'SL%':>7}{'hold':>6}{'trades':>8}{'win%':>8}{'avg %/trade':>13}{'after 0.1% fee':>16}{'gross %':>10}"
    print(hdr); print("-" * len(hdr))
    for tp, sl, hold in ((0.05, 0.50, 24), (0.10, 0.50, 24), (0.10, 1.00, 48),
                         (0.20, 1.00, 48), (0.05, 0.20, 12), (0.30, 0.30, 24),
                         (0.50, 0.50, 48), (1.00, 0.50, 48)):
        s = simulate(c, tp, sl, hold)
        print(f"{tp:>6.2f}{sl:>7.2f}{hold:>6}{s['trades']:>8}{s['winrate']:>8.2f}"
              f"{s['avg']:>13.4f}{s['net_avg']:>16.4f}{s['gross']:>10.1f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles.json")
