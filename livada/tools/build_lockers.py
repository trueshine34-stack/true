# -*- coding: utf-8 -*-
"""
Construiește data/lockers.json (lockere și puncte de ridicare) și data/places.json (localitățile României)
din listele publicate de curieri, plus un raport de calitate a adreselor.

  python3 tools/build_lockers.py [--cache DIR] [--offline]

Surse (fiecare curier publică lista pe harta lui oficială; descărcările rămân în cache):
  fanbox   FAN Courier   https://www.fancourier.ro/wp-content/uploads/pickup-points-fanbox.json
  gls      GLS România   https://map.gls-romania.com/data/deliveryPoints/ro.json         (lockere + ParcelShop)
  cargus   Cargus        https://www.cargus.ro/wp-content/plugins/find-ship-go-cargus/data.json   (lockere + Ship & Go)
  dpd      DPD România   https://services.dpd.ro/officesmap_v2/  (harta oficiilor: lockere + DPD Pickup)
  easybox  Sameday       API-ul Sameday cu contul magazinului (SAMEDAY_USER, SAMEDAY_PASSWORD).
                         Sameday nu publică lista deschis; fără cont folosim easybox-urile din OpenStreetMap
                         (tools/data/osm_easybox.json, © OpenStreetMap contributors, ODbL) — o parte din rețea.
  packeta  Packeta       feed-ul de puncte cu cheia API a magazinului (PACKETA_API_KEY); fără cheie, omis.

Localitățile (diacritice, județ, coordonate) vin din tools/data/localitati.json (domeniu public, pe datele INS).
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import hashlib
import json
import math
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "tools", "data")
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

URLS = {
    "fanbox": "https://www.fancourier.ro/wp-content/uploads/pickup-points-fanbox.json",
    "gls": "https://map.gls-romania.com/data/deliveryPoints/ro.json",
    "cargus": "https://www.cargus.ro/wp-content/plugins/find-ship-go-cargus/data.json",
    "dpd_map": "https://services.dpd.ro/officesmap_v2/googleMaps.php?office_locator&lang=ro&src=olsl_2&dropOff=1&pickUp=1&officeType=ALL",
    "dpd_office": "https://services.dpd.ro/officesmap_v2/getOfficeDetails.php",
    "sameday_auth": "https://api.sameday.ro/api/authenticate",
    "sameday_lockers": "https://api.sameday.ro/api/client/lockers",
    "packeta": "https://www.zasilkovna.cz/api/v4/{key}/branch.json?lang=ro",
}

COUNTIES = ["Alba", "Arad", "Argeș", "Bacău", "Bihor", "Bistrița-Năsăud", "Botoșani", "Brașov", "Brăila", "București", "Buzău",
            "Caraș-Severin", "Călărași", "Cluj", "Constanța", "Covasna", "Dâmbovița", "Dolj", "Galați", "Giurgiu", "Gorj", "Harghita",
            "Hunedoara", "Ialomița", "Iași", "Ilfov", "Maramureș", "Mehedinți", "Mureș", "Neamț", "Olt", "Prahova", "Satu Mare", "Sălaj",
            "Sibiu", "Suceava", "Teleorman", "Timiș", "Tulcea", "Vaslui", "Vâlcea", "Vrancea"]
CODES = {"AB": "Alba", "AR": "Arad", "AG": "Argeș", "BC": "Bacău", "BH": "Bihor", "BN": "Bistrița-Năsăud", "BT": "Botoșani",
         "BV": "Brașov", "BR": "Brăila", "B": "București", "BZ": "Buzău", "CS": "Caraș-Severin", "CL": "Călărași", "CJ": "Cluj",
         "CT": "Constanța", "CV": "Covasna", "DB": "Dâmbovița", "DJ": "Dolj", "GL": "Galați", "GR": "Giurgiu", "GJ": "Gorj",
         "HR": "Harghita", "HD": "Hunedoara", "IL": "Ialomița", "IS": "Iași", "IF": "Ilfov", "MM": "Maramureș", "MH": "Mehedinți",
         "MS": "Mureș", "NT": "Neamț", "OT": "Olt", "PH": "Prahova", "SM": "Satu Mare", "SJ": "Sălaj", "SB": "Sibiu", "SV": "Suceava",
         "TR": "Teleorman", "TM": "Timiș", "TL": "Tulcea", "VS": "Vaslui", "VL": "Vâlcea", "VN": "Vrancea"}
# primele două cifre ale codului poștal → județul (01–06 sunt sectoarele Bucureștiului)
POSTCODE_COUNTY = {"01": "București", "02": "București", "03": "București", "04": "București", "05": "București", "06": "București",
                   "07": "Ilfov", "08": "Giurgiu", "10": "Prahova", "11": "Argeș", "12": "Buzău", "13": "Dâmbovița", "14": "Teleorman",
                   "20": "Dolj", "21": "Gorj", "22": "Mehedinți", "23": "Olt", "24": "Vâlcea", "30": "Timiș", "31": "Arad",
                   "32": "Caraș-Severin", "33": "Hunedoara", "40": "Cluj", "41": "Bihor", "42": "Bistrița-Năsăud", "43": "Maramureș",
                   "44": "Satu Mare", "45": "Sălaj", "50": "Brașov", "51": "Alba", "52": "Covasna", "53": "Harghita", "54": "Mureș",
                   "55": "Sibiu", "60": "Bacău", "61": "Neamț", "62": "Vrancea", "70": "Iași", "71": "Botoșani", "72": "Suceava",
                   "73": "Vaslui", "80": "Galați", "81": "Brăila", "82": "Tulcea", "90": "Constanța", "91": "Călărași", "92": "Ialomița"}
NAMES = {"easybox": "easybox", "fanbox": "FANbox", "cargus": "Cargus", "dpd": "DPD", "gls": "GLS", "packeta": "Packeta"}
DAYS = ["L", "Ma", "Mi", "J", "V", "S", "D"]


def fold(s):
    s = unicodedata.normalize("NFD", str(s or "")).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[\s\-_.]+", " ", s).strip()


COUNTY_BY_FOLD = {fold(c): c for c in COUNTIES}
COUNTY_BY_FOLD.update({"bucuresti": "București", "municipiul bucuresti": "București", "caras severin": "Caraș-Severin",
                       "bistrita nasaud": "Bistrița-Năsăud"})


def county_name(s):
    s = fold(s).replace("judetul ", "")
    return COUNTY_BY_FOLD.get(s, "")


# ─── localități ──────────────────────────────────────────────────────────────
class Places:
    def __init__(self, path):
        d = json.load(open(path, encoding="utf-8"))
        self.rows = []
        self.by_name = defaultdict(list)
        for name, county, lat, lng, pop, zip_ in d["rows"]:
            c = county_name(county) or county
            if name == "București":
                pop = 1716961
            base = re.sub(r"\s*\(.*?\)\s*", " ", name).strip()
            r = {"name": base, "full": name, "county": c, "lat": lat, "lng": lng, "pop": pop}
            self.rows.append(r)
            self.by_name[fold(base)].append(r)
        # grilă de 0,1° pentru „cea mai apropiată localitate”
        self.grid = defaultdict(list)
        for r in self.rows:
            self.grid[(int(r["lat"] * 10), int(r["lng"] * 10))].append(r)

    def nearest(self, lat, lng, county=None):
        best, bd = None, 1e9
        cy, cx = int(lat * 10), int(lng * 10)
        for ring in range(0, 6):
            for gy in range(cy - ring, cy + ring + 1):
                for gx in range(cx - ring, cx + ring + 1):
                    if max(abs(gy - cy), abs(gx - cx)) != ring:
                        continue
                    for r in self.grid.get((gy, gx), ()):
                        if county and r["county"] != county:
                            continue
                        d = km(lat, lng, r["lat"], r["lng"])
                        if d < bd:
                            best, bd = r, d
            if best and bd < ring * 8:
                break
        return best, bd

    def resolve(self, name, county, lat, lng):
        """→ (localitate, distanța în km față de centrul ei); potrivire după nume (+ județ), apoi după coordonate"""
        cands = self.by_name.get(fold(name), [])
        if county:
            in_county = [r for r in cands if r["county"] == county]
            cands = in_county or cands
        if cands:
            r = min(cands, key=lambda r: km(lat, lng, r["lat"], r["lng"]))
            return r, km(lat, lng, r["lat"], r["lng"])
        return None, None


def km(a, b, c, d):
    p = math.pi / 180
    h = math.sin((c - a) * p / 2) ** 2 + math.cos(a * p) * math.cos(c * p) * math.sin((d - b) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


# ─── text ────────────────────────────────────────────────────────────────────
KEEP_UPPER = {"OMV", "MOL", "DPD", "GLS", "FAN", "BCR", "BRD", "ING", "CEC", "UP", "SC", "KFC", "DM", "XXL", "UMF", "ASE", "II", "III",
              "IV", "VI", "VII", "VIII", "IX", "XI", "XII", "LTD", "PFA", "SRL", "NR", "DN", "DJ", "DC", "FN", "ANL", "ISU", "BMW", "IKEA", "LIDL",
              "AFI", "ARO", "ARS", "ELIS", "PECO", "GPL", "UPC", "RCS", "RDS", "PRO", "EUROPA", "CFR", "IMM"}
NOT_UPPER = {"LIDL", "IKEA", "EUROPA", "PRO", "ELIS", "PECO", "NR"}
SMALL = {"de", "la", "din", "cu", "si", "și", "pe", "in", "în", "a", "al", "ale", "lui", "sub", "langa", "lângă", "pentru"}


def tidy(s):
    """normalizează spațiile și transformă cuvintele SCRISE CU MAJUSCULE în Titlu (păstrând sigle și numere)"""
    s = re.sub(r"\s+", " ", str(s or "")).strip(" ,;-")
    letters = [c for c in s if c.isalpha()]
    all_caps = letters and sum(c.isupper() for c in letters) > 0.75 * len(letters)
    out = []
    for i, tok in enumerate(re.split(r"(\s+|/|-|\(|\)|,)", s)):
        core = tok.strip(".")
        if not core or not any(c.isalpha() for c in core) or not core.isupper():
            out.append(tok)
            continue
        if any(c.isdigit() for c in core) or (core in KEEP_UPPER and core not in NOT_UPPER):
            out.append(tok)
            continue
        if len(core) <= 3 and not all_caps:
            out.append(tok)  # sigle în texte normale (ex. „Punct OMV”)
            continue
        low = tok.lower()
        out.append(low if i > 0 and low in SMALL else low[:1].upper() + low[1:])
    s = "".join(out)
    s = re.sub(r"\b(Nr|Str|Bd|Bdul|Sos|Cal|Al|Bl|Sc|Ap|Et)\b\.?", lambda m: m.group(1) + ".", s)
    s = re.sub(r"\bSos\.", "Șos.", s)
    return re.sub(r"\.\.+", ".", s).replace(" ,", ",")


LEGAL = re.compile(r"(?i)[\s,.-]+(s\.?\s?r\.?\s?l\.?(-?d)?|s\.?\s?a\.?|s\.?n\.?c\.?|s\.?c\.?s\.?|p\.?f\.?a\.?|i\.?\s?i\.?|i\.?\s?f\.?|i\.?\s?i\.?\s?f\.?)(?=\s*(\d+\s*)?$)")


def strip_legal(s):
    """„Riemalmi 2021 SRL” → „Riemalmi 2021”, „Andrei Florentina IF” → „Andrei Florentina”"""
    s = re.sub(r"(?i)^s\.?c\.?\s+", "", (s or "").strip())
    return LEGAL.sub("", s).strip(" ,.-")


STREET_TYPES = {"str": "Str.", "strada": "Str.", "bld": "Bd.", "bd": "Bd.", "bdul": "Bd.", "bulevardul": "Bd.", "sos": "Șos.",
                "soseaua": "Șos.", "cal": "Calea", "calea": "Calea", "al": "Aleea", "aleea": "Aleea", "pta": "Piața", "piata": "Piața",
                "spl": "Splaiul", "splaiul": "Splaiul", "intr": "Intrarea", "intrarea": "Intrarea", "drum": "Drumul", "drumul": "Drumul"}


def street(typ, name, no):
    typ = STREET_TYPES.get(fold(typ).strip(" ."), tidy(typ or "")) if typ else ""
    name, no = tidy(name or ""), str(no or "").strip()
    if no.upper() in ("FN", "F.N.", "F N", "0", "-"):
        no = "f.n."
    if not name:
        return ""
    if typ and fold(name).startswith(fold(typ).rstrip(".")):
        typ = ""
    return " ".join(x for x in [typ, name, no] if x)


def hours_from_days(days):
    """days: 7 elemente (L…D) cu (de la, până la) sau None → „Non-stop” / „L–V 08:00–20:00, S 09:00–14:00”"""
    norm = []
    for d in days:
        if not d or not d[0] or not d[1] or d[0] == d[1]:
            norm.append(None)
            continue
        a, b = d[0][:5], d[1][:5]
        if b in ("23:59", "24:00") and a == "00:00":
            norm.append("00:00–24:00")
        else:
            norm.append(f"{a}–{'24:00' if b == '23:59' else b}")
    if all(x == "00:00–24:00" for x in norm):
        return "Non-stop"
    if not any(norm):
        return ""
    parts, i = [], 0
    while i < 7:
        j = i
        while j + 1 < 7 and norm[j + 1] == norm[i]:
            j += 1
        label = DAYS[i] if i == j else f"{DAYS[i]}–{DAYS[j]}"
        val = "închis" if norm[i] is None else ("non-stop" if norm[i] == "00:00–24:00" else norm[i])
        parts.append(f"{label} {val}")
        i = j + 1
    # „D închis” la final nu aduce informație utilă când restul e clar
    return ", ".join(parts)


def osm_hours(h):
    h = (h or "").strip()
    if not h or h in ("24/7", "Mo-Su 00:00-24:00", "Mo-Su 00:00-23:59"):
        return "Non-stop"
    for a, b in (("Mo", "L"), ("Tu", "Ma"), ("We", "Mi"), ("Th", "J"), ("Fr", "V"), ("Sa", "S"), ("Su", "D"), ("PH", "Sărb."), ("off", "închis")):
        h = h.replace(a, b)
    return h.replace("-", "–").replace(";", ",")


def note_text(s, limit=140):
    s = re.sub(r"\s+", " ", str(s or "")).strip(" -.")
    m = re.search(r"punct de reper:\s*([^.\n]+)", s, re.I)
    if m:
        s = m.group(1).strip(" -.")
    if len(s) > limit or "Ship & Go!" in s or "Ship&Go!" in s or len(s) < 3:
        return ""
    return tidy(s[:1].upper() + s[1:])


# ─── descărcare ──────────────────────────────────────────────────────────────
def fetch(url, cache, name, offline=False, data=None, headers=None):
    path = os.path.join(cache, name)
    if os.path.exists(path):
        return open(path, encoding="utf-8-sig").read()
    if offline:
        raise SystemExit(f"lipsește din cache: {path}")
    h = {"User-Agent": UA, "Accept-Language": "ro-RO,ro;q=0.9"}
    h.update(headers or {})
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, data=data, headers=h, method="POST" if data is not None else "GET")
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read().decode("utf-8-sig")
            os.makedirs(cache, exist_ok=True)
            open(path, "w", encoding="utf-8").write(body)
            return body
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(2 * (attempt + 1))
    raise SystemExit(f"eșec la {url}: {err}")


# ─── surse ───────────────────────────────────────────────────────────────────
def src_fanbox(a):
    rows = json.loads(fetch(URLS["fanbox"], a.cache, "fan-fanbox.json", a.offline))
    out = []
    for x in rows:
        ad = x.get("address") or {}
        sch = x.get("schedule") or []
        out.append(dict(id=f"fanbox-{x['id']}", provider="fanbox", kind="locker",
                        name=tidy(re.sub(r"\s+[A-Z]{1,2}$", "", (x.get("name") or "").strip())), address=street("", ad.get("street"), ad.get("streetNo")),
                        city=ad.get("locality") or "", county=county_name(ad.get("county")), postcode=str(ad.get("zipCode") or ""),
                        lat=float(x["latitude"]), lng=float(x["longitude"]),
                        hours=hours_from_days([(d.get("firstHour"), d.get("secondHour")) if d else None for d in sch[:7]] + [None] * (7 - len(sch))),
                        note=note_text(ad.get("reference") or x.get("description"))))
    return out


def src_gls(a):
    items = json.loads(fetch(URLS["gls"], a.cache, "gls-ro.json", a.offline))["items"]
    out = []
    for x in items:
        if x.get("id", "").endswith("PSTEST"):
            continue
        c = x.get("contact") or {}
        m = re.match(r"^(.*?)(?:\s+([A-Z]{1,2}))+$", (c.get("city") or "").strip())
        city, code = (m.group(1), m.group(2)) if m else (c.get("city") or "", "")
        sector = ""
        sm = re.match(r"(?i)^bucuresti\s+sector\s+(\d)", c.get("city") or "")
        if sm:
            city, code, sector = "București", "B", sm.group(1)
        days = [None] * 7
        for h in x.get("hours") or []:  # [zi, de la, până la] sau, cu pauză, [zi, de la, până la, de la, până la]
            if len(h) >= 3 and 1 <= h[0] <= 7:
                days[h[0] - 1] = (h[1], h[-1])
        locker = x.get("type") == "parcel-locker"
        raw = re.sub(r"^GLS\s+(Locker|ParcelLocker)\s*\d*\s*", "", x.get("name") or "", flags=re.I).strip()
        name = f"GLS Locker {tidy(raw)}" if locker else f"GLS ParcelShop {tidy(strip_legal(raw))}"
        addr = tidy(re.sub(r",?\s*\d{6}\s*[A-ZĂÂÎȘȚ ]*$", "", c.get("address") or ""))
        out.append(dict(id=f"gls-{x['id']}", provider="gls", kind="locker" if locker else "point", name=name.strip(), address=addr,
                        city=city, county=CODES.get(code, ""), postcode=str(c.get("postalCode") or ""), sector=sector,
                        lat=float(x["location"][0]), lng=float(x["location"][1]), hours=hours_from_days(days), note=""))
    return out


def src_cargus(a):
    rows = json.loads(fetch(URLS["cargus"], a.cache, "cargus-shipgo.json", a.offline))
    out = []
    for x in rows:
        locker = x.get("PointType") == 55
        days = [(x.get(f"OpenHours{d}Start"), x.get(f"OpenHours{d}End")) for d in ("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")]
        raw = re.sub(r"^(Locker|Punct)\s+", "", re.sub(r"\s+", " ", x.get("Name") or "").strip(), flags=re.I)
        parts = [p.strip() for p in re.sub(r",?\s*Cod postal\.?\s*\d*", "", x.get("Address") or "").split(",") if p.strip()]
        if len(parts) >= 2:
            sm = re.match(r"^(\S+)\s+(.*)$", parts[1])
            no = re.sub(r"(?i)^nr\.?\s*", "", parts[2]) if len(parts) > 2 else ""
            addr = street(sm.group(1), sm.group(2), no) if sm and fold(sm.group(1)) in STREET_TYPES else street("", parts[1], no)
            if len(parts) > 3:
                addr += ", " + tidy(", ".join(parts[3:]))
        else:
            addr = street("Str.", x.get("StreetName"), x.get("StreetNo"))
        raw = strip_legal(raw)
        out.append(dict(id=f"cargus-{x['Id']}", provider="cargus", kind="locker" if locker else "point",
                        name=f"{'Cargus Locker' if locker else 'Ship & Go'} {tidy(raw)}", address=addr,
                        city=x.get("City") or "", county=county_name(x.get("County")), postcode=str(x.get("PostalCode") or "").strip(),
                        sector=str(x.get("Sector") or "").strip(), lat=float(x["Latitude"]), lng=float(x["Longitude"]),
                        hours=hours_from_days(days), note=note_text(x.get("AddressDescription"))))
    return out


def src_dpd(a):
    page = fetch(URLS["dpd_map"], a.cache, "dpd-map.html", a.offline)
    ids = [int(m) for m in re.findall(r"oID:\s*(\d+)", page)]
    det_path = os.path.join(a.cache, "dpd-offices.json")
    det = json.load(open(det_path, encoding="utf-8")) if os.path.exists(det_path) else {}
    missing = [i for i in ids if str(i) not in det]
    if missing and not a.offline:
        print(f"  DPD: detalii pentru {len(missing)} oficii…", file=sys.stderr)

        def one(i):
            body = json.dumps({"officeId": i}).encode()
            for attempt in range(4):
                try:
                    req = urllib.request.Request(URLS["dpd_office"], data=body, method="POST", headers={
                        "User-Agent": UA, "Content-Type": "application/json", "Referer": URLS["dpd_map"]})
                    with urllib.request.urlopen(req, timeout=30) as r:
                        d = json.loads(r.read().decode("utf-8-sig"))
                    time.sleep(0.4)
                    return i, d.get("office")
                except Exception:  # noqa: BLE001
                    time.sleep(2 * (attempt + 1))
            return i, None
        with cf.ThreadPoolExecutor(3) as ex:
            for i, o in ex.map(one, missing):
                if o:
                    det[str(i)] = o
        json.dump(det, open(det_path, "w", encoding="utf-8"), ensure_ascii=False)
    out = []
    for i in ids:
        x = det.get(str(i))
        if not x or not x.get("pickUpAllowed"):
            continue
        ad = x.get("address") or {}
        locker = x.get("type") == "APT"
        days = [None] * 7
        for s in x.get("workingTimeSchedule") or []:
            wd = dt.date.fromisoformat(s["date"]).weekday()
            days[wd] = (s.get("workingTimeFrom"), s.get("workingTimeTo"))
        if not any(days):
            days = [(x.get("workingTimeFrom"), x.get("workingTimeTo"))] * 5 + [(x.get("workingTimeHalfFrom"), x.get("workingTimeHalfTo")),
                                                                             (x.get("workingTimeDayOffFrom"), x.get("workingTimeDayOffTo"))]
        raw = re.sub(r"\((OUTDOOR |INDOOR )?LOCKER\)|\(DPD SHOP\)|\(DPD PICKUP\)", "", x.get("name") or "", flags=re.I).strip()
        if " - " in raw:
            raw = raw.split(" - ", 1)[1]
        raw = re.sub(r"(?i)^paypoint\s+", "PayPoint ", raw)
        raw = strip_legal(raw)
        loc = ad.get("localAddressString") or ""
        m = re.match(r"^(\w+)\.?\s+(.*?)\s+Nr\s+(\S+)(.*)$", loc)
        addr = (street(m.group(1), m.group(2), m.group(3)) + tidy(m.group(4))) if m else street(ad.get("streetType"), ad.get("streetName"), ad.get("streetNo")) or tidy(loc)
        name = f"DPD Box {tidy(raw)}" if locker else (f"DPD Pickup {tidy(raw)}" if raw and fold(raw) != fold(ad.get('siteName')) else f"Oficiu DPD {tidy(ad.get('siteName') or raw)}")
        out.append(dict(id=f"dpd-{i}", provider="dpd", kind="locker" if locker else "point", name=name, address=addr,
                        city=ad.get("siteName") or "", county="", postcode=str(ad.get("postCode") or ""), lat=float(ad.get("y") or 0),
                        lng=float(ad.get("x") or 0), hours=hours_from_days(days), note=""))
    return out


def src_easybox(a, places):
    user, pwd = os.environ.get("SAMEDAY_USER"), os.environ.get("SAMEDAY_PASSWORD")
    if user and pwd and not a.offline:
        return easybox_api(user, pwd)
    d = json.load(open(os.path.join(DATA, "osm_easybox.json"), encoding="utf-8"))
    out = []
    for e in d["rows"]:
        ad = e.get("address") or {}
        road = ad.get("road") or ad.get("pedestrian") or ad.get("square") or ""
        line = " ".join(x for x in [road, ad.get("house_number")] if x)
        raw = re.sub(r"(?i)^(emag\s+)?(sameday\s+)?easy\s?box\s*[-–:]?\s*", "", e.get("name") or "").strip()
        raw = re.sub(r"(?i)\s*[-–]?\s*easy\s?box$", "", raw).strip()
        if not raw or fold(raw) in ("easybox", "locker", "sameday"):
            # Nominatim pune numele POI-ului („Easybox”) și în adresă: alegem primul reper care nu e chiar lockerul
            raw = next((v for v in (ad.get("shop"), ad.get("amenity"), road, ad.get("suburb"))
                        if v and not re.search(r"(?i)easy\s?box|sameday", v)), "")
        out.append(dict(id=f"easybox-osm-{e['osm']}", provider="easybox", kind="locker", name=f"easybox {tidy(raw)}".strip(),
                        address=", ".join(x for x in [line, ad.get("suburb") or ad.get("city_district") or ""] if x),
                        city=ad.get("city") or ad.get("town") or ad.get("village") or ad.get("municipality") or "",
                        county=CODES.get((ad.get("ISO3166-2-lvl4") or "").replace("RO-", ""), county_name(ad.get("county"))),
                        postcode=ad.get("postcode") or "", lat=e["lat"], lng=e["lng"], hours=osm_hours(e.get("hours")), note=""))
    return out


def easybox_api(user, pwd):
    """API-ul Sameday (cont de client): autentificare → lista paginată a lockerelor easybox."""
    req = urllib.request.Request(URLS["sameday_auth"], data=b"remember_me=1", method="POST", headers={
        "X-AUTH-USERNAME": user, "X-AUTH-PASSWORD": pwd, "Content-Type": "application/x-www-form-urlencoded", "User-Agent": UA})
    token = json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]
    out, page = [], 1
    while True:
        q = urllib.parse.urlencode({"page": page, "countPerPage": 500})
        req = urllib.request.Request(f"{URLS['sameday_lockers']}?{q}", headers={"X-AUTH-TOKEN": token, "User-Agent": UA})
        d = json.loads(urllib.request.urlopen(req, timeout=60).read())
        for x in d.get("data") or []:
            days = [None] * 7
            for s in x.get("schedule") or []:
                k = int(s.get("day") or 0)
                if 1 <= k <= 7:
                    days[k - 1] = (s.get("openingHour"), s.get("closingHour"))
            out.append(dict(id=f"easybox-{x.get('lockerId') or x.get('id')}", provider="easybox", kind="locker",
                            name=tidy(x.get("name") or ""), address=tidy(x.get("address") or ""), city=x.get("city") or "",
                            county=county_name(x.get("county")), postcode=str(x.get("postalCode") or x.get("postcode") or ""),
                            lat=float(x.get("lat") or x.get("latitude")), lng=float(x.get("lng") or x.get("long") or x.get("longitude")),
                            hours=hours_from_days(days) or "Non-stop", note=note_text(x.get("details") or "")))
        if page >= int(d.get("pages") or 1):
            break
        page += 1
    return out


def src_packeta(a):
    key = os.environ.get("PACKETA_API_KEY")
    if not key or a.offline:
        return []
    d = json.loads(fetch(URLS["packeta"].format(key=urllib.parse.quote(key)), a.cache, "packeta.json"))
    rows = d.get("data") or {}
    rows = rows.values() if isinstance(rows, dict) else rows
    out = []
    for x in rows:
        if (x.get("country") or "").lower() != "ro":
            continue
        locker = "z-box" in fold(x.get("name")) or x.get("type") == "zbox"
        out.append(dict(id=f"packeta-{x['id']}", provider="packeta", kind="locker" if locker else "point", name=tidy(x.get("name") or ""),
                        address=tidy(x.get("street") or ""), city=x.get("city") or x.get("place") or "", county="",
                        postcode=str(x.get("zip") or "").replace(" ", ""), lat=float(x["latitude"]), lng=float(x["longitude"]),
                        hours="Non-stop" if locker else "", note=note_text(x.get("directions") or "")))
    return out


# ─── normalizare + raport ───────────────────────────────────────────────────
def clean_city(raw, county):
    s = re.sub(r"\s+", " ", raw or "").strip()
    s = re.sub(r"(?i)^(mun\.?|municipiul|or\.?|oras(ul)?|orașul|com\.?|comuna|s\.|sat(ul)?)\s+", "", s)
    s = re.sub(r"(?i)\s+sector(ul)?\s*\d$", "", s)
    s = re.sub(r"\s*\([^)]*(\)|$)\s*", " ", s).strip()    # „Fundeni (Calarasi)”, și paranteza neînchisă din GLS
    s = re.sub(r"\s+[A-Z]{2}\d{6,}$", "", s)               # „Moscu GL550011141”
    s = re.sub(r"-[A-Z]{1,2}$", "", s)                      # „Berceni-B”
    out = [s]
    if county and fold(s).endswith(" " + fold(county)):     # „Dumbraveni Ilfov”, dar nu „Târgu Mureș”: încercăm întâi numele întreg
        out.append(s[: -len(county)].strip())
    return out



def normalize(rows, places, report):
    seen, out = {}, []
    for r in rows:
        net = r["provider"]
        rep = report[net]
        rep["sursă"] += 1
        if not (43.5 < r["lat"] < 48.4 and 20.2 < r["lng"] < 29.8):
            rep["coordonate în afara României (eliminate)"] += 1
            continue
        county = r.get("county") or ""
        variants = clean_city(r.get("city"), county)
        if fold(variants[0]) in ("bucuresti", "municipiul bucuresti"):
            variants, county = ["București"], "București"
        raw_city = variants[0]
        pc_county = POSTCODE_COUNTY.get((r.get("postcode") or "")[:2]) if re.fullmatch(r"\d{6}", r.get("postcode") or "") else None
        expected = pc_county or county
        loc, dist = None, None
        for v in variants:
            if v:
                loc, dist = places.resolve(v, expected or county, r["lat"], r["lng"])
                if loc:
                    break
        near, near_d = places.nearest(r["lat"], r["lng"])
        if not near or near_d > 15:  # în Republica Moldova, Ungaria, Bulgaria sau în mare: nicio localitate românească aproape
            rep["coordonate în afara României (eliminate)"] += 1
            rep["exemple: coordonate în alt județ"].append(f"{r['id']} {r['name']} — în afara României ({r['lat']:.4f}, {r['lng']:.4f})")
            continue
        if not loc or dist > 30:
            # localitatea declarată e departe de coordonate: cine greșește? județul punctului de pe hartă decide
            in_county = places.nearest(r["lat"], r["lng"], expected)[1] if expected else 0
            if expected and near and near["county"] != expected and in_county > 8 and not (expected == "București" and near["county"] == "Ilfov"):
                rep["eliminate: coordonate în alt județ decât adresa"] += 1
                rep["exemple: coordonate în alt județ"].append(f"{r['id']} {r['name']} — {raw_city}, {expected} ≠ hartă: {near['name']}, {near['county']}")
                continue
            if loc and loc["county"] == (expected or loc["county"]):
                rep["coordonate verificate prin codul poștal (localitate cu centru greșit în nomenclator)"] += 1
            else:
                loc, dist = near, near_d
                rep["localitate dedusă din coordonate"] += 1
                if raw_city:
                    rep["exemple: localitate necunoscută"].append(f"{raw_city} ({county or '?'}) → {near['name']}")
        r["city"], r["county"] = loc["name"], loc["county"]
        # adresa nu repetă localitatea / județul (le afișăm separat); fiecare segment e normalizat separat
        drop = {fold(r["city"]), fold(r["county"]), fold(raw_city), "romania", "ro", "jud " + fold(r["county"])}
        segs = [tidy(x) for x in re.split(r"\s*,\s*", r["address"] or "") if x.strip()]
        while len(segs) > 1 and fold(segs[-1]) in drop:
            segs.pop()
        r["address"] = ", ".join(segs)
        # sectorul Bucureștiului: din sursă sau din codul poștal (0S xxxx)
        sector = r.pop("sector", "") or ""
        if r["county"] == "București" and not sector and re.fullmatch(r"0[1-6]\d{4}", r["postcode"] or ""):
            sector = r["postcode"][1]
        if r["county"] == "București" and sector and "sector" not in fold(r["address"]):
            r["address"] = f"{r['address']}, Sector {sector}" if r["address"] else f"Sector {sector}"
        r["postcode"] = r["postcode"] if re.fullmatch(r"\d{6}", r["postcode"] or "") else ""
        if not r["postcode"]:
            rep["fără cod poștal"] += 1
        if not r["address"]:
            rep["fără stradă"] += 1
            r["address"] = r["name"].split(" ", 1)[-1]
        elif not re.search(r"\d|f\.n\.", r["address"]):
            rep["fără număr"] += 1
        if not r["hours"]:
            rep["fără program"] += 1
        key = (net, round(r["lat"], 4), round(r["lng"], 4), fold(r["name"]))
        if key in seen:
            rep["duplicate eliminate"] += 1
            continue
        seen[key] = 1
        r["lat"], r["lng"] = round(r["lat"], 6), round(r["lng"], 6)
        rep[r["kind"]] += 1
        out.append(r)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.environ.get("LOCKERS_CACHE", "/tmp/livada-lockers-cache"))
    ap.add_argument("--offline", action="store_true", help="doar din cache, fără cereri")
    ap.add_argument("--out", default=os.path.join(ROOT, "data", "lockers.json"))
    ap.add_argument("--places-out", default=os.path.join(ROOT, "data", "places.json"))
    a = ap.parse_args()

    places = Places(os.path.join(DATA, "localitati.json"))
    report = defaultdict(lambda: defaultdict(int))
    for k in NAMES:
        for ex in ("exemple: localitate necunoscută", "exemple: coordonate în alt județ"):
            report[k][ex] = []
    sources = {}
    rows = []
    for net, fn in (("fanbox", src_fanbox), ("gls", src_gls), ("cargus", src_cargus), ("dpd", src_dpd),
                    ("easybox", lambda a: src_easybox(a, places)), ("packeta", src_packeta)):
        got = fn(a)
        print(f"{net}: {len(got)}", file=sys.stderr)
        rows += got
    rows = normalize(rows, places, report)
    rows.sort(key=lambda r: (r["county"], r["city"], r["provider"], r["name"]))
    easy_osm = any(r["id"].startswith("easybox-osm-") for r in rows)
    for net in NAMES:
        mine = [r for r in rows if r["provider"] == net]
        if not mine:
            continue
        sources[net] = {
            "url": {"fanbox": URLS["fanbox"], "gls": URLS["gls"], "cargus": URLS["cargus"], "dpd": "https://services.dpd.ro/officesmap_v2/",
                    "easybox": "OpenStreetMap (ODbL)" if easy_osm else URLS["sameday_lockers"], "packeta": "Packeta API"}[net],
            "lockers": sum(r["kind"] == "locker" for r in mine), "points": sum(r["kind"] == "point" for r in mine),
            "counties": len({r["county"] for r in mine}), "cities": len({(r["county"], r["city"]) for r in mine}),
            "complete": not (net == "easybox" and easy_osm),
        }
    out = {"generated": dt.date.today().isoformat(), "sources": sources, "count": len(rows),
           "fields": ["id", "provider", "kind", "name", "address", "city", "county", "postcode", "lat", "lng", "hours", "note"],
           "lockers": rows}
    json.dump(out, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

    # localitățile pentru căutarea de pe hartă (inclusiv satele fără lockere)
    pl = [[r["name"], r["county"], r["lat"], r["lng"], r["pop"]] for r in places.rows]
    json.dump({"source": "INS / data.gov.ro, prin github.com/catalin87/baza-de-date-localitati-romania (domeniu public)",
               "fields": ["name", "county", "lat", "lng", "population"], "rows": pl},
              open(a.places_out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

    # raport
    rep = {}
    for net, d in report.items():
        if not d.get("sursă"):
            continue
        rep[net] = {k: (v[:12] if isinstance(v, list) else v) for k, v in d.items() if v}
        rep[net]["județe acoperite"] = sources.get(net, {}).get("counties", 0)
    missing_counties = {net: sorted(set(COUNTIES) - {r["county"] for r in rows if r["provider"] == net}) for net in sources}
    rep["județe fără acoperire"] = {k: v for k, v in missing_counties.items() if v}
    json.dump(rep, open(os.path.join(DATA, "lockers-report.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps({k: {kk: vv for kk, vv in v.items() if not kk.startswith("exemple")} if isinstance(v, dict) and k in NAMES else v
                      for k, v in rep.items()}, ensure_ascii=False, indent=1))
    print(f"total: {len(rows)} → {a.out} ({os.path.getsize(a.out) // 1024} KB); localități: {len(pl)}", file=sys.stderr)


if __name__ == "__main__":
    main()
