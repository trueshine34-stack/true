"""Собственно отписка: медленно, порциями, с журналом."""

from __future__ import annotations

import random
import sys
import time
from typing import Any

from instagrapi import Client

from instaclean.collect import RATE_ERRORS, SKIP_ERRORS, SOFT_ERRORS
from instaclean.storage import Storage


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def run_unfollow(
    cl: Client | None,
    storage: Storage,
    candidates: list[dict[str, Any]],
    limit: int,
    min_delay: float,
    max_delay: float,
    execute: bool,
) -> dict[str, int]:
    """Отписывается от кандидатов. Без execute=True только показывает план.

    Останавливается сама, если Instagram начал ругаться на частоту — лучше
    доделать завтра, чем словить блокировку действий.
    """
    already = storage.load_unfollowed_ids()
    queue = [row for row in candidates if row["pk"] not in already]
    if limit > 0:
        queue = queue[:limit]

    stats = {"done": 0, "failed": 0, "skipped": len(candidates) - len(queue)}
    if not queue:
        log("Отписываться не от кого: очередь пуста.")
        return stats

    if not execute:
        log(f"ПРОБНЫЙ ПРОГОН: отписались бы от {len(queue)} аккаунтов.")
        for row in queue:
            print(f"  @{row['username']}  ({row['full_name']})".rstrip())
        log("Ничего не изменено. Добавь --execute, чтобы отписаться по-настоящему.")
        return stats

    log(f"Отписываюсь от {len(queue)} аккаунтов, пауза {min_delay:.0f}–{max_delay:.0f} с между шагами.")
    for index, row in enumerate(queue, start=1):
        pk, username = row["pk"], row["username"]
        try:
            ok = cl.user_unfollow(pk)
            if ok:
                storage.log_unfollow(pk, username, "ok")
                stats["done"] += 1
                log(f"  [{index}/{len(queue)}] отписались от @{username}")
            else:
                storage.log_unfollow(pk, username, "failed", "user_unfollow вернул False")
                stats["failed"] += 1
                log(f"  [{index}/{len(queue)}] @{username}: Instagram не подтвердил отписку")
        except RATE_ERRORS as exc:
            storage.log_unfollow(pk, username, "failed", type(exc).__name__)
            log(f"  Instagram включил ограничение ({type(exc).__name__}). Останавливаюсь.")
            log("  Продолжи через несколько часов — прогресс сохранён в unfollowed.csv.")
            stats["failed"] += 1
            break
        except SKIP_ERRORS as exc:
            storage.log_unfollow(pk, username, "failed", type(exc).__name__)
            stats["failed"] += 1
            log(f"  [{index}/{len(queue)}] @{username}: пропуск ({type(exc).__name__})")
        except SOFT_ERRORS as exc:
            storage.log_unfollow(pk, username, "failed", f"{type(exc).__name__}: {exc}")
            stats["failed"] += 1
            log(f"  [{index}/{len(queue)}] @{username}: ошибка {type(exc).__name__}")
        if index < len(queue):
            time.sleep(random.uniform(min_delay, max_delay))
    return stats
