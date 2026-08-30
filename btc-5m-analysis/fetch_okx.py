#!/usr/bin/env python3
"""Download N days of BTC-USDT 5m candles from the OKX public API.

OKX returns at most 100 rows per history-candles call, newest first, so we walk
backwards with the `after` cursor until we cover the requested window.
Requests go through curl because the sandbox proxy rejects urllib's default UA.
"""
import json
import subprocess
import sys
import time

INST = "BTC-USDT"
BAR = "5m"
BAR_MS = 5 * 60 * 1000
URL = "https://www.okx.com/api/v5/market/history-candles"


def get(after):
    url = f"{URL}?instId={INST}&bar={BAR}&limit=100&after={after}"
    for attempt in range(5):
        out = subprocess.run(["curl", "-sS", "-m", "30", url],
                             capture_output=True, text=True)
        try:
            body = json.loads(out.stdout)
            if body.get("code") == "0":
                return body["data"]
            sys.stderr.write(f"api error: {body}\n")
        except json.JSONDecodeError:
            sys.stderr.write(f"bad response: {out.stdout[:200]} {out.stderr[:200]}\n")
        time.sleep(2 ** attempt)
    raise SystemExit("giving up after 5 attempts")


def main(days, out):
    end = int(time.time() * 1000) // BAR_MS * BAR_MS
    start = end - days * 24 * 60 * 60 * 1000
    rows = {}
    cursor = end
    while cursor > start:
        data = get(cursor)
        if not data:
            break
        for c in data:
            ts = int(c[0])
            if ts >= start:
                rows[ts] = c
        cursor = min(int(c[0]) for c in data)
        sys.stderr.write(f"\r{len(rows)} candles ...")
    sys.stderr.write("\n")
    candles = [rows[k] for k in sorted(rows)]
    with open(out, "w") as f:
        json.dump(candles, f)
    print(f"{len(candles)} candles -> {out}")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 30,
         sys.argv[2] if len(sys.argv) > 2 else "candles.json")
