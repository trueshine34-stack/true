"""Сбор данных: посты (включая архив), лайкеры каждого поста, список подписок."""

from __future__ import annotations

import random
import sys
import time
from typing import Any, Callable, Iterable

from instagrapi import Client
from instagrapi import exceptions as ig_exc

from instaclean.storage import Storage, now_iso

ARCHIVE_ENDPOINT = "feed/only_me_feed/"

# Instagram отдаёт ограниченный срез лайкнувших. Если пришло меньше, чем like_count,
# значит часть лайков мы не видим — такие посты помечаем как "capped".
LIKERS_PAGE_HINT = 1000


def _exc(*names: str) -> tuple[type[BaseException], ...]:
    """Не все версии instagrapi экспортируют одинаковый набор исключений."""
    found = [getattr(ig_exc, name, None) for name in names]
    return tuple(e for e in found if isinstance(e, type)) or (ig_exc.ClientError,)


RATE_ERRORS = _exc("PleaseWaitFewMinutes", "RateLimitError", "ClientThrottledError")
SOFT_ERRORS = _exc(
    "ClientConnectionError", "ClientJSONDecodeError", "ClientRequestTimeout", "ClientError"
)
SKIP_ERRORS = _exc("MediaNotFound", "ClientNotFoundError", "ClientForbiddenError")


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def _retry(fn: Callable[..., Any], *args: Any, what: str = "запрос", attempts: int = 4, **kwargs: Any) -> Any:
    """Повтор с экспоненциальной паузой. На rate limit ждём заметно дольше."""
    delay = 10.0
    last: BaseException | None = None
    for attempt in range(1, attempts + 1):
        try:
            return fn(*args, **kwargs)
        except RATE_ERRORS as exc:
            last = exc
            wait = 120.0 * attempt
            log(f"  Instagram просит подождать ({what}), пауза {wait:.0f} c…")
            time.sleep(wait)
        except SKIP_ERRORS:
            raise
        except SOFT_ERRORS as exc:
            last = exc
            if attempt == attempts:
                break
            log(f"  Сбой: {type(exc).__name__} ({what}), повтор через {delay:.0f} c…")
            time.sleep(delay)
            delay *= 2
    assert last is not None
    raise last


def _pause(low: float, high: float) -> None:
    time.sleep(random.uniform(low, high))


# ---------------------------------------------------------------- посты


def _row_from_model(media: Any, archived: bool) -> dict[str, Any]:
    return {
        "id": str(media.id),
        "pk": str(media.pk),
        "code": getattr(media, "code", None),
        "taken_at": media.taken_at.isoformat() if getattr(media, "taken_at", None) else None,
        "like_count": int(getattr(media, "like_count", 0) or 0),
        "media_type": int(getattr(media, "media_type", 0) or 0),
        "archived": archived,
    }


def _row_from_raw(item: dict[str, Any], archived: bool) -> dict[str, Any] | None:
    """Архивная лента приходит сырым JSON — разбираем её без моделей instagrapi,
    чтобы не зависеть от того, какие поля умеет парсить конкретная версия."""
    if "media" in item and isinstance(item["media"], dict):
        item = item["media"]
    raw_id = str(item.get("id") or "")
    pk = str(item.get("pk") or (raw_id.split("_")[0] if raw_id else ""))
    if not pk:
        return None
    taken_at = item.get("taken_at")
    return {
        "id": raw_id or pk,
        "pk": pk,
        "code": item.get("code"),
        "taken_at": (
            time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(taken_at)) if taken_at else None
        ),
        "like_count": int(item.get("like_count") or 0),
        "media_type": int(item.get("media_type") or 0),
        "archived": archived,
    }


def fetch_archived_medias(cl: Client, max_pages: int = 100) -> list[dict[str, Any]]:
    """Архивные публикации живут в приватном эндпоинте feed/only_me_feed/."""
    rows: list[dict[str, Any]] = []
    next_max_id: str | None = None
    for _ in range(max_pages):
        params: dict[str, Any] = {}
        if next_max_id:
            params["max_id"] = next_max_id
        result = _retry(cl.private_request, ARCHIVE_ENDPOINT, params=params, what="архив")
        for item in result.get("items", []) or []:
            row = _row_from_raw(item, archived=True)
            if row:
                rows.append(row)
        next_max_id = result.get("next_max_id")
        if not result.get("more_available") or not next_max_id:
            break
        _pause(1.5, 4.0)
    return rows


