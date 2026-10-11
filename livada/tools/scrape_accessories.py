# -*- coding: utf-8 -*-
"""
Unealtă de dezvoltare: colectează catalogul complet de accesorii Apple și Beats din Apple Store Germania
(toate culorile, mărimile și dimensiunile de carcasă ale fiecărui produs), pentru catalogul Livada.

  python3 tools/scrape_accessories.py [--cache DIR] [--out tools/data/apple_accessories_de.json]

1. parcurge listele „Toate accesoriile” (paginate) și reține linkurile produselor Apple / Beats;
2. deschide pagina fiecărui produs și extrage harta variantelor: cod → {titlu, culoare, mărime, carcasă},
   prețul fiecărui cod, etichetele dimensiunilor și imaginea fiecărei culori (din mostra de culoare);
3. unește paginile care descriu aceeași familie (aceleași coduri) și scrie un JSON brut.

Paginile descărcate rămân în cache, deci rularea repetată nu mai face cereri.
"""
import argparse
import hashlib
import html as htmlmod
import json
import os
import re
import sys
import time
import urllib.request

BASE = "https://www.apple.com"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
LISTS = [
    # tot ce face Apple + categoriile mari
    "/de/shop/accessories/all/made-by-apple", "/de/shop/accessories/all/cases-protection",
    "/de/shop/accessories/all/chargers-adapters", "/de/shop/accessories/all/mice-keyboards",
    "/de/shop/accessories/all/headphones-speakers", "/de/shop/watch/bands",
    # pe dispozitiv
    *[f"/de/shop/iphone/accessories/{c}" for c in ["cases-protection", "chargers-adapters", "crossbody-straps", "magsafe", "headphones-speakers"]],
    *[f"/de/shop/ipad/accessories/{c}" for c in ["apple-pencil", "cases-protection", "chargers-adapters", "keyboards"]],
    *[f"/de/shop/mac/accessories/{c}" for c in ["chargers-adapters", "mice-keyboards", "cases-protection"]],
    *[f"/de/shop/watch/accessories/{c}" for c in ["chargers-adapters"]],
    *[f"/de/shop/airpods/accessories/{c}" for c in ["airpods", "airpods-essentials", "chargers-adapters"]],
    *[f"/de/shop/airtag/accessories/{c}" for c in ["airtag", "airtag-essentials"]],
    *[f"/de/shop/beats/accessories/{c}" for c in ["cables", "cases", "earbuds", "headphones", "speakers"]],
    "/de/shop/smart-home/accessories/apple-tv-essentials",
]


def fetch(url, cache):
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, hashlib.sha1(url.encode()).hexdigest()[:16] + ".html")
    if os.path.exists(path):
        return open(path, encoding="utf-8").read()
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "de-DE,de;q=0.9"})
            with urllib.request.urlopen(req, timeout=40) as r:
                body = r.read().decode("utf-8", "replace")
            open(path, "w", encoding="utf-8").write(body)
            time.sleep(0.4)
            return body
        except Exception as e:  # noqa: BLE001
            if getattr(e, "code", None) == 404:
                open(path, "w").write("")
                return ""
            time.sleep(2 ** attempt)
    print("eșec:", url, file=sys.stderr)
    return ""


def tiles(listing_html):
    """linkurile de produs din lista Apple (fără parametrii de culoare)"""
    out = []
    for m in re.finditer(r'"url":"(/de/shop/product/[a-z0-9]+/a/[^"?#]*)', listing_html):
        out.append(m.group(1))
    for m in re.finditer(r'href="(/de/shop/product/[a-z0-9]+/a/[^"?#]*)', listing_html):
        out.append(m.group(1))
    return out


def unescape_json_blob(s):
    return s.replace('\\"', '"').replace('\\\\', '\\')


