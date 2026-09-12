"""Командный интерфейс instaclean."""

from __future__ import annotations

import argparse
import os
import sys
from typing import Any

from instaclean import __version__
from instaclean.analyze import build_report, write_report_csv
from instaclean.client import build_client
from instaclean.collect import collect_engagement, collect_following, collect_medias
from instaclean.storage import Storage
from instaclean.unfollow import run_unfollow


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def _resolve_username(args: argparse.Namespace) -> str:
    username = args.username or os.environ.get("IG_USERNAME")
    if not username:
        raise SystemExit("Укажи логин: --username <логин> или переменная IG_USERNAME.")
    return username


def _client(args: argparse.Namespace, storage: Storage):
    return build_client(
        storage,
        password=args.password,
        verification_code=args.code or "",
        proxy=args.proxy,
    )


def _print_report(report: dict[str, Any], preview: int) -> None:
    print()
    print("=" * 56)
    print(f"Подписок всего:                {report['following_total']}")
    print(
        f"Публикаций просканировано:     {report['medias_scanned']} из {report['medias_total']}"
        f" (архивных: {report['medias_archived']})"
    )
    print(f"Лайкали хотя бы раз:           {report['engaged_total']}")
    print(f"   из них через архивные:      {report['engaged_from_archive']}")
    if report["count_comments"]:
        print("   (комментаторы тоже засчитаны как активные)")
    print(f"Ни одного лайка — кандидаты:   {len(report['candidates'])}")
    if report["whitelisted"]:
        print(f"В белом списке (не трогаем):   {len(report['whitelisted'])}")
    print("=" * 56)

    if report["medias_missing"]:
        log(f"! По {len(report['medias_missing'])} постам лайки ещё не собраны — доделай `scan`.")
    if report["medias_errored"]:
        log(f"! По {len(report['medias_errored'])} постам лайки прочитать не удалось.")
    if report["medias_capped"]:
        log(
            f"! У {len(report['medias_capped'])} постов Instagram отдал не всех лайкнувших "
            "(так бывает у постов с большим числом лайков) — часть людей может попасть "
            "в кандидаты ошибочно. Проверь список глазами перед отпиской."
        )

    if preview and report["candidates"]:
        print("\nПервые кандидаты на отписку:")
        for row in report["candidates"][:preview]:
            mark = " ✔" if row["is_verified"] else ""
            print(f"  @{row['username']}{mark}  {row['full_name']}".rstrip())
        if len(report["candidates"]) > preview:
            print(f"  … и ещё {len(report['candidates']) - preview}")


def _do_scan(args: argparse.Namespace, storage: Storage) -> None:
    cl = _client(args, storage)
    medias = collect_medias(
        cl, storage, include_archive=not args.no_archive, refresh=args.refresh
    )
    collect_engagement(
        cl,
        storage,
        medias,
        with_comments=args.with_comments,
        min_delay=args.scan_delay[0],
        max_delay=args.scan_delay[1],
    )
    collect_following(cl, storage, refresh=args.refresh)


def cmd_login(args: argparse.Namespace) -> int:
    storage = Storage(_resolve_username(args))
    cl = _client(args, storage)
    info = cl.account_info()
    print(f"Готово: @{info.username} (id {cl.user_id}). Сессия лежит в {storage.session_file}")
    return 0


def cmd_scan(args: argparse.Namespace) -> int:
    storage = Storage(_resolve_username(args))
    _do_scan(args, storage)
    report = build_report(storage, count_comments=args.with_comments)
    write_report_csv(storage, report)
    _print_report(report, args.preview)
    print(f"\nСписок кандидатов: {storage.report_file}")
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    storage = Storage(_resolve_username(args))
    report = build_report(storage, count_comments=args.with_comments)
    if not report["medias_total"] or not report["following_total"]:
        raise SystemExit("Нет данных. Сначала запусти `instaclean scan`.")
    write_report_csv(storage, report)
    _print_report(report, args.preview)
    print(f"\nСписок кандидатов: {storage.report_file}")
    return 0


def _unfollow_stage(args: argparse.Namespace, storage: Storage) -> int:
    report = build_report(storage, count_comments=args.with_comments)
    if not report["medias_total"] or not report["following_total"]:
        raise SystemExit("Нет данных. Сначала запусти `instaclean scan`.")
    if report["medias_missing"] and not args.force:
        raise SystemExit(
            f"По {len(report['medias_missing'])} постам лайки не собраны — часть людей попадёт "
            "в отписку зря. Доделай `instaclean scan` или запусти с --force."
        )
    write_report_csv(storage, report)
    _print_report(report, args.preview)
    print(f"\nСписок кандидатов: {storage.report_file}")

    cl = _client(args, storage) if args.execute else None
    stats = run_unfollow(
        cl,
        storage,
        report["candidates"],
        limit=args.limit,
        min_delay=args.delay[0],
        max_delay=args.delay[1],
        execute=args.execute,
    )
    if args.execute:
        print(f"\nОтписались: {stats['done']}, с ошибкой: {stats['failed']}")
        print(f"Журнал: {storage.unfollowed_file}")
        remaining = len(report["candidates"]) - stats["done"] - stats["skipped"]
        if remaining > 0:
            print(f"Осталось на следующий заход: ~{remaining}. Повтори команду позже.")
    return 0


