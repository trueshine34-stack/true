# -*- coding: utf-8 -*-
"""
Construiește data/lockers.json din exportul OpenStreetMap (Nominatim, amenity=parcel_locker).

Datele de locații sunt © OpenStreetMap contributors (ODbL 1.0). În producție lista se
sincronizează din API-urile curierilor (vezi README → „Lockere”), cu același format.
"""
import json, sys, re

SRC, OUT = sys.argv[1], sys.argv[2]
COUNTY = {"RO-B": "București", "RO-CJ": "Cluj", "RO-BV": "Brașov", "RO-TM": "Timiș", "RO-IS": "Iași", "RO-CT": "Constanța",
          "RO-DJ": "Dolj", "RO-GL": "Galați", "RO-PH": "Prahova", "RO-BH": "Bihor", "RO-BR": "Brăila", "RO-AR": "Arad",
          "RO-AG": "Argeș", "RO-SB": "Sibiu", "RO-BC": "Bacău", "RO-MS": "Mureș", "RO-MM": "Maramureș", "RO-BZ": "Buzău",
          "RO-SV": "Suceava", "RO-SM": "Satu Mare", "RO-BT": "Botoșani", "RO-VL": "Vâlcea", "RO-NT": "Neamț", "RO-AB": "Alba",
          "RO-HD": "Hunedoara", "RO-VN": "Vrancea", "RO-DB": "Dâmbovița", "RO-BN": "Bistrița-Năsăud", "RO-IF": "Ilfov",
          "RO-CV": "Covasna", "RO-HR": "Harghita", "RO-SJ": "Sălaj", "RO-GJ": "Gorj", "RO-MH": "Mehedinți", "RO-OT": "Olt",
          "RO-TR": "Teleorman", "RO-GR": "Giurgiu", "RO-CL": "Călărași", "RO-IL": "Ialomița", "RO-TL": "Tulcea", "RO-VS": "Vaslui",
          "RO-CS": "Caraș-Severin"}
NAMES = {"easybox": "easybox", "fanbox": "FANbox", "cargus": "Ship & Go", "dpd": "DPD Box", "gls": "GLS ParcelLocker",
         "packeta": "Packeta Z-BOX"}


def provider(e):
    t = e.get("extratags") or {}
    s = " ".join([e.get("name") or "", t.get("brand", ""), t.get("operator", "")]).lower()
    wd = t.get("brand:wikidata", "") + t.get("operator:wikidata", "")
    if "easybox" in s or "sameday" in s or "emag" in s or "Q114496224" in wd: return "easybox"
    if "fanbox" in s or "fan courier" in s or "fan box" in s: return "fanbox"
    if "cargus" in s or ("ship" in s and "go" in s): return "cargus"
    if "dpd" in s: return "dpd"
    if "gls" in s or "Q366182" in wd: return "gls"
    if "packeta" in s or "z-box" in s or "zbox" in s: return "packeta"
    return None


def clean_hours(h):
    if not h: return "Non-stop"
    if h.strip() in ("24/7", "Mo-Su 00:00-24:00"): return "Non-stop"
    return (h.replace("Mo", "Lu").replace("Tu", "Ma").replace("We", "Mi").replace("Th", "Jo").replace("Fr", "Vi")
            .replace("Sa", "Sâ").replace("Su", "Du").replace("PH", "Sărb.").replace("off", "închis"))


rows, seen = [], set()
drop = {"provider": 0, "city": 0, "dup": 0}
for e in json.load(open(SRC, encoding="utf-8")):
    p = provider(e)
    if not p:
        drop["provider"] += 1; continue
    a = e.get("address") or {}
    city = a.get("city") or a.get("town") or a.get("village") or a.get("municipality")
    if not city:
        drop["city"] += 1; continue
    key = (p, round(float(e["lat"]), 5), round(float(e["lon"]), 5))
    if key in seen:
        drop["dup"] += 1; continue
    seen.add(key)
    street = a.get("road") or a.get("pedestrian") or a.get("square") or ""
    num = a.get("house_number")
    line = " ".join(x for x in [street, num] if x)
    addr = ", ".join(x for x in [line, a.get("suburb") or a.get("city_district") or ""] if x)
    raw = (e.get("name") or "").strip()
    label = re.sub(r"(?i)^(emag\s+)?(easybox|fanbox|fan box|gls parcel ?locker|dpd( box| pickup)?|ship ?& ?go|packeta( z-box)?)\s*[-–]?\s*", "", raw).strip()
    if not label or label.lower() in ("easybox", "fanbox", "locker"):
        label = street or a.get("suburb") or city
    rows.append({
        "id": f"{p}-{e['osm_type'][0]}{e['osm_id']}", "provider": p,
        "name": f"{NAMES[p]} {label}".strip(), "address": addr or label, "city": city,
        "county": COUNTY.get(a.get("ISO3166-2-lvl4", ""), a.get("county", "") or a.get("state", "")),
        "postcode": a.get("postcode", ""), "lat": round(float(e["lat"]), 6), "lng": round(float(e["lon"]), 6),
        "hours": clean_hours((e.get("extratags") or {}).get("opening_hours")),
    })
rows.sort(key=lambda r: (r["city"], r["provider"], r["name"]))
json.dump({"source": "OpenStreetMap contributors (ODbL 1.0) — amenity=parcel_locker", "count": len(rows), "lockers": rows},
          open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
from collections import Counter
print(drop); print(len(rows), Counter(r["provider"] for r in rows).most_common())
print(rows[0]); print(rows[len(rows)//2])
