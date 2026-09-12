"""Файлы и кеш: всё лежит в ~/.instaclean/<username>/."""

from __future__ import annotations

import csv
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

BASE_DIR = Path(os.environ.get("INSTACLEAN_HOME", str(Path.home() / ".instaclean")))


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def norm_username(username: str) -> str:
    return username.strip().lower().lstrip("@")


class Storage:
    """Кеш одного аккаунта. Все длинные операции пишутся на диск по ходу дела,
    чтобы прерванный сбор можно было продолжить, а не начинать заново."""

    def __init__(self, username: str) -> None:
        self.username = norm_username(username)
        if not self.username:
            raise ValueError("пустой логин")
        self.dir = BASE_DIR / self.username
        self.dir.mkdir(parents=True, exist_ok=True)

    # --- пути ---------------------------------------------------------
    @property
    def session_file(self) -> Path:
        return self.dir / "session.json"

    @property
    def medias_file(self) -> Path:
        return self.dir / "medias.json"

    @property
    def likers_file(self) -> Path:
        return self.dir / "likers.json"

    @property
    def following_file(self) -> Path:
        return self.dir / "following.json"

    @property
    def whitelist_file(self) -> Path:
        return self.dir / "whitelist.txt"

    @property
    def unfollowed_file(self) -> Path:
        return self.dir / "unfollowed.csv"

    @property
    def report_file(self) -> Path:
        return self.dir / "candidates.csv"

    # --- json ---------------------------------------------------------
    @staticmethod
    def read_json(path: Path, default: Any) -> Any:
        if not path.exists():
            return default
        try:
            with path.open(encoding="utf-8") as fh:
                return json.load(fh)
        except (json.JSONDecodeError, OSError):
            return default

    @staticmethod
    def write_json(path: Path, data: Any) -> None:
        tmp = path.with_suffix(path.suffix + ".tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=2)
        tmp.replace(path)

    # --- белый список -------------------------------------------------
    def load_whitelist(self) -> set[str]:
        """Логины, которых нельзя трогать никогда. По одному в строке, # — комментарий."""
        if not self.whitelist_file.exists():
            self.whitelist_file.write_text(
                "# Кого не отписывать ни при каких условиях — по одному логину в строке.\n",
                encoding="utf-8",
            )
            return set()
        keep: set[str] = set()
        for line in self.whitelist_file.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                keep.add(norm_username(line))
        return keep

    def add_to_whitelist(self, usernames: list[str]) -> list[str]:
        current = self.load_whitelist()
        added = [norm_username(u) for u in usernames if norm_username(u) not in current]
        if added:
            with self.whitelist_file.open("a", encoding="utf-8") as fh:
                for name in added:
                    fh.write(name + "\n")
        return added

    # --- журнал отписок -----------------------------------------------
    def load_unfollowed_ids(self) -> set[str]:
        if not self.unfollowed_file.exists():
            return set()
        done: set[str] = set()
        with self.unfollowed_file.open(encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                if row.get("status") == "ok" and row.get("pk"):
                    done.add(str(row["pk"]))
        return done

    def log_unfollow(self, pk: str, username: str, status: str, note: str = "") -> None:
        new_file = not self.unfollowed_file.exists()
        with self.unfollowed_file.open("a", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            if new_file:
                writer.writerow(["ts", "pk", "username", "status", "note"])
            writer.writerow([now_iso(), pk, username, status, note])
