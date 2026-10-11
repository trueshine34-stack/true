# -*- coding: utf-8 -*-
"""
Construiește data/catalog.json + imaginile din public/img/products/.

  python3 tools/build_catalog.py --apple-cache <dir cu paginile apple.com/de descărcate>

Unealtă de dezvoltare (necesită Pillow). Magazinul în sine nu are nevoie de Python.
Imaginile sunt descărcate de pe CDN-ul Apple cu fundal transparent și aduse la același
format: pânză pătrată, produs centrat, aceeași margine, WebP cu transparență.
"""
import argparse, hashlib, io, json, math, os, re, sys, time, urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.dirname(__file__))
import catalog_data as D  # noqa: E402
from PIL import Image  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_JSON = os.path.join(ROOT, "data", "catalog.json")
IMG_DIR = os.path.join(ROOT, "public", "img", "products")
SIZES = (480, 1000)
CANVAS = 1200
FILL = 0.84          # cât din pânză ocupă latura mare a produsului
UA = {"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/128 Safari/537.36"}

EN = {"silver": "Silver", "burgundy": "Burgundy", "black": "Black", "glacier": "Glacier", "night-sky": "Night Sky",
      "star-white": "Star White", "skyblue": "Sky Blue", "lightgold": "Light Gold", "cloudwhite": "Cloud White",
      "spaceblack": "Space Black", "lavender": "Lavender", "sage": "Sage", "mistblue": "Mist Blue", "white": "White",
      "softpink": "Soft Pink", "ultramarine": "Ultramarine", "teal": "Teal", "pink": "Pink", "spacegray": "Space Gray",
      "blue": "Blue", "purple": "Purple", "starlight": "Starlight", "yellow": "Yellow"}
MPN_SUFFIX_OVERRIDE = {"MXCK3": "RO/A", "MXK83": "RO/A", "MXCL3": "RO/A", "MXK53": "Z/A", "MXK93": "Z/A",
                       "MWR03": "Z/A", "MWR53": "Z/A", "MDFV4": "Z/A", "MQDP3": "Z/A", "MW463": "ZM/A"}


def lei(eur, cat):
    v = eur * D.MULT[cat]
    step = 5 if v < 100 else 10 if v < 1000 else 50
    r = math.ceil(v / step) * step
    return round(r - 0.01, 2)


def bump(price, pct):
    """preț vechi (tăiat) plauzibil pentru promoții"""
    v = price * (1 + pct)
    step = 5 if v < 100 else 10 if v < 1000 else 50
    return round(math.ceil(v / step) * step - 0.01, 2)


def stock_for(sku):
    h = int(hashlib.sha1(sku.encode()).hexdigest()[:8], 16)
    if h % 17 == 0:
        return 0
    return 2 + h % 29


def sku_of(slug, opts):
    base = re.sub(r"[^A-Z0-9]", "", slug.upper().replace("IPHONE", "IP").replace("MACBOOK", "MB")
                  .replace("APPLE-WATCH", "AW").replace("IPAD", "IPD"))[:10]
    tail = "-".join(re.sub(r"[^A-Z0-9]", "", str(v).upper())[:12] for v in opts.values() if v != "-")
    sku = f"LV-{base}" + (f"-{tail}" if tail else "")
    n, out = 2, sku
    while out in SEEN_SKUS:
        out, n = f"{sku}-{n}", n + 1
    SEEN_SKUS.add(out)
    return out


SEEN_SKUS = set()
FALLBACK_HEX = {"transparent": "#E9EEF0", "black": "#2A2A2C", "white": "#F4F4F2", "silver": "#E3E4E1", "midnight": "#2E3643"}


# ------------------------------------------------------------------ date Apple DE
def load_metrics(cache):
    m = {}
    for f in os.listdir(cache):
        if not f.startswith("de-") or not f.endswith(".html"):
            continue
        s = open(os.path.join(cache, f), encoding="utf-8").read()
        mm = re.search(r'<script type="application/json" id="metrics">(.*?)</script>', s, re.S)
        if not mm:
            continue
        try:
            prods = json.loads(mm.group(1))["data"].get("products") or []
        except Exception:
            continue
        m[f[:-5]] = [(p["partNumber"], float(p["price"]["fullPrice"]), p["name"]) for p in prods]
    return m


