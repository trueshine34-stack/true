"""Проверка всей цепочки на поддельном клиенте — без обращений к Instagram."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def user(pk: str, username: str) -> SimpleNamespace:
    return SimpleNamespace(
        pk=pk, username=username, full_name=username.title(), is_private=False, is_verified=False
    )


def media(pk: str, code: str, like_count: int) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"{pk}_777",
        pk=pk,
        code=code,
        taken_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
        like_count=like_count,
        media_type=1,
    )


class FakeClient:
    """Минимальный двойник instagrapi.Client: только то, что зовёт instaclean."""

    def __init__(self) -> None:
        self.user_id = "777"
        self.unfollowed: list[str] = []
        self._medias = [media("11", "aaa", 2), media("12", "bbb", 1)]
        # 13 — архивный пост: его лайкнул только carol
        self._archive = [
            {"id": "13_777", "pk": "13", "code": "ccc", "taken_at": 1767225600,
             "like_count": 1, "media_type": 1}
        ]
        self._likers = {
            "11_777": [user("1", "alice"), user("2", "bob")],
            "12_777": [user("1", "alice")],
            "13_777": [user("3", "carol")],
        }
        self._comments = {"11_777": [SimpleNamespace(user=user("4", "dave"))]}
        self._following = {
            "1": user("1", "alice"),
            "2": user("2", "bob"),
            "3": user("3", "carol"),
            "4": user("4", "dave"),
            "5": user("5", "erin"),
            "6": user("6", "frank"),
        }

    def user_medias(self, user_id, amount=0):
        return list(self._medias)

    def private_request(self, endpoint, params=None):
        assert endpoint == "feed/only_me_feed/"
        return {"items": list(self._archive), "more_available": False}

    def media_likers(self, media_id):
        return list(self._likers.get(media_id, []))

    def media_comments(self, media_id, amount=0):
        return list(self._comments.get(media_id, []))

    def user_following(self, user_id, amount=0):
        return dict(self._following)

    def user_unfollow(self, pk):
        self.unfollowed.append(str(pk))
        return True


class PipelineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["INSTACLEAN_HOME"] = self.tmp.name
        import importlib

        from instaclean import storage as storage_mod

        importlib.reload(storage_mod)
        self.storage_mod = storage_mod
        self.storage = storage_mod.Storage("me")
        self.cl = FakeClient()

    def tearDown(self) -> None:
        self.tmp.cleanup()
        os.environ.pop("INSTACLEAN_HOME", None)

    def _scan(self, with_comments: bool = False):
        from instaclean.collect import collect_engagement, collect_following, collect_medias

        medias = collect_medias(self.cl, self.storage)
        collect_engagement(
            self.cl, self.storage, medias, with_comments=with_comments, min_delay=0, max_delay=0
        )
        collect_following(self.cl, self.storage)
        return medias

    def test_archive_is_included(self) -> None:
        medias = self._scan()
        self.assertEqual(len(medias), 3)
        self.assertEqual(sum(1 for m in medias if m["archived"]), 1)

    def test_candidates_exclude_archive_likers(self) -> None:
        from instaclean.analyze import build_report

        self._scan()
        report = build_report(self.storage)
        names = [row["username"] for row in report["candidates"]]
        # alice и bob лайкали обычные посты, carol — только архивный
        self.assertNotIn("carol", names)
        self.assertEqual(names, ["dave", "erin", "frank"])
        self.assertEqual(report["engaged_from_archive"], 1)
        self.assertEqual(report["medias_scanned"], 3)
        self.assertEqual(report["medias_missing"], [])

    def test_comments_option_keeps_commenters(self) -> None:
        from instaclean.analyze import build_report

        self._scan(with_comments=True)
        report = build_report(self.storage, count_comments=True)
        names = [row["username"] for row in report["candidates"]]
        self.assertEqual(names, ["erin", "frank"])  # dave комментировал

    def test_whitelist_is_respected(self) -> None:
        from instaclean.analyze import build_report

        self._scan()
        self.storage.add_to_whitelist(["Erin", "@frank"])
        report = build_report(self.storage)
        self.assertEqual([row["username"] for row in report["candidates"]], ["dave"])

    def test_dry_run_changes_nothing(self) -> None:
        from instaclean.analyze import build_report
        from instaclean.unfollow import run_unfollow

        self._scan()
        report = build_report(self.storage)
        run_unfollow(None, self.storage, report["candidates"], 0, 0, 0, execute=False)
        self.assertEqual(self.cl.unfollowed, [])
        self.assertFalse(self.storage.unfollowed_file.exists())

    def test_execute_respects_limit_and_resumes(self) -> None:
        from instaclean.analyze import build_report
        from instaclean.unfollow import run_unfollow

        self._scan()
        report = build_report(self.storage)

        first = run_unfollow(self.cl, self.storage, report["candidates"], 2, 0, 0, execute=True)
        self.assertEqual(first["done"], 2)
        self.assertEqual(self.cl.unfollowed, ["4", "5"])

        second = run_unfollow(self.cl, self.storage, report["candidates"], 0, 0, 0, execute=True)
        self.assertEqual(second["done"], 1)
        self.assertEqual(self.cl.unfollowed, ["4", "5", "6"])  # повторов нет

        third = run_unfollow(self.cl, self.storage, report["candidates"], 0, 0, 0, execute=True)
        self.assertEqual(third["done"], 0)

    def test_scan_resumes_from_cache(self) -> None:
        from instaclean.collect import collect_engagement

        medias = self._scan()
        calls = []
        original = self.cl.media_likers
        self.cl.media_likers = lambda mid: calls.append(mid) or original(mid)
        collect_engagement(self.cl, self.storage, medias, min_delay=0, max_delay=0)
        self.assertEqual(calls, [])  # всё уже в кеше, сеть не трогаем

    def test_capped_media_is_flagged(self) -> None:
        from instaclean.analyze import build_report

        self.cl._medias[0].like_count = 5000  # видно меньше лайкнувших, чем заявлено
        self._scan()
        report = build_report(self.storage)
        self.assertEqual(report["medias_capped"], ["11_777"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