def collect_medias(
    cl: Client, storage: Storage, include_archive: bool = True, refresh: bool = False
) -> list[dict[str, Any]]:
    cached = storage.read_json(storage.medias_file, None)
    if cached and not refresh:
        log(f"Посты из кеша: {len(cached['items'])} (собраны {cached.get('fetched_at')})")
        return cached["items"]

    user_id = str(cl.user_id)
    log("Собираю обычные публикации…")
    try:
        medias = _retry(cl.user_medias, user_id, 0, what="лента постов")
    except Exception as exc:  # noqa: BLE001 — GraphQL иногда капризничает, есть запасной путь
        log(f"  Не вышло через основной путь ({type(exc).__name__}), пробую user_medias_v1…")
        medias = _retry(cl.user_medias_v1, user_id, 0, what="лента постов (v1)")
    rows = [_row_from_model(m, archived=False) for m in medias]
    log(f"  Обычных публикаций: {len(rows)}")

    if include_archive:
        log("Собираю архивные публикации…")
        try:
            archived = fetch_archived_medias(cl)
            known = {r["pk"] for r in rows}
            archived = [r for r in archived if r["pk"] not in known]
            rows.extend(archived)
            log(f"  Архивных публикаций: {len(archived)}")
        except Exception as exc:  # noqa: BLE001
            log(f"  ВНИМАНИЕ: архив прочитать не удалось ({type(exc).__name__}: {exc}).")
            log("  Лайки из архивных постов не будут учтены — это может добавить лишних кандидатов.")

    storage.write_json(storage.medias_file, {"fetched_at": now_iso(), "items": rows})
    log(f"Всего публикаций: {len(rows)}")
    return rows


# ------------------------------------------------------------- лайкеры


def collect_engagement(
    cl: Client,
    storage: Storage,
    medias: Iterable[dict[str, Any]],
    with_comments: bool = False,
    min_delay: float = 1.5,
    max_delay: float = 4.0,
) -> dict[str, Any]:
    """Для каждого поста тянет список лайкнувших (и, по желанию, комментаторов).

    Результат дописывается в likers.json после каждого поста, так что сбор
    можно прервать и продолжить позже с того же места.
    """
    store: dict[str, Any] = storage.read_json(storage.likers_file, {})
    medias = list(medias)
    todo = [
        m
        for m in medias
        if m["id"] not in store or (with_comments and "comments" not in store.get(m["id"], {}))
    ]
    if not todo:
        log(f"Лайкеры уже собраны по всем {len(medias)} постам.")
        return store

    log(f"Собираю лайки: {len(todo)} постов из {len(medias)} (остальное в кеше).")
    for index, media in enumerate(todo, start=1):
        mid = media["id"]
        entry: dict[str, Any] = store.get(mid, {})
        tag = "архив" if media["archived"] else "лента"
        try:
            if "likers" not in entry:
                likers = _retry(cl.media_likers, mid, what=f"лайки {mid}")
                entry["likers"] = [[str(u.pk), u.username] for u in likers]
            if with_comments and "comments" not in entry:
                comments = _retry(cl.media_comments, mid, 0, what=f"комментарии {mid}")
                entry["comments"] = sorted(
                    {(str(c.user.pk), c.user.username) for c in comments if c.user}
                )
        except SKIP_ERRORS as exc:
            log(f"  [{index}/{len(todo)}] {mid} ({tag}) — пропуск: {type(exc).__name__}")
            entry.setdefault("likers", [])
            entry["error"] = type(exc).__name__
        entry["like_count"] = media["like_count"]
        entry["archived"] = media["archived"]
        entry["capped"] = len(entry.get("likers", [])) < media["like_count"]
        entry["fetched_at"] = now_iso()
        store[mid] = entry
        storage.write_json(storage.likers_file, store)
        log(
            f"  [{index}/{len(todo)}] {tag} {media.get('code') or mid}: "
            f"лайков видно {len(entry.get('likers', []))} из {media['like_count']}"
        )
        if index < len(todo):
            _pause(min_delay, max_delay)
    return store


# ------------------------------------------------------------ подписки


def collect_following(cl: Client, storage: Storage, refresh: bool = False) -> dict[str, Any]:
    cached = storage.read_json(storage.following_file, None)
    if cached and not refresh:
        log(f"Подписки из кеша: {len(cached['items'])} (собраны {cached.get('fetched_at')})")
        return cached["items"]

    log("Собираю список подписок…")
    following = _retry(cl.user_following, str(cl.user_id), 0, what="подписки")
    items = {
        str(pk): {
            "username": user.username,
            "full_name": getattr(user, "full_name", "") or "",
            "is_private": bool(getattr(user, "is_private", False)),
            "is_verified": bool(getattr(user, "is_verified", False)),
        }
        for pk, user in following.items()
    }
    storage.write_json(storage.following_file, {"fetched_at": now_iso(), "items": items})
    log(f"Всего подписок: {len(items)}")
    return items
