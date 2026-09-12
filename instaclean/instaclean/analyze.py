"""Сведение данных: кто из подписок не лайкнул ни одного поста."""

from __future__ import annotations

import csv
from typing import Any

from instaclean.storage import Storage


def build_report(storage: Storage, count_comments: bool = False) -> dict[str, Any]:
    following = (storage.read_json(storage.following_file, {}) or {}).get("items", {})
    medias = (storage.read_json(storage.medias_file, {}) or {}).get("items", [])
    likers_store = storage.read_json(storage.likers_file, {}) or {}
    whitelist = storage.load_whitelist()

    engaged: dict[str, str] = {}
    liked_from_archive: set[str] = set()
    for mid, entry in likers_store.items():
        for pk, username in entry.get("likers", []):
            engaged[str(pk)] = username
            if entry.get("archived"):
                liked_from_archive.add(str(pk))
        if count_comments:
            for pk, username in entry.get("comments", []):
                engaged.setdefault(str(pk), username)

    scanned = {str(mid) for mid in likers_store}
    missing = [m for m in medias if m["id"] not in scanned]
    capped = [mid for mid, entry in likers_store.items() if entry.get("capped")]
    errored = [mid for mid, entry in likers_store.items() if entry.get("error")]

    candidates: list[dict[str, Any]] = []
    kept_whitelist: list[str] = []
    for pk, info in following.items():
        username = (info.get("username") or "").lower()
        if username in whitelist:
            kept_whitelist.append(username)
            continue
        if str(pk) in engaged:
            continue
        candidates.append(
            {
                "pk": str(pk),
                "username": info.get("username", ""),
                "full_name": info.get("full_name", ""),
                "is_private": info.get("is_private", False),
                "is_verified": info.get("is_verified", False),
            }
        )
    candidates.sort(key=lambda row: row["username"].lower())

    return {
        "following_total": len(following),
        "medias_total": len(medias),
        "medias_archived": sum(1 for m in medias if m.get("archived")),
        "medias_scanned": len(scanned),
        "medias_missing": [m["id"] for m in missing],
        "medias_capped": capped,
        "medias_errored": errored,
        "engaged_total": len(engaged),
        "engaged_from_archive": len(liked_from_archive),
        "whitelisted": sorted(set(kept_whitelist)),
        "candidates": candidates,
        "count_comments": count_comments,
    }


def write_report_csv(storage: Storage, report: dict[str, Any]) -> None:
    with storage.report_file.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["pk", "username", "full_name", "is_private", "is_verified", "url"])
        for row in report["candidates"]:
            writer.writerow(
                [
                    row["pk"],
                    row["username"],
                    row["full_name"],
                    row["is_private"],
                    row["is_verified"],
                    f"https://www.instagram.com/{row['username']}/",
                ]
            )
