"""Логин и переиспользование сессии."""

from __future__ import annotations

import getpass
import os
import sys

from instagrapi import Client
from instagrapi.exceptions import LoginRequired

from instaclean.storage import Storage


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def build_client(
    storage: Storage,
    password: str | None = None,
    verification_code: str = "",
    proxy: str | None = None,
    delay_range: tuple[int, int] = (2, 5),
) -> Client:
    """Поднимает клиент: сначала пробует сохранённую сессию, иначе логинится заново.

    Пароль берётся из аргумента, переменной IG_PASSWORD или спрашивается в терминале —
    в файлы он не пишется, на диск ложится только токен сессии.
    """
    cl = Client()
    cl.delay_range = list(delay_range)
    if proxy:
        cl.set_proxy(proxy)

    if storage.session_file.exists():
        try:
            cl.load_settings(storage.session_file)
            cl.get_timeline_feed()  # проверка, что сессия жива
            log(f"Сессия из кеша подошла: @{storage.username}")
            return cl
        except Exception as exc:  # noqa: BLE001 — сессия протухла, идём логиниться
            log(f"Сохранённая сессия не подошла ({type(exc).__name__}), логинимся заново.")
            cl = Client()
            cl.delay_range = list(delay_range)
            if proxy:
                cl.set_proxy(proxy)

    if password is None:
        password = os.environ.get("IG_PASSWORD")
    if not password:
        if not sys.stdin.isatty():
            raise SystemExit(
                "Нужен пароль: передай --password, задай IG_PASSWORD или запусти в терминале."
            )
        password = getpass.getpass(f"Пароль для @{storage.username}: ")

    cl.login(storage.username, password, verification_code=verification_code)
    cl.dump_settings(storage.session_file)
    try:
        storage.session_file.chmod(0o600)
    except OSError:
        pass
    log(f"Вошли как @{storage.username}, сессия сохранена в {storage.session_file}")
    return cl


def ensure_logged_in(cl: Client) -> None:
    try:
        cl.get_timeline_feed()
    except LoginRequired as exc:
        raise SystemExit("Сессия отвалилась, повтори `instaclean login`.") from exc