def cmd_unfollow(args: argparse.Namespace) -> int:
    return _unfollow_stage(args, Storage(_resolve_username(args)))


def cmd_clean(args: argparse.Namespace) -> int:
    """Всё одной командой: собрать данные, показать отчёт, отписаться."""
    storage = Storage(_resolve_username(args))
    _do_scan(args, storage)
    return _unfollow_stage(args, storage)


def cmd_whitelist(args: argparse.Namespace) -> int:
    storage = Storage(_resolve_username(args))
    if args.add:
        added = storage.add_to_whitelist(args.add)
        print(f"Добавлено в белый список: {len(added)}")
    current = sorted(storage.load_whitelist())
    print(f"Белый список ({len(current)}) — {storage.whitelist_file}")
    for name in current:
        print(f"  @{name}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="instaclean",
        description="Чистка подписок в Instagram: отписаться от тех, кто ни разу не лайкнул "
        "ваши публикации (архивные считаются наравне с обычными).",
    )
    parser.add_argument("--version", action="version", version=f"instaclean {__version__}")

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-u", "--username", help="ваш логин (или переменная IG_USERNAME)")
    common.add_argument("--password", help="пароль (лучше через IG_PASSWORD или ввод в терминале)")
    common.add_argument("--code", help="код двухфакторной аутентификации")
    common.add_argument("--proxy", help="прокси, напр. http://user:pass@host:port")
    common.add_argument(
        "--with-comments",
        action="store_true",
        help="считать активными и тех, кто комментировал, а не только лайкал",
    )
    common.add_argument(
        "--preview", type=int, default=20, help="сколько кандидатов показать в консоли (0 — не показывать)"
    )

    scan_opts = argparse.ArgumentParser(add_help=False)
    scan_opts.add_argument("--no-archive", action="store_true", help="не читать архивные публикации")
    scan_opts.add_argument("--refresh", action="store_true", help="перечитать посты и подписки, игнорируя кеш")
    scan_opts.add_argument(
        "--scan-delay",
        nargs=2,
        type=float,
        default=[1.5, 4.0],
        metavar=("МИН", "МАКС"),
        help="пауза между запросами лайков, секунды (по умолчанию 1.5 4)",
    )

    unfollow_opts = argparse.ArgumentParser(add_help=False)
    unfollow_opts.add_argument(
        "--execute", action="store_true", help="реально отписаться (без флага — только показать список)"
    )
    unfollow_opts.add_argument(
        "--limit", type=int, default=50, help="максимум отписок за запуск (0 — без ограничения)"
    )
    unfollow_opts.add_argument(
        "--delay",
        nargs=2,
        type=float,
        default=[25.0, 70.0],
        metavar=("МИН", "МАКС"),
        help="пауза между отписками, секунды (по умолчанию 25 70)",
    )
    unfollow_opts.add_argument(
        "--force", action="store_true", help="отписываться, даже если лайки собраны не по всем постам"
    )

    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("login", parents=[common], help="войти и сохранить сессию")
    p.set_defaults(func=cmd_login)

    p = sub.add_parser("scan", parents=[common, scan_opts], help="собрать посты, лайки и подписки")
    p.set_defaults(func=cmd_scan)

    p = sub.add_parser("report", parents=[common], help="показать отчёт по собранным данным")
    p.set_defaults(func=cmd_report)

    p = sub.add_parser(
        "unfollow", parents=[common, unfollow_opts], help="отписаться от тех, кто не лайкал"
    )
    p.set_defaults(func=cmd_unfollow)

    p = sub.add_parser(
        "clean", parents=[common, scan_opts, unfollow_opts], help="scan + отчёт + отписка одной командой"
    )
    p.set_defaults(func=cmd_clean)

    p = sub.add_parser("whitelist", parents=[common], help="кого не трогать никогда")
    p.add_argument("--add", nargs="+", metavar="ЛОГИН", help="добавить логины в белый список")
    p.set_defaults(func=cmd_whitelist)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        log("\nПрервано. Прогресс сохранён — просто запусти команду ещё раз.")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
