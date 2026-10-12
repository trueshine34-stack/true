# -*- coding: utf-8 -*-
"""
Teste pentru tools/build_lockers.py: conectorii Sameday (easybox) și Packeta pe un server HTTP local care
răspunde ca API-urile reale (formatul din SDK-ul oficial Sameday și din documentația Packeta v5),
plus funcțiile de normalizare a adreselor.

  python3 -m unittest discover -s tools/tests -v
"""
import json
import os
import sys
import tempfile
import threading
import types
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import build_lockers as B  # noqa: E402

SCHEDULE = [{"day": d, "openingHour": "00:00", "closingHour": "23:59"} for d in range(1, 8)]
SAMEDAY_PAGES = {
    1: {"data": [
        {"lockerId": 101, "name": "easybox Mega Image Dorobanți", "county": "Bucuresti", "city": "Bucuresti", "address": "Calea Dorobanților 142",
         "postalCode": "010581", "lat": 44.4598, "lng": 26.0956, "phone": "", "email": "", "supportedPayment": 1, "clientVisible": 1,
         "availableBoxes": [{"size": "S", "number": 10}], "schedule": SCHEDULE},
        {"lockerId": 102, "name": "Kaufland Mărăști", "county": "Cluj", "city": "Cluj-Napoca", "address": "Str. Fabricii 1",
         "postalCode": "400632", "lat": 46.778, "lng": 23.613, "clientVisible": 0, "availableBoxes": [], "schedule": SCHEDULE},
    ], "total": 3, "currentPage": 1, "pages": 2, "perPage": 2},
    2: {"data": [
        {"lockerId": 103, "name": "easybox Profi Iris", "county": "Cluj", "city": "Cluj-Napoca", "address": "Str. Oașului 92",
         "postalCode": "400647", "lat": 46.79, "lng": 23.62, "clientVisible": True, "availableBoxes": [],
         "schedule": [{"day": d, "openingHour": "07:00", "closingHour": "22:00"} for d in range(1, 7)]},
    ], "total": 3, "currentPage": 2, "pages": 2, "perPage": 2},
}
PACKETA_BRANCH = {"data": {
    "11": {"id": "11", "name": "Packeta Cluj Centru", "place": "Papetărie Unirii", "street": "Piața Unirii 5", "city": "Cluj-Napoca",
           "zip": "400 098", "country": "ro", "latitude": "46.7695", "longitude": "23.5899",
           "openingHours": {"regular": {"monday": "09:00–18:00", "tuesday": "09:00–18:00", "wednesday": "09:00–18:00",
                                        "thursday": "09:00–18:00", "friday": "09:00–18:00", "saturday": "10:00–14:00", "sunday": ""}},
           "directions": "Lângă intrarea în pasaj"},
    "12": {"id": "12", "name": "Packeta Praha", "street": "Václavské nám. 1", "city": "Praha", "zip": "110 00", "country": "cz",
           "latitude": "50.08", "longitude": "14.42"},
}}
PACKETA_BOX = {"data": [
    {"id": "901", "name": "Z-BOX Iulius Mall", "place": "Z-BOX Iulius Mall", "street": "Str. Alexandru Vaida Voevod 53B", "city": "Cluj-Napoca",
     "zip": "400436", "country": "ro", "latitude": 46.7712, "longitude": 23.6266},
]}


class Mock(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):  # liniște în teste
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        Mock.seen.append(("POST", self.path, {k.lower(): v for k, v in self.headers.items()}))
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode()
        if self.path == "/api/authenticate":
            ok = self.headers.get("X-AUTH-USERNAME") == "demo" and self.headers.get("X-AUTH-PASSWORD") == "secret" and "remember_me" in body
            return self._send(200, {"token": "tok-123", "expire_at": "2026-12-31 23:59"}) if ok else self._send(401, {"error": "bad"})
        self._send(404, {})

    def do_GET(self):
        Mock.seen.append(("GET", self.path, {k.lower(): v for k, v in self.headers.items()}))
        u = urlparse(self.path)
        if u.path == "/api/client/lockers":
            if self.headers.get("X-AUTH-TOKEN") != "tok-123":
                return self._send(401, {"error": "token"})
            page = int(parse_qs(u.query).get("page", ["1"])[0])
            return self._send(200, SAMEDAY_PAGES.get(page, {"data": [], "pages": 2}))
        if u.path == "/v5/KEY/branch.json":
            return self._send(200, PACKETA_BRANCH)
        if u.path == "/v5/KEY/box.json":
            return self._send(200, PACKETA_BOX)
        self._send(404, {})


class ConnectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = HTTPServer(("127.0.0.1", 0), Mock)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{cls.srv.server_port}"
        cls.urls = dict(B.URLS)
        B.URLS.update({"sameday_auth": f"{base}/api/authenticate", "sameday_lockers": f"{base}/api/client/lockers",
                       "packeta_branch": f"{base}/v5/{{key}}/branch.json?lang=ro", "packeta_box": f"{base}/v5/{{key}}/box.json?lang=ro"})
        cls.proxy = {k: os.environ.pop(k) for k in ("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy") if k in os.environ}

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        B.URLS.clear()
        B.URLS.update(cls.urls)
        os.environ.update(cls.proxy)

    def test_sameday_auth_pagination_visibility(self):
        rows = B.easybox_api("demo", "secret")
        self.assertEqual([r["id"] for r in rows], ["easybox-101", "easybox-103"], "pagina 2 citită, lockerul ascuns sărit")
        first = rows[0]
        self.assertEqual(first["provider"], "easybox")
        self.assertEqual(first["kind"], "locker")
        self.assertEqual(first["hours"], "Non-stop")
        self.assertEqual((first["lat"], first["lng"]), (44.4598, 26.0956))
        self.assertEqual(first["postcode"], "010581")
        self.assertEqual(rows[1]["hours"], "L–S 07:00–22:00, D închis")
        auth = next(h for m, p, h in Mock.seen if p == "/api/authenticate")
        self.assertEqual(auth.get("x-auth-username"), "demo")
        pages = [p for m, p, h in Mock.seen if p.startswith("/api/client/lockers")]
        self.assertTrue(any("page=2" in p for p in pages) and all("countPerPage=" in p for p in pages))

    def test_sameday_bad_credentials(self):
        with self.assertRaises(Exception):
            B.easybox_api("demo", "wrong")

    def test_packeta_points_and_zbox(self):
        os.environ["PACKETA_API_KEY"] = "KEY"
        try:
            with tempfile.TemporaryDirectory() as cache:
                rows = B.src_packeta(types.SimpleNamespace(cache=cache, offline=False))
        finally:
            os.environ.pop("PACKETA_API_KEY", None)
        by = {r["id"]: r for r in rows}
        self.assertEqual(set(by), {"packeta-11", "packeta-901"}, "doar România")
        self.assertEqual(by["packeta-11"]["kind"], "point")
        self.assertEqual(by["packeta-11"]["postcode"], "400098")
        self.assertEqual(by["packeta-11"]["hours"], "L–V 09:00–18:00, S 10:00–14:00, D închis")
        self.assertEqual(by["packeta-11"]["note"], "Lângă intrarea în pasaj")
        self.assertEqual(by["packeta-901"]["kind"], "locker")
        self.assertEqual(by["packeta-901"]["hours"], "Non-stop")
        self.assertTrue(by["packeta-901"]["name"].startswith("Packeta Z-BOX"))

    def test_packeta_without_key_is_skipped(self):
        os.environ.pop("PACKETA_API_KEY", None)
        self.assertEqual(B.src_packeta(types.SimpleNamespace(cache="/nonexistent", offline=False)), [])


class NormalizeTests(unittest.TestCase):
    def test_tidy(self):
        self.assertEqual(B.tidy("PUNCT AMARADIA 26"), "Punct Amaradia 26")
        self.assertEqual(B.tidy("Punct Carrefour SUPER IPOTESTI"), "Punct Carrefour Super Ipotesti")
        self.assertEqual(B.tidy("Locker OMV Pipera"), "Locker OMV Pipera")
        self.assertEqual(B.tidy("STR 1 MAI NR 22"), "Str. 1 Mai Nr. 22")

    def test_street(self):
        self.assertEqual(B.street("str.", "Barcelona", "FN"), "Str. Barcelona f.n.")
        self.assertEqual(B.street("bld.", "Timisoara", "51"), "Bd. Timisoara 51")
        self.assertEqual(B.street("Strada", "Strada Morii", "3"), "Strada Morii 3")

    def test_hours(self):
        nonstop = [("00:00", "23:59")] * 7
        self.assertEqual(B.hours_from_days(nonstop), "Non-stop")
        week = [("08:00", "20:00")] * 5 + [("09:00", "14:00"), None]
        self.assertEqual(B.hours_from_days(week), "L–V 08:00–20:00, S 09:00–14:00, D închis")
        self.assertEqual(B.hours_from_days([None] * 7), "")

    def test_clean_city_and_legal(self):
        self.assertEqual(B.clean_city("Fundeni (Calarasi)", "Călărași"), ["Fundeni"])
        self.assertEqual(B.clean_city("Berceni-B", "Ilfov"), ["Berceni"])
        self.assertEqual(B.clean_city("Dumbraveni Ilfov", "Ilfov"), ["Dumbraveni Ilfov", "Dumbraveni"])
        self.assertEqual(B.clean_city("Targu Mures", "Mureș")[0], "Targu Mures", "numele întreg se încearcă primul")
        self.assertEqual(B.strip_legal("Riemalmi 2021 SRL"), "Riemalmi 2021")
        self.assertEqual(B.strip_legal("Andrei Florentina IF"), "Andrei Florentina")

    def test_postcode_county(self):
        self.assertEqual(B.POSTCODE_COUNTY["51"], "Alba")      # Blaj 515400
        self.assertEqual(B.POSTCODE_COUNTY["14"], "Teleorman")  # Slobozia Mândra 147355
        self.assertEqual(B.POSTCODE_COUNTY["04"], "București")  # sectorul 4


if __name__ == "__main__":
    unittest.main()