def watch_products(cache, page):
    sys.path.insert(0, cache)
    s = open(os.path.join(cache, f"de-{page}.html"), encoding="utf-8").read()
    i = s.find("productSelectionData:")
    j = s.find("{", i)
    depth, k, instr, esc = 0, j, False, False
    while True:
        c = s[k]
        if instr:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': instr = False
        else:
            if c == '"': instr = True
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0: break
        k += 1
    data = json.loads(s[j:k + 1])
    prices = {pk: v["amount"] for pk, v in data["displayValues"].get("prices", {}).items()} if "prices" in data["displayValues"] else {}
    if not prices:
        prices = {k2: float(v) for k2, v in re.findall(r'"([A-Za-z0-9_\-]+)":\{"comparativeDisplayPrice":"(?:[^"\\]|\\.)*","amount":([0-9.]+)', s)}
    return data["products"], prices


# ------------------------------------------------------------------ imagini
def fetch(url, tries=3):
    for t in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception as e:
            err = e
            time.sleep(1.5 * (t + 1))
    raise RuntimeError(f"download failed {url}: {err}")


def get_alpha(name, cache_dir):
    p = os.path.join(cache_dir, re.sub(r"[^A-Za-z0-9_.-]", "_", name) + ".png")
    if not os.path.exists(p):
        open(p, "wb").write(fetch(f"{D.CDN}{name}?wid=1400&hei=1400&fmt=png-alpha&.v=1"))
    return Image.open(p).convert("RGBA")


