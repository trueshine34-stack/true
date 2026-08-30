#!/usr/bin/env python3
"""Brute-force search for a >70% directional pattern on 5m BTC candles.

Every predicate is compiled into a bitmask over candle indices, so conjunctions
of up to three conditions are just AND + popcount. Patterns are mined on the
first 70% of the history and then checked, untouched, on the last 30%.
"""
import gzip
import itertools
import json
import random
import sys
from datetime import datetime, timezone

import os
MIN_N_TRAIN = int(os.environ.get("MIN_N", 150))    # pattern must fire this often
MIN_WR_TRAIN = float(os.environ.get("MIN_WR", 0.58))  # ... and beat this in-sample
LOOKBACK = 60


def load(path):
    """Reads candles.json, gzipped or not."""
    opener = gzip.open if path.endswith(".gz") else open
    rows = []
    for ts, o, h, l, c, vol, *_ in json.load(opener(path, "rt")):
        rows.append((int(ts), float(o), float(h), float(l), float(c), float(vol)))
    rows.sort()
    return rows


def build_predicates(k):
    """k = list of (ts, open, high, low, close, volume). Returns {name: [bool]}."""
    n = len(k)
    o = [x[1] for x in k]; h = [x[2] for x in k]
    lo = [x[3] for x in k]; c = [x[4] for x in k]; v = [x[5] for x in k]
    body = [abs(c[i] - o[i]) for i in range(n)]
    rng = [h[i] - lo[i] for i in range(n)]
    up = [c[i] > o[i] for i in range(n)]
    dn = [c[i] < o[i] for i in range(n)]

    def rolling_mean(src, w):
        out = [0.0] * n
        s = sum(src[:w])
        for i in range(w, n):
            out[i] = s / w
            s += src[i] - src[i - w]
        return out

    body10 = rolling_mean(body, 10)
    rng10 = rolling_mean(rng, 10)
    vol10 = rolling_mean(v, 10)
    sma20 = rolling_mean(c, 20)
    sma50 = rolling_mean(c, 50)

    run = [0] * n
    for i in range(n):
        d = 1 if up[i] else (-1 if dn[i] else 0)
        p = 1 if up[i - 1] else (-1 if dn[i - 1] else 0)
        run[i] = run[i - 1] + 1 if i and d and d == p else (1 if d else 0)
    rundir = [1 if up[i] else (-1 if dn[i] else 0) for i in range(n)]

    green10 = [0] * n
    s = sum(up[:10])
    for i in range(10, n):
        green10[i] = s
        s += up[i] - up[i - 10]

    hour = [datetime.fromtimestamp(x[0] / 1000, timezone.utc).hour for x in k]
    dow = [datetime.fromtimestamp(x[0] / 1000, timezone.utc).weekday() for x in k]

    P = {}
    P["c1_up"] = up[:]
    P["c1_dn"] = dn[:]
    P["c2_up"] = [up[i - 1] for i in range(n)]
    P["c2_dn"] = [dn[i - 1] for i in range(n)]
    P["c3_up"] = [up[i - 2] for i in range(n)]
    P["c3_dn"] = [dn[i - 2] for i in range(n)]
    P["run_up>=3"] = [run[i] >= 3 and rundir[i] > 0 for i in range(n)]
    P["run_dn>=3"] = [run[i] >= 3 and rundir[i] < 0 for i in range(n)]
    P["body>1.5avg"] = [body[i] > 1.5 * body10[i] for i in range(n)]
    P["body<0.5avg"] = [body[i] < 0.5 * body10[i] for i in range(n)]
    P["range>1.5avg"] = [rng[i] > 1.5 * rng10[i] for i in range(n)]
    P["range<0.7avg"] = [rng[i] < 0.7 * rng10[i] for i in range(n)]
    P["vol>1.5avg"] = [v[i] > 1.5 * vol10[i] for i in range(n)]
    P["vol<0.7avg"] = [v[i] < 0.7 * vol10[i] for i in range(n)]
    P["upper_wick>body"] = [h[i] - max(o[i], c[i]) > body[i] for i in range(n)]
    P["lower_wick>body"] = [min(o[i], c[i]) - lo[i] > body[i] for i in range(n)]
    P["close_top25%"] = [rng[i] > 0 and (c[i] - lo[i]) / rng[i] > 0.75 for i in range(n)]
    P["close_bot25%"] = [rng[i] > 0 and (c[i] - lo[i]) / rng[i] < 0.25 for i in range(n)]
    P["above_sma20"] = [c[i] > sma20[i] for i in range(n)]
    P["below_sma20"] = [c[i] < sma20[i] for i in range(n)]
    P["above_sma50"] = [c[i] > sma50[i] for i in range(n)]
    P["below_sma50"] = [c[i] < sma50[i] for i in range(n)]
    P["green10>=7"] = [green10[i] >= 7 for i in range(n)]
    P["green10<=3"] = [green10[i] <= 3 for i in range(n)]
    P["new_high20"] = [i >= 19 and h[i] >= max(h[i - 19:i + 1]) for i in range(n)]
    P["new_low20"] = [i >= 19 and lo[i] <= min(lo[i - 19:i + 1]) for i in range(n)]
    for a, b, lab in ((0, 6, "00-06"), (6, 12, "06-12"), (12, 18, "12-18"), (18, 24, "18-24")):
        P[f"utc_{lab}"] = [a <= hour[i] < b for i in range(n)]
    P["weekend"] = [dow[i] >= 5 for i in range(n)]
    P["weekday"] = [dow[i] < 5 for i in range(n)]
    return P


