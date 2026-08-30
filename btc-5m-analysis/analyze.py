#!/usr/bin/env python3
"""Backtest two 5m BTC candle-direction strategies on the downloaded month.

Strategy A (momentum): after every closed candle, enter at the next candle's
open in the direction that candle closed (green -> long, red -> short) and exit
at that next candle's close.

Strategy B (volatility filter): same rule, but trading pauses whenever a candle
is larger than the average size of the 10 candles before it; it resumes on the
first candle that closes opposite to that oversized candle, and that candle is
itself the next signal.
"""
import json
import sys
from datetime import datetime, timezone

FEE_ROUND_TRIP = 0.10  # % of notional, taker in + taker out (0.05% per side)


def load(path):
    raw = json.load(open(path))
    out = []
    for ts, o, h, l, c, *_ in raw:
        out.append((int(ts), float(o), float(h), float(l), float(c)))
    out.sort()
    return out


def sign(x):
    return (x > 0) - (x < 0)


def stats(trades, label):
    """trades: list of (direction, pct_return_of_the_traded_candle)"""
    n = len(trades)
    wins = sum(1 for _, r in trades if r > 0)
    losses = sum(1 for _, r in trades if r < 0)
    flat = n - wins - losses
    gross = sum(r for _, r in trades)
    net = gross - n * FEE_ROUND_TRIP
    decided = wins + losses
    return {
        "label": label,
        "trades": n,
        "wins": wins,
        "losses": losses,
        "flat": flat,
        "winrate": 100 * wins / n if n else 0.0,
        "winrate_decided": 100 * wins / decided if decided else 0.0,
        "gross_pct": gross,
        "avg_pct": gross / n if n else 0.0,
        "net_pct": net,
    }


def strategy_a(c):
    """Enter every candle in the direction of the previous candle."""
    trades = []
    for i in range(1, len(c)):
        d = sign(c[i - 1][4] - c[i - 1][1])   # previous candle body sign
        if d == 0:
            continue                          # no direction -> no signal
        o, cl = c[i][1], c[i][4]
        trades.append((d, d * (cl - o) / o * 100))
    return trades


def size(candle, mode):
    _, o, h, l, cl = candle
    return (h - l) if mode == "range" else abs(cl - o)


def strategy_b(c, mode="range", lookback=10):
    """Same rule, paused after an oversized candle until an opposite one closes."""
    trades = []
    paused_dir = 0        # direction of the oversized candle that paused us
    skipped = 0
    for i in range(lookback, len(c) - 1):
        prev = c[i]
        d = sign(prev[4] - prev[1])
        avg = sum(size(x, mode) for x in c[i - lookback:i]) / lookback
        oversized = size(prev, mode) > avg

        if oversized:
            # a big candle stops trading; its direction sets what we wait against
            paused_dir = d if d != 0 else paused_dir
            skipped += 1
            continue
        if paused_dir:
            if d != 0 and d != paused_dir:
                paused_dir = 0        # opposite candle -> resume, it is the signal
            else:
                skipped += 1
                continue
        if d == 0:
            continue
        o, cl = c[i + 1][1], c[i + 1][4]
        trades.append((d, d * (cl - o) / o * 100))
    return trades, skipped


def report(rows):
    hdr = f"{'strategy':<34}{'trades':>8}{'wins':>7}{'loss':>7}{'flat':>6}{'win%':>8}{'win% (no flat)':>16}{'gross%':>10}{'avg%':>9}{'net% (0.1% fee)':>17}"
    print(hdr)
    print("-" * len(hdr))
    for s in rows:
        print(f"{s['label']:<34}{s['trades']:>8}{s['wins']:>7}{s['losses']:>7}{s['flat']:>6}"
              f"{s['winrate']:>8.2f}{s['winrate_decided']:>16.2f}"
              f"{s['gross_pct']:>10.2f}{s['avg_pct']:>9.4f}{s['net_pct']:>17.2f}")


def main(path):
    c = load(path)
    t0 = datetime.fromtimestamp(c[0][0] / 1000, timezone.utc)
    t1 = datetime.fromtimestamp(c[-1][0] / 1000, timezone.utc)
    green = sum(1 for x in c if x[4] > x[1])
    red = sum(1 for x in c if x[4] < x[1])
    print(f"BTC-USDT 5m (OKX) — {len(c)} candles, {t0:%Y-%m-%d %H:%M} .. {t1:%Y-%m-%d %H:%M} UTC")
    print(f"price {c[0][1]:,.0f} -> {c[-1][4]:,.0f} ({(c[-1][4]/c[0][1]-1)*100:+.2f}%), "
          f"green {green} / red {red} / doji {len(c)-green-red}\n")

    a = strategy_a(c)
    rows = [stats(a, "A momentum (every candle)")]
    for mode in ("range", "body"):
        b, skipped = strategy_b(c, mode)
        rows.append(stats(b, f"B filtered, size = {mode} (skip {skipped})"))
    report(rows)

    # what the same month pays for betting the other way
    print()
    report([stats([(d, -r) for d, r in a], "A inverted (mean reversion)")])


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles.json")