def get_keyed(name, cache_dir):
    """imagini Apple cu fundal deschis uniform → transparență prin flood-fill de la margini"""
    p = os.path.join(cache_dir, re.sub(r"[^A-Za-z0-9_.-]", "_", name) + "-bg.png")
    if not os.path.exists(p):
        open(p, "wb").write(fetch(f"{D.CDN}{name}?wid=1600&hei=1600&fmt=png&.v=1"))
    im = Image.open(p).convert("RGB")
    w, h = im.size
    px = im.load()
    # fundalul poate fi alb + un panou gri (#F5F5F7) — tratăm ambele culori ca fundal
    bgs = {px[2, 2], px[w // 16, h // 2], px[w // 2, 4]}
    tol = 7
    alpha = Image.new("L", (w, h), 255)
    ap = alpha.load()
    seen = bytearray(w * h)
    stack = [(x, 0) for x in range(w)] + [(x, h - 1) for x in range(w)] + [(0, y) for y in range(h)] + [(w - 1, y) for y in range(h)]
    while stack:
        x, y = stack.pop()
        i = y * w + x
        if seen[i]:
            continue
        seen[i] = 1
        r, g, b = px[x, y]
        if any(abs(r - bg[0]) <= tol and abs(g - bg[1]) <= tol and abs(b - bg[2]) <= tol for bg in bgs):
            ap[x, y] = 0
            if x > 0: stack.append((x - 1, y))
            if x < w - 1: stack.append((x + 1, y))
            if y > 0: stack.append((x, y - 1))
            if y < h - 1: stack.append((x, y + 1))
    # margine moale de 1px
    from PIL import ImageFilter
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    out = im.convert("RGBA")
    out.putalpha(alpha)
    return out


def get_watch(spec, cache_dir):
    layers = [get_alpha(n, cache_dir) for n in spec.split("|")]
    size = layers[1].size
    out = Image.new("RGBA", size, (0, 0, 0, 0))
    for l in layers:
        if l.size != size:
            l = l.resize(size, Image.LANCZOS)
        out.alpha_composite(l)
    return out


def swatch_hex(name, cache_dir):
    """culoarea medie a mostrei Apple (centrul imaginii _SW_COLOR) → #RRGGBB pentru buline"""
    p = os.path.join(cache_dir, re.sub(r"[^A-Za-z0-9_.-]", "_", name) + "-sw.png")
    if not os.path.exists(p):
        open(p, "wb").write(fetch(f"{D.CDN}{name}_SW_COLOR?wid=64&hei=64&fmt=png&.v=1"))
    im = Image.open(p).convert("RGB").resize((64, 64))
    box = im.crop((18, 18, 46, 46))
    px = list(box.getdata())
    r, g, b = (round(sum(c[i] for c in px) / len(px)) for i in range(3))
    return f"#{r:02X}{g:02X}{b:02X}"


def normalize(im, fill=FILL):
    bbox = im.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    if bbox:
        im = im.crop(bbox)
    w, h = im.size
    scale = (CANVAS * fill) / max(w, h)
    im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((CANVAS - im.width) // 2, (CANVAS - im.height) // 2))
    return canvas


def save_variants(im, rel_base):
    paths = {}
    for s in SIZES:
        out = os.path.join(IMG_DIR, rel_base + f"-{s}.webp")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        im.resize((s, s), Image.LANCZOS).save(out, "WEBP", quality=82, method=6)
        paths[s] = "/img/products/" + rel_base + f"-{s}.webp"
    return paths


def process_image(name, rel_base, cache_dir, fill):
    if name.startswith("@watch:"):
        im = get_watch(name[7:], cache_dir)
    elif name.startswith("@bg:"):
        im = get_keyed(name[4:], cache_dir)
    else:
        im = get_alpha(name, cache_dir)
    return save_variants(normalize(im, fill), rel_base)


# ------------------------------------------------------------------ variante
def build_variants(p, metrics, cache):
    cat = p["category"]
    opts = p.get("options", [])
    keys = [o["key"] for o in opts]
    variants = []

    def add(o, price, mpn=None, compare=None, sku=None):
        if sku:
            SEEN_SKUS.add(sku)
        else:
            sku = sku_of(p["slug"], o)
        v = {"sku": sku, "options": o, "price": price, "mpn": mpn}
        if compare:
            v["compare_at"] = compare
        variants.append(v)

    if "variants_explicit" in p:  # accesorii din magazinul Apple: doar combinațiile care există, fiecare cu codul ei
        for ve in p["variants_explicit"]:
            price = lei(ve["eur"], cat)
            add(ve["options"], price, ve.get("mpn"), bump(price, p["promo"]) if p.get("promo") else None, ve.get("sku"))
        return variants
    if p.get("watch_matrix") == "s12":
        prods, prices = watch_products(cache, "apple-watch")
        mp = {}
        for pr in prods:
            d = pr["dimensions"]
            conn = "cell" if d["watch_cases-dimensionConnection"] == "gpscell" else "gps"
            col = d["watch_cases-dimensionColor"].replace("_", "")
            mp[(d["watch_cases-dimensionCaseMaterial"], col, d["watch_cases-dimensionCaseSize"], conn)] = pr["part"]
        for mat, cols in D.S12_COLORS.items():
            for c in cols:
                for size in ("42mm", "46mm"):
                    for conn in (("gps", "cell") if mat == "aluminum" else ("cell",)):
                        pk = f"watch_cases-{mat}-{size}-{'gpscell' if conn == 'cell' else 'gps'}"
                        add({"material": mat, "color": c, "size": size, "conn": conn}, lei(prices[pk], cat),
                            mp.get((mat, c, size, conn)))
        return variants
    if p.get("watch_matrix") == "se":
        prods, prices = watch_products(cache, "apple-watch-se")
        mp = {}
        for pr in prods:
            d = pr["dimensions"]
            conn = "cell" if d["watch_cases-dimensionConnection"] == "gpscell" else "gps"
            mp[(d["watch_cases-dimensionColor"], d["watch_cases-dimensionCaseSize"], conn)] = pr["part"]
        for c in ("midnight", "starlight"):
            for size in ("40mm", "44mm"):
                for conn in ("gps", "cell"):
                    pk = f"watch_cases-aluminum-{size}-{'gpscell' if conn == 'cell' else 'gps'}"
                    add({"color": c, "size": size, "conn": conn}, lei(prices[pk], cat), mp.get((c, size, conn)))
        return variants

    if "eur_from" in p:  # iPad: preț + MPN din pagina Apple DE
        rows = metrics[p["eur_from"]]
        glasses = next(([v["value"] for v in o["values"]] for o in opts if o["key"] == "glass"), [None])
        combos = [(c, st, g, cn) for c in [v["value"] for v in opts[0]["values"]]
                  for st in [v["value"] for v in opts[1]["values"]] for g in glasses for cn in ("wifi", "cell")]
        for c, st, glass, conn in combos:
            hit = None
            for pn, pr, name in rows:
                n = name.replace("‑", "-").replace("\xa0", " ")
                if p.get("eur_size") and not n.startswith(p["eur_size"]):
                    continue
                if f" {st.upper()}" not in n.upper():
                    continue
                is_cell = "Cellular" in n
                if is_cell != (conn == "cell"):
                    continue
                if ("nano-texture" in n) != (glass == "nano"):
                    continue
                col = EN.get(c, c)
                tail = n.rsplit(" - ", 1)[-1].strip() if " - " in n else ""
                if tail == col or (c == "silver" and tail in ("", "Silver")):
                    hit = (pn, pr)
                    break
            if not hit:
                if glass != "nano":  # nanotextura există doar la 1 TB / 2 TB
                    print("  ! lipsă iPad", p["slug"], c, st, conn, file=sys.stderr)
                continue
            price = lei(hit[1], cat)
            o = {"color": c, "storage": st, **({"glass": glass} if glass else {}), "conn": conn}
            add(o, price, hit[0],
                bump(price, p["promo"]) if p.get("promo") else None)
        return variants

    if "mpn_from" in p:  # iPhone
        rows = metrics[p["mpn_from"]]
        for c in [v["value"] for v in opts[0]["values"]]:
            for st in [v["value"] for v in opts[1]["values"]]:
                mpn = None
                for pn, pr, name in rows:
                    if p.get("mpn_filter") and p["mpn_filter"] not in name + " ":
                        continue
                    if p.get("mpn_filter") == "Pro " and "Max" in name:
                        continue
                    if st.upper() in name.upper().replace(" ", "") and name.endswith(EN[c]):
                        mpn = pn
                        break
                price = lei(p["eur"][st], cat)
                add({"color": c, "storage": st}, price, mpn, bump(price, p["promo"]) if p.get("promo") else None)
        return variants

    # cazuri generice
    import itertools
    value_lists = [[v["value"] for v in o["values"]] for o in opts]
    combos = list(itertools.product(*value_lists)) if opts else [()]
    for combo in combos:
        o = dict(zip(keys, combo))
        if "ron" in p:
            price = p["ron"][o["config"]]
        elif "eur" in p:
            ek = None
            for k in ("storage", "config", "case", "model", "pack"):
                if k in o:
                    ek = o[k]
            if ek is None and "glass" in o:
                ek = (o["glass"], o["stand"])
                if ek not in p["eur"]:
                    continue
            price = lei(p["eur"][ek] + (p.get("display_extra_eur", 0) if o.get("display") == "nano" else 0), cat)
        else:
            price = lei(p["eur_flat"], cat)
        mpn = None
        if "mpn" in p:
            hit = p["mpn"].get(tuple(o[k] for k in keys))
            if hit:
                mpn = hit + p.get("mpn_suffix", "ZM/A")
        elif "mpn_single" in p:
            base = p["mpn_single"][:5]
            mpn = base + MPN_SUFFIX_OVERRIDE.get(base, "ZM/A")
        add(o, price, mpn, bump(price, p["promo"]) if p.get("promo") else None)
    return variants


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apple-cache", required=True)
    ap.add_argument("--img-cache", default=os.path.join(ROOT, ".cache", "apple-img"))
    ap.add_argument("--skip-images", action="store_true")
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    os.makedirs(a.img_cache, exist_ok=True)
    metrics = load_metrics(a.apple_cache)

    out_products = []
    for p in D.PRODUCTS:
        if a.only and p["slug"] not in a.only.split(","):
            continue
        variants = build_variants(p, metrics, a.apple_cache)
        if not variants:
            print("!! fără variante", p["slug"], file=sys.stderr)
            continue
        for v in variants:
            v["stock"] = 0 if p.get("stock_mode") == "preorder" else stock_for(v["sku"])
        # imagini
        fill = p.get("fill") or (0.72 if p["category"] == "accesorii" else 0.80 if p["category"] in ("tv-home",) else D_FILL(p))
        # bulinele de culoare ale accesoriilor: culoarea medie a mostrei Apple
        for o in p.get("options", []):
            for v in o["values"]:
                if "hex" in v and v["hex"] is None:
                    sw = (p.get("swatch_images") or {}).get(v["value"])
                    try:
                        v["hex"] = swatch_hex(sw, a.img_cache) if sw and not a.skip_images else FALLBACK_HEX.get(v["value"], "#C8CCC6")
                    except Exception as e:  # noqa: BLE001
                        print(f"  ! mostră {p['slug']} {v['value']}: {e}", file=sys.stderr)
                        v["hex"] = FALLBACK_HEX.get(v["value"], "#C8CCC6")
        images = {}
        src = p.get("images") or {}
        if "images_by_option" in p:
            src = p["images_by_option"]
        for key, names in src.items():
            lst = []
            for i, n in enumerate(names):
                rel = f"{p['slug']}/{'main' if key == '-' else key}-{i + 1}"
                if a.skip_images:
                    lst.append({s: f"/img/products/{rel}-{s}.webp" for s in SIZES})
                    continue
                try:
                    lst.append(process_image(n, rel, a.img_cache, fill))
                except Exception as e:
                    print(f"  ! imagine eșuată {p['slug']} {n}: {e}", file=sys.stderr)
            if lst:
                images[key] = lst
        prices = [v["price"] for v in variants]
        prod = {k: p.get(k) for k in ("slug", "name", "category", "group", "badge", "featured", "release", "tagline",
                                      "description", "highlights", "in_box", "options")}
        prod["specs"] = [{"group": g, "rows": [{"k": k, "v": v} for k, v in rows]} for g, rows in p.get("specs", [])]
        prod["image_key"] = ("color" if "color" in [o["key"] for o in p.get("options", [])] and "images" in p and "-" not in src
                             else (p["options"][0]["key"] if "images_by_option" in p else None))
        prod["images"] = images
        prod["variants"] = variants
        prod["preorder"] = p.get("stock_mode") == "preorder"
        prod["price_from"] = min(prices)
        prod["warranty"] = D.WARRANTY
        out_products.append(prod)
        print(f"{p['slug']:48s} {len(variants):3d} var  de la {min(prices):>10,.2f} lei  img:{sum(len(x) for x in images.values())}")

    data = {"generated": time.strftime("%Y-%m-%d"), "currency": "RON", "vat": 21,
            "categories": D.CATEGORIES, "products": out_products}
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    if a.only and os.path.exists(OUT_JSON):
        old = json.load(open(OUT_JSON, encoding="utf-8"))
        keep = [x for x in old["products"] if x["slug"] not in a.only.split(",")]
        order = [p["slug"] for p in D.PRODUCTS]
        data["products"] = sorted(keep + out_products, key=lambda x: order.index(x["slug"]) if x["slug"] in order else 999)
    json.dump(data, open(OUT_JSON, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("produse:", len(data["products"]), "variante:", sum(len(x["variants"]) for x in data["products"]))


def D_FILL(p):
    if p["category"] == "watch":
        return 0.86
    if p["category"] == "airpods":
        return 0.78
    return FILL


if __name__ == "__main__":
    main()
