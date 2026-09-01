#!/usr/bin/env python3
"""Build an exact 15m series from the 5m file: three 5m candles make one 15m."""
import gzip
import json
import sys

import search


def main(src="candles330.json.gz", dst="candles330_15m.json.gz"):
    rows = search.load(src)                      # (ts, o, h, l, c, vol)
    buckets = {}
    for ts, o, h, l, c, v in rows:
        key = ts // 900_000 * 900_000
        b = buckets.get(key)
        if b is None:
            buckets[key] = [ts, o, h, l, c, v, 1]
        else:
            b[3] = max(b[3], h)
            b[4] = min(b[4], l)
            b[5] = c            # placeholder, fixed below
            b[6] += 1
    # rebuild properly in time order so open/close come from the right members
    out = {}
    for ts, o, h, l, c, v in sorted(rows):
        key = ts // 900_000 * 900_000
        if key not in out:
            out[key] = [str(key), o, h, l, c, v, 1]
        else:
            r = out[key]
            r[2] = max(r[2], h)
            r[3] = min(r[3], l)
            r[4] = c
            r[5] += v
            r[6] += 1
    full = [r[:6] for k, r in sorted(out.items()) if r[6] == 3]
    dropped = len(out) - len(full)
    with gzip.open(dst, "wt") as f:
        json.dump([[r[0], str(r[1]), str(r[2]), str(r[3]), str(r[4]), str(r[5])]
                   for r in full], f)
    print(f"{len(rows)} 5m candles -> {len(full)} complete 15m candles "
          f"({dropped} partial buckets dropped) -> {dst}")


if __name__ == "__main__":
    main(*(sys.argv[1:3] or ["candles330.json.gz", "candles330_15m.json.gz"]))