def to_mask(flags, valid):
    m = 0
    for i, f in enumerate(flags):
        if f and valid[i]:
            m |= 1 << i
    return m


def wr(mask, up_mask, dn_mask):
    a = (mask & up_mask).bit_count()
    b = (mask & dn_mask).bit_count()
    tot = a + b
    if not tot:
        return 0, 0, 0.0
    return tot, (1 if a >= b else -1), max(a, b) / tot


def shuffle_labels(up_next, dn_next, valid, seed):
    """Keep the same predicates, randomise which candle went up — the win rate
    the search still reports on that is the part it manufactured itself."""
    rnd = random.Random(seed)
    idx = [i for i in range(len(valid)) if valid[i] and (up_next[i] or dn_next[i])]
    labels = [up_next[i] for i in idx]
    rnd.shuffle(labels)
    u = [False] * len(valid); d = [False] * len(valid)
    for i, lab in zip(idx, labels):
        u[i] = lab
        d[i] = not lab
    return u, d


def main(path, max_terms=3, shuffle_seed=None):
    k = load(path)
    n = len(k)
    t0 = datetime.fromtimestamp(k[0][0] / 1000, timezone.utc)
    t1 = datetime.fromtimestamp(k[-1][0] / 1000, timezone.utc)
    print(f"{n} candles, {t0:%Y-%m-%d} .. {t1:%Y-%m-%d} UTC")

    valid = [LOOKBACK <= i < n - 1 for i in range(n)]
    up_next = [valid[i] and k[i + 1][4] > k[i + 1][1] for i in range(n)]
    dn_next = [valid[i] and k[i + 1][4] < k[i + 1][1] for i in range(n)]
    if shuffle_seed is not None:
        up_next, dn_next = shuffle_labels(up_next, dn_next, valid, shuffle_seed)
        print(f"*** labels shuffled (seed {shuffle_seed}) — this is the noise baseline ***")

    split = int(n * 0.7)
    train = [valid[i] and i < split for i in range(n)]
    test = [valid[i] and i >= split for i in range(n)]
    print(f"train: candles {LOOKBACK}..{split} ({t0:%Y-%m-%d} .. "
          f"{datetime.fromtimestamp(k[split][0]/1000, timezone.utc):%Y-%m-%d}), "
          f"test: the rest\n")

    UP = to_mask(up_next, valid); DN = to_mask(dn_next, valid)
    TRAIN = to_mask(train, valid); TEST = to_mask(test, valid)

    P = build_predicates(k)
    masks = {name: to_mask(f, valid) for name, f in P.items()}
    names = sorted(masks)
    print(f"{len(names)} predicates, combinations up to {max_terms} terms")

    found = []
    for r in range(1, max_terms + 1):
        for combo in itertools.combinations(names, r):
            m = TRAIN
            for name in combo:
                m &= masks[name]
                if m == 0:
                    break
            if m == 0:
                continue
            cnt, side, rate = wr(m, UP, DN)
            if cnt >= MIN_N_TRAIN and rate >= MIN_WR_TRAIN:
                found.append((rate, cnt, side, combo))
    found.sort(reverse=True)
    print(f"patterns passing train filter (n>={MIN_N_TRAIN}, wr>={MIN_WR_TRAIN:.0%}): {len(found)}\n")

    hdr = f"{'pattern':<62}{'n_tr':>6}{'wr_tr':>8}{'n_te':>6}{'wr_te':>8}{'side':>6}"
    print(hdr); print("-" * len(hdr))
    for rate, cnt, side, combo in found[:25]:
        m = TEST
        for name in combo:
            m &= masks[name]
        a = (m & UP).bit_count(); b = (m & DN).bit_count()
        tot = a + b
        # out-of-sample hit rate for the side chosen in-sample
        hit = (a if side > 0 else b) / tot if tot else float("nan")
        label = " + ".join(combo)
        print(f"{label:<62}{cnt:>6}{100*rate:>7.1f}%{tot:>6}"
              f"{(100*hit if tot else float('nan')):>7.1f}%{'LONG' if side > 0 else 'SHORT':>6}")

    if not found:
        best = []
        for name in names:
            m = TRAIN & masks[name]
            cnt, side, rate = wr(m, UP, DN)
            if cnt >= MIN_N_TRAIN:
                best.append((rate, cnt, name))
        best.sort(reverse=True)
        print("nothing passed; best single predicates in-sample:")
        for rate, cnt, name in best[:10]:
            print(f"  {name:<20} n={cnt:6d}  {100*rate:.2f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "candles.json",
         int(sys.argv[2]) if len(sys.argv) > 2 else 3,
         int(sys.argv[3]) if len(sys.argv) > 3 else None)