def parse_pdp(page):
    """→ dict cu variante, prețuri, etichete de dimensiuni, imagini pe culoare"""
    raw = unescape_json_blob(page)
    variants = {}
    # harta variantelor e un șir JSON în JSON: "productVariationsPart":"{\"productVariations\":{\"MJUA4ZM/A\":{…}}}"
    i = page.find('"productVariationsPart":"')
    if i >= 0:
        j = i + len('"productVariationsPart":"')
        k = j
        while k < len(page) and page[k] != '"':
            k += 2 if page[k] == "\\" else 1
        try:
            inner = json.loads(json.loads('"' + page[j:k] + '"'))
            for part, v in (inner.get("productVariations") or {}).items():
                dims = {kk: vv for kk, vv in v.items() if kk.startswith("dimension")}
                variants[part] = {"title": htmlmod.unescape(v.get("productTitle", "")).replace("\xa0", " "), "dims": dims}
        except ValueError:
            pass
    prices = {}
    # {"partNumber":"MJUA4ZM/A","price":{… "priceTargetProduct":{…},"currentPrice":{"amount":"49,00 €","raw_amount":"49.00"}
    for m in re.finditer(r'"partNumber":"([A-Z0-9]+/A)","price":\{', raw):
        r = re.search(r'"raw_amount":"([\d.]+)"', raw[m.end():m.end() + 4000])
        if r:
            prices.setdefault(m.group(1), float(r.group(1)))
    if not prices:  # produs fără variante: prețul din datele de metrics
        for m in re.finditer(r'"partNumber":"([A-Z0-9]+/A)","price":\{"fullPrice":([\d.]+)\}', raw):
            prices.setdefault(m.group(1), float(m.group(2)))
    # etichetele dimensiunilor din <select name="dimensionX"><option value="…">Etichetă</option>
    labels = {}
    for sm in re.finditer(r'<select name="(dimension[A-Za-z]+)"[^>]*>(.*?)</select>', page, re.S):
        labels[sm.group(1)] = {v: htmlmod.unescape(t.strip()) for v, t in re.findall(r'<option value="([^"]*)"[^>]*>([^<]*)</option>', sm.group(2))}
    # mostrele de culoare: radioValue → imagine (fără sufixul _SW_COLOR)
    swatches = {}
    for m in re.finditer(r'"text":"([^"]*)","selected":(?:true|false),"radioValue":"([^"]*)","sortOrder":(\d+),"swatchImage":\{"sources":\[\{"srcSet":"[^"]*?/is/([A-Za-z0-9_\-]+?)(?:_SW_COLOR)?\?', raw):
        swatches[m.group(2)] = {"label": htmlmod.unescape(m.group(1)), "order": int(m.group(3)), "image": m.group(4)}
    title = re.search(r'<h1[^>]*data-autom="productTitle"[^>]*>([^<]+)</h1>', page)
    gallery = sorted(set(re.findall(r'as-images\.apple\.com/is/([A-Za-z0-9_\-]+)\?wid=1000', page)))
    return {"title": htmlmod.unescape(title.group(1).strip()) if title else "", "variants": variants, "prices": prices,
            "labels": labels, "swatches": swatches, "gallery": gallery}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.environ.get("APPLE_CACHE", "/tmp/apple-cache"))
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "data", "apple_accessories_de.json"))
    a = ap.parse_args()

    links = []
    for lst in LISTS:
        seen_page = set()
        for page_no in range(1, 30):
            url = BASE + lst + (f"?page={page_no}" if page_no > 1 else "")
            page = fetch(url, a.cache)
            found = [t for t in tiles(page) if t not in seen_page]
            if not found:
                break
            seen_page.update(found)
            links.extend(found)
        print(f"{lst}: {len(seen_page)} produse", file=sys.stderr)
    links = list(dict.fromkeys(links))
    print("linkuri unice:", len(links), file=sys.stderr)

    families = {}
    known = set()  # coduri deja găsite în familiile descărcate → nu mai deschidem paginile lor
    for i, link in enumerate(links):
        part = link.split("/product/")[1].split("/")[0].upper()
        if part.startswith("H"):
            continue  # produse terțe (Belkin, Logitech, …)
        if part + "/A" in known:
            continue
        page = fetch(BASE + link, a.cache)
        if not page:
            continue
        d = parse_pdp(page)
        if not d["variants"]:
            d["variants"] = {part + "/A": {"title": d["title"], "dims": {}}}
        known.update(d["variants"])
        key = ",".join(sorted(d["variants"]))
        if key in families:
            families[key]["links"].append(link)
            continue
        d["links"] = [link]
        families[key] = d
        if i % 20 == 0:
            print(f"  {i}/{len(links)} {d['title'][:60]} ({len(d['variants'])} variante)", file=sys.stderr)

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    out = sorted(families.values(), key=lambda f: f["title"])
    json.dump(out, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{len(out)} familii, {sum(len(f['variants']) for f in out)} variante → {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
