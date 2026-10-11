# -*- coding: utf-8 -*-
"""
Sursa catalogului Livada (dev-only).

Prețurile de referință sunt prețurile Apple Store Germania (EUR, oct. 2026),
convertite în prețuri medii de piață din România (lei, cu TVA 21%) prin
multiplicatori pe categorie calibrați pe prețurile eMAG / Altex / iSTYLE.
Codurile MPN sunt codurile Apple reale (ZD/A, ZM/A — aceleași ca în RO;
pentru Mac se folosește varianta cu tastatură românească RO/A).

Rulează:  python3 tools/build_catalog.py   (vezi README → „Catalog și imagini”)
"""

CATEGORIES = [
    {"slug": "iphone", "name": "iPhone", "title": "iPhone",
     "tagline": "De la iPhone 17e până la iPhone Duo pliabil.",
     "groups": ["Pliabil", "Pro", "Air", "Clasic"]},
    {"slug": "mac", "name": "Mac", "title": "Mac",
     "tagline": "Laptopuri, desktopuri și monitoare cu cipuri Apple.",
     "groups": ["Laptopuri", "Desktop", "Monitoare"]},
    {"slug": "ipad", "name": "iPad", "title": "iPad",
     "tagline": "Ecran mare, cip puternic, Apple Pencil Pro.",
     "groups": ["iPad Pro", "iPad Air", "iPad", "iPad mini"]},
    {"slug": "watch", "name": "Watch", "title": "Apple Watch",
     "tagline": "Sănătate, sport și notificări — la încheietură.",
     "groups": ["Series 12", "Ultra", "SE"]},
    {"slug": "airpods", "name": "AirPods", "title": "AirPods și audio",
     "tagline": "AirPods și toată gama Beats, în toate culorile.",
     "groups": ["AirPods", "Beats"]},
    {"slug": "tv-home", "name": "TV & Home", "title": "TV & Home",
     "tagline": "Apple TV 4K, HomePod și AirTag.",
     "groups": ["Apple TV", "HomePod", "AirTag"]},
    {"slug": "accesorii", "name": "Accesorii", "title": "Accesorii",
     "tagline": "Toate accesoriile Apple și Beats, în toate culorile și mărimile.",
     "groups": ["Huse iPhone", "iPad și Apple Pencil", "Curele Apple Watch", "Încărcare",
                "Cabluri și adaptoare", "Tastaturi și mouse", "Audio", "Diverse"]},
]

# multiplicator EUR(DE) → lei(RO), calibrat pe prețuri reale de retail
MULT = {"iphone": 5.25, "watch": 5.15, "airpods": 5.0, "mac": 4.4,
        "ipad": 4.6, "accesorii": 4.9, "tv-home": 4.9}

CDN = "https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/"

# ---------------------------------------------------------------- culori
C = {
    # iPhone 18 Pro
    "silver": ("Argintiu", "#E3E4E1"), "burgundy": ("Burgundia", "#5B2B37"),
    "black": ("Negru", "#2A2A2C"), "glacier": ("Glacier", "#C9DBE5"),
    # Duo
    "night-sky": ("Night Sky", "#2B3142"), "star-white": ("Star White", "#ECEAE4"),
    # Air
    "skyblue": ("Albastru cer", "#C7DCEB"), "lightgold": ("Auriu deschis", "#E8D9C2"),
    "cloudwhite": ("Alb nor", "#F3F3F1"), "spaceblack": ("Negru spațial", "#1F2023"),
    # 17
    "lavender": ("Lavandă", "#D8CCE8"), "sage": ("Salvie", "#B7C7A6"),
    "mistblue": ("Albastru ceață", "#A7BCD4"), "white": ("Alb", "#F4F4F2"),
    # 17e
    "softpink": ("Roz pal", "#F1D5D7"),
    # 16
    "ultramarine": ("Ultramarin", "#6772D8"), "teal": ("Turcoaz", "#8CC6BF"),
    "pink": ("Roz", "#F0BFD0"),
    # Mac
    "blush": ("Blush", "#E9C7C0"), "citrus": ("Citrus", "#E3E07F"),
    "indigo": ("Indigo", "#4A4F7C"), "starlight": ("Lumina stelelor", "#ECE4D7"),
    "midnight": ("Miezul nopții", "#2E3643"),
    "blue": ("Albastru", "#8DB4D8"), "purple": ("Mov", "#B9A8DA"),
    "orange": ("Portocaliu", "#F2A46A"), "yellow": ("Galben", "#F1D06A"),
    "green": ("Verde", "#9AC8A4"),
    # iPad
    "spacegray": ("Gri spațial", "#6E6E73"),
    # Watch
    "darkbronze": ("Bronz închis", "#6A5345"), "radiantgold": ("Auriu strălucitor", "#D8B878"),
    "natural": ("Natural", "#C8C1B6"), "pearlwhite": ("Alb perlat", "#F0EDE7"),
    "nightblue": ("Albastru nocturn", "#2B3551"),
}


def colors(*keys):
    return [{"value": k, "label": C[k][0], "hex": C[k][1]} for k in keys]


def storage(*vals):
    return [{"value": v, "label": v.replace("gb", " GB").replace("tb", " TB").upper()
             .replace(" GB", " GB").replace(" TB", " TB")} for v in vals]


def opt(value, label, hint=None):
    o = {"value": value, "label": label}
    if hint:
        o["hint"] = hint
    return o


# ---------------------------------------------------------------- texte comune
IPHONE_BOX = ["iPhone", "Cablu USB‑C de încărcare (1 m)", "Documentație"]
WARRANTY = "24 de luni garanție (garanția legală de conformitate) + 14 zile drept de retur."

PRODUCTS = []


def P(**kw):
    PRODUCTS.append(kw)
    return kw


# ================================================================= iPhone
P(slug="iphone-duo", name="iPhone Duo", category="iphone", group="Pliabil",
  badge="Precomandă", featured=True, release="Livrare începând cu 23 octombrie 2026",
  tagline="Primul iPhone pliabil. Două ecrane, un singur gest.",
  description=[
      "iPhone Duo se deschide într-un ecran interior de 7,6\" fără cută vizibilă și se închide într-un iPhone de 5,4\" care încape în orice buzunar. Carcasa din titan are doar 5,2 mm grosime deschisă.",
      "Cipul A20 Pro, sistemul de camere de 48 MP și autonomia de o zi întreagă fac din Duo un telefon complet — și o tabletă atunci când ai nevoie de una.",
  ],
  highlights=["Ecran interior pliabil 7,6\" ProMotion", "Ecran exterior 5,4\" Always‑On",
              "Cip A20 Pro", "Doar 5,2 mm grosime deschis", "Touch ID în butonul lateral"],
  specs=[
      ("Ecran", [("Interior", "7,6\" OLED pliabil, 2670×1878, 430 ppi, ProMotion 120 Hz"),
                 ("Exterior", "5,4\" OLED, 2034×1398, 460 ppi, Always‑On"),
                 ("Luminozitate", "1000 nits tipic, 3000 nits în exterior")]),
      ("Performanță", [("Cip", "A20 Pro — CPU 6 nuclee, GPU 7 nuclee, Neural Engine dual 16 nuclee")]),
      ("Camere", [("Principală", "48 MP Fusion, ƒ/1.6"), ("Ultra Wide", "48 MP, 120°"),
                  ("Teleobiectiv", "12 MP 2×"), ("Frontale", "12 MP Center Stage + cameră sub ecran")]),
      ("Autonomie", [("Baterie", "Două celule, până la 24 de ore utilizare tipică"),
                     ("Încărcare rapidă", "50% în aproximativ 20–30 de minute")]),
      ("Design", [("Material", "Titan, Ceramic Shield 2"), ("Grosime", "5,2 mm deschis / 11,3 mm închis"),
                  ("Greutate", "254 g"), ("Rezistență", "IP68 (6 m, 30 min)")]),
      ("Securitate", [("Autentificare", "Touch ID în butonul lateral")]),
  ],
  in_box=["iPhone Duo", "Cablu USB‑C țesut (1 m)", "Documentație"],
  options=[{"key": "color", "label": "Culoare", "values": colors("night-sky", "star-white")},
           {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb", "1tb", "2tb")}],
  eur={"256gb": 2299, "512gb": 2549, "1tb": 3049, "2tb": 3799},
  mpn_from="de-iphone-duo",
  images={c: [f"iphone-duo-finish-select-{c}-202609_GEO_EMEA", f"iphone-duo-finish-select-{c}-202609_AV2",
              f"iphone-duo-finish-select-{c}-202609_AV3"] for c in ["night-sky", "star-white"]},
  stock_mode="preorder")

for model, size, eur, title in [
    ("iphone-18-pro", "6,3\"", {"256gb": 1449, "512gb": 1699, "1tb": 2199, "2tb": 2949}, "iPhone 18 Pro"),
    ("iphone-18-pro-max", "6,9\"", {"256gb": 1599, "512gb": 1849, "1tb": 2349, "2tb": 3099}, "iPhone 18 Pro Max"),
]:
    big = model.endswith("max")
    P(slug=model, name=title, category="iphone", group="Pro", badge="Nou", featured=True,
      tagline="Pro în fiecare detaliu. Cameră cu diafragmă variabilă." if not big else "Cel mai mare ecran și cea mai lungă autonomie într-un iPhone Pro.",
      description=[
          f"{title} aduce cipul A20 Pro, un ecran {size} ProMotion de până la 3000 nits în exterior și un sistem triplu de camere de 48 MP cu diafragmă variabilă pe camera principală.",
          "Modemul Apple C2, Wi‑Fi 7 și unibody‑ul din aluminiu cu Ceramic Shield 2 îl fac mai rapid, mai rezistent și mai eficient decât oricând." + (" Autonomia ajunge la 45 de ore de redare video." if big else " Autonomia ajunge la 36 de ore de redare video."),
      ],
      highlights=[f"Ecran {size} ProMotion, Always‑On", "Cip A20 Pro", "Cameră principală 48 MP, ƒ/1.48–ƒ/4.0",
                  "Zoom optic de calitate 8×", "Modem Apple C2 · Wi‑Fi 7"],
      specs=[
          ("Ecran", [("Diagonală", f"{size} Super Retina XDR OLED"),
                     ("Rezoluție", "2868×1320, 460 ppi" if big else "2622×1206, 460 ppi"),
                     ("Tehnologii", "ProMotion până la 120 Hz, Always‑On, 3000 nits în exterior")]),
          ("Performanță", [("Cip", "A20 Pro — CPU 6 nuclee, GPU 7 nuclee"), ("Modem", "Apple C2, 5G")]),
          ("Camere", [("Principală", "48 MP, 24 mm, ƒ/1.48–ƒ/4.0 variabilă"), ("Ultra Wide", "48 MP, 13 mm, 120°"),
                      ("Teleobiectiv", "48 MP 4× (100 mm), opțiune 8× (200 mm)"), ("Frontală", "18 MP Center Stage")]),
          ("Autonomie", [("Redare video", "până la 45 de ore" if big else "până la 36 de ore"),
                         ("Încărcare rapidă", "50% în ~15 minute cu adaptor de 60 W")]),
          ("Conectivitate", [("Wireless", "Wi‑Fi 7, Bluetooth 6, UWB gen. 2, NFC, Thread"),
                             ("Port", "USB‑C (USB 3, până la 10 Gb/s)")]),
          ("Design", [("Material", "Unibody din aluminiu, Ceramic Shield 2"),
                      ("Greutate", "249 g" if big else "211 g"), ("Rezistență", "IP68 (6 m, 30 min)")]),
      ],
      in_box=IPHONE_BOX,
      options=[{"key": "color", "label": "Culoare", "values": colors("silver", "burgundy", "black", "glacier")},
               {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb", "1tb", "2tb")}],
      eur=eur, mpn_from="de-18pro", mpn_filter=("Max" if big else "Pro "),
      images={c: [f"{model}-finish-select-{c}-202609"] for c in (["glacier", "silver", "burgundy", "black"] if big else ["silver", "burgundy", "black", "glacier"])})

P(slug="iphone-air", name="iPhone Air", category="iphone", group="Air",
  tagline="Cel mai subțire iPhone. Incredibil de ușor, surprinzător de puternic.",
  description=["iPhone Air are doar 5,6 mm grosime și un cadru din titan, cu ecran ProMotion de 6,5\" și cipul A19 Pro.",
               "Camera Fusion de 48 MP, modemul Apple C1X și Ceramic Shield 2 pe ambele fețe — totul într-un iPhone pe care aproape că nu-l simți în buzunar."],
  highlights=["Doar 5,6 mm grosime", "Ecran 6,5\" ProMotion", "Cip A19 Pro", "Cameră Fusion 48 MP", "Cadru din titan"],
  specs=[("Ecran", [("Diagonală", "6,5\" Super Retina XDR, ProMotion 120 Hz, Always‑On")]),
         ("Performanță", [("Cip", "A19 Pro"), ("Modem", "Apple C1X")]),
         ("Camere", [("Principală", "48 MP Fusion"), ("Frontală", "18 MP Center Stage")]),
         ("Autonomie", [("Redare video", "până la 27 de ore")]),
         ("Design", [("Grosime", "5,64 mm"), ("Greutate", "165 g"), ("Material", "Titan, Ceramic Shield 2"),
                     ("Rezistență", "IP68")])],
  in_box=IPHONE_BOX,
  options=[{"key": "color", "label": "Culoare", "values": colors("skyblue", "lightgold", "cloudwhite", "spaceblack")},
           {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb", "1tb")}],
  eur={"256gb": 1299, "512gb": 1549, "1tb": 2049}, mpn_from="de-iphone-air",
  images={c: [f"iphone-air-finish-select-{c}-202509_GEO_EMEA", f"iphone-air-finish-select-{c}-202509_AV2",
              f"iphone-air-finish-select-{c}-202509_AV3"] for c in ["skyblue", "lightgold", "cloudwhite", "spaceblack"]})

P(slug="iphone-17", name="iPhone 17", category="iphone", group="Clasic",
  tagline="Ecran ProMotion, cameră frontală Center Stage, 256 GB din start.",
  description=["iPhone 17 vine cu ecran de 6,3\" ProMotion Always‑On, cip A19 și un sistem de camere duble de 48 MP.",
               "Camera frontală Center Stage de 18 MP și Ceramic Shield 2, de trei ori mai rezistent la zgârieturi, completează cel mai echilibrat iPhone."],
  highlights=["Ecran 6,3\" ProMotion 120 Hz", "Cip A19", "Camere duale 48 MP", "Selfie Center Stage 18 MP", "256 GB stocare de bază"],
  specs=[("Ecran", [("Diagonală", "6,3\" Super Retina XDR, ProMotion, Always‑On")]),
         ("Performanță", [("Cip", "A19")]),
         ("Camere", [("Principală", "48 MP Fusion"), ("Ultra Wide", "48 MP"), ("Frontală", "18 MP Center Stage")]),
         ("Autonomie", [("Redare video", "până la 30 de ore")]),
         ("Design", [("Material", "Aluminiu, Ceramic Shield 2"), ("Greutate", "177 g"), ("Rezistență", "IP68")])],
  in_box=IPHONE_BOX,
  options=[{"key": "color", "label": "Culoare", "values": colors("lavender", "sage", "mistblue", "white", "black")},
           {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb")}],
  eur={"256gb": 1099, "512gb": 1349}, mpn_from="de-iphone-17", promo=0.06,
  images={c: [f"iphone-17-finish-select-{c}-202509_GEO_EMEA"] for c in ["lavender", "sage", "mistblue", "white", "black"]})

P(slug="iphone-17e", name="iPhone 17e", category="iphone", group="Clasic", badge="Nou",
  tagline="Tot ce contează la un iPhone, la cel mai bun preț.",
  description=["iPhone 17e aduce cipul A19, modemul Apple C1X și MagSafe într-un design de 6,1\" — acum cu 256 GB stocare de bază.",
               "Camera Fusion de 48 MP face fotografii excelente zi și noapte, iar autonomia te duce lejer până seara."],
  highlights=["Cip A19", "256 GB din start", "MagSafe", "Cameră Fusion 48 MP", "Ecran 6,1\" Super Retina XDR"],
  specs=[("Ecran", [("Diagonală", "6,1\" Super Retina XDR OLED")]),
         ("Performanță", [("Cip", "A19"), ("Modem", "Apple C1X")]),
         ("Camere", [("Principală", "48 MP Fusion"), ("Frontală", "12 MP")]),
         ("Autonomie", [("Redare video", "până la 26 de ore")]),
         ("Design", [("Material", "Aluminiu, Ceramic Shield"), ("Rezistență", "IP68"), ("Încărcare", "MagSafe, Qi2")])],
  in_box=IPHONE_BOX,
  options=[{"key": "color", "label": "Culoare", "values": colors("softpink", "white", "black")},
           {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb")}],
  eur={"256gb": 849, "512gb": 1099}, mpn_from="de-iphone-17e",
  images={c: [f"iphone-17e-finish-select-{c}-202603", f"iphone-17e-finish-select-{c}-202603_AV2"] for c in ["softpink", "white", "black"]})

P(slug="iphone-16", name="iPhone 16", category="iphone", group="Clasic",
  tagline="Camera Control, Apple Intelligence și cip A18.",
  description=["iPhone 16 are butonul Camera Control, cip A18 și o cameră Fusion de 48 MP — o alegere excelentă la un preț redus.",
               "Butonul de acțiune, USB‑C și autonomia de până la 22 de ore de redare video îl fac și acum un iPhone complet."],
  highlights=["Cip A18", "Camera Control", "Cameră Fusion 48 MP", "Ecran 6,1\"", "Buton de acțiune"],
  specs=[("Ecran", [("Diagonală", "6,1\" Super Retina XDR OLED")]),
         ("Performanță", [("Cip", "A18")]),
         ("Camere", [("Principală", "48 MP Fusion"), ("Ultra Wide", "12 MP")]),
         ("Autonomie", [("Redare video", "până la 22 de ore")]),
         ("Design", [("Material", "Aluminiu, sticlă colorată în masă"), ("Rezistență", "IP68")])],
  in_box=IPHONE_BOX,
  options=[{"key": "color", "label": "Culoare", "values": colors("ultramarine", "teal", "pink", "white", "black")},
           {"key": "storage", "label": "Stocare", "values": storage("128gb")}],
  eur={"128gb": 999}, mpn_from="de-iphone-16", promo=0.12,
  images={c: [f"iphone-16-{c}-select-202409", f"iphone-16-{c}-select-202409_AV2", f"iphone-16-{c}-select-202409_AV3"]
          for c in ["ultramarine", "teal", "pink", "white", "black"]})

# ================================================================= Mac
P(slug="macbook-neo", name="MacBook Neo", category="mac", group="Laptopuri", badge="Nou", featured=True,
  tagline="Primul tău Mac. În patru culori vesele.",
  description=["MacBook Neo este cel mai accesibil laptop Apple: ecran Liquid Retina de 13\", cip A18 Pro și până la 16 ore de autonomie, într-o carcasă din aluminiu de 1,23 kg.",
               "Perfect pentru școală, facultate și tot ce faci zilnic — cu macOS, iCloud și integrare perfectă cu iPhone."],
  highlights=["Ecran 13\" Liquid Retina, 500 nits", "Cip A18 Pro", "Până la 16 ore autonomie", "1,23 kg", "4 culori"],
  specs=[("Ecran", [("Diagonală", "13\" Liquid Retina, 2408×1506, 500 nits")]),
         ("Performanță", [("Cip", "A18 Pro — CPU 6 nuclee, GPU 5 nuclee"), ("Memorie unificată", "8 GB")]),
         ("Autonomie", [("Streaming video", "până la 16 ore"), ("Web wireless", "până la 11 ore")]),
         ("Porturi", [("Conectori", "USB‑C (USB 3, DisplayPort), USB‑C (USB 2), jack 3,5 mm")]),
         ("Design", [("Greutate", "1,23 kg"), ("Cameră", "FaceTime HD 1080p")])],
  in_box=["MacBook Neo", "Adaptor de alimentare USB‑C", "Cablu USB‑C"],
  options=[{"key": "color", "label": "Culoare", "values": colors("silver", "blush", "citrus", "indigo")},
           {"key": "config", "label": "Configurație", "values": [
               opt("256gb", "8 GB · 256 GB SSD", "Magic Keyboard"),
               opt("512gb", "8 GB · 512 GB SSD", "Magic Keyboard cu Touch ID")]}],
  ron={"256gb": 3499.99, "512gb": 3999.99},
  mpn={("indigo", "256gb"): "MHFF4", ("blush", "512gb"): "MHFJ4", ("blush", "256gb"): "MHFH4",
       ("indigo", "512gb"): "MHFG4", ("citrus", "256gb"): "MHFD4", ("silver", "256gb"): "MHFA4",
       ("citrus", "512gb"): "MHFE4", ("silver", "512gb"): "MHFC4"}, mpn_suffix="RO/A",
  images={c: [f"macbook-neo-{c}-cto-hero-202603"] for c in ["silver", "blush", "citrus", "indigo"]})

MBA_SPECS = lambda inch: [
    ("Ecran", [("Diagonală", "13,6\" Liquid Retina, 2560×1664, 500 nits" if inch == 13 else "15,3\" Liquid Retina, 2880×1864, 500 nits")]),
    ("Performanță", [("Cip", "Apple M5 — CPU 10 nuclee, Neural Engine 16 nuclee")]),
    ("Autonomie", [("Streaming video", "până la 18 ore"), ("Web wireless", "până la 15 ore")]),
    ("Porturi", [("Conectori", "MagSafe 3, 2× Thunderbolt 4, jack 3,5 mm"), ("Wireless", "Wi‑Fi 7, Bluetooth 6")]),
    ("Design", [("Greutate", "1,23 kg" if inch == 13 else "1,51 kg"), ("Cameră", "12 MP Center Stage, Desk View"),
                ("Tastatură", "Magic Keyboard iluminată cu Touch ID, layout RO")]),
]
for inch, cfgs in [(13, [("m5-8-16-512", "M5 · GPU 8 nuclee · 16 GB · 512 GB", 5999.99),
                         ("m5-10-16-1tb", "M5 · GPU 10 nuclee · 16 GB · 1 TB", 6999.99),
                         ("m5-10-24-1tb", "M5 · GPU 10 nuclee · 24 GB · 1 TB", 8399.99)]),
                   (15, [("m5-10-16-512", "M5 · GPU 10 nuclee · 16 GB · 512 GB", 7349.99),
                         ("m5-10-16-1tb", "M5 · GPU 10 nuclee · 16 GB · 1 TB", 8349.99),
                         ("m5-10-24-1tb", "M5 · GPU 10 nuclee · 24 GB · 1 TB", 9449.99)])]:
    P(slug=f"macbook-air-{inch}-m5", name=f"MacBook Air {inch}\" M5", category="mac", group="Laptopuri",
      featured=(inch == 13), promo=(0.07 if inch == 13 else None),
      tagline="Subțire, silențios, cu cip M5 și 512 GB din start." if inch == 13 else "Ecran mare de 15\", șase difuzoare, aceeași greutate‑fulg.",
      description=[f"MacBook Air {inch}\" cu cip M5 este rapid pentru orice — de la prezentări la editare foto — și complet silențios, fără ventilator.",
                   "Până la 18 ore de autonomie, Wi‑Fi 7, cameră Center Stage de 12 MP și tastatură cu layout românesc."],
      highlights=[f"Ecran {inch}\" Liquid Retina", "Cip M5", "Până la 18 ore autonomie", "Design fără ventilator", "Tastatură RO cu Touch ID"],
      specs=MBA_SPECS(inch),
      in_box=[f"MacBook Air {inch}\"", "Adaptor de alimentare 40 W Dynamic", "Cablu USB‑C la MagSafe 3 (2 m)"],
      options=[{"key": "color", "label": "Culoare", "values": colors("skyblue", "silver", "starlight", "midnight")},
               {"key": "config", "label": "Configurație", "values": [opt(k, l) for k, l, _ in cfgs]}],
      ron={k: v for k, _, v in cfgs},
      mpn=({("midnight", "m5-8-16-512"): "MDHE4", ("skyblue", "m5-8-16-512"): "MDHH4", ("starlight", "m5-8-16-512"): "MDHA4",
            ("silver", "m5-8-16-512"): "MDH74", ("skyblue", "m5-10-16-1tb"): "MDHJ4", ("silver", "m5-10-16-1tb"): "MDH84",
            ("starlight", "m5-10-16-1tb"): "MDHC4", ("midnight", "m5-10-24-1tb"): "MDHK4"} if inch == 13 else
           {("starlight", "m5-10-16-512"): "MDVD4", ("skyblue", "m5-10-16-512"): "MDVQ4",
            ("midnight", "m5-10-16-512"): "MDVH4", ("silver", "m5-10-16-512"): "MDV94"}),
      mpn_suffix="RO/A",
      images={c: [f"mba{inch}-{c}-select-202503"] for c in ["skyblue", "silver", "starlight", "midnight"]})

MBP_CFG = {
    14: [("m5-16-1tb", "M5 · CPU 10 / GPU 10 · 16 GB · 1 TB", 2199),
         ("m5pro15-24-1tb", "M5 Pro · CPU 15 / GPU 16 · 24 GB · 1 TB", 2899),
         ("m5pro18-24-1tb", "M5 Pro · CPU 18 / GPU 20 · 24 GB · 1 TB", 3149),
         ("m5max32-36-2tb", "M5 Max · CPU 18 / GPU 32 · 36 GB · 2 TB", 4799)],
    16: [("m5pro18-24-1tb", "M5 Pro · CPU 18 / GPU 20 · 24 GB · 1 TB", 3399),
         ("m5max32-48-2tb", "M5 Max · CPU 18 / GPU 32 · 48 GB · 2 TB", 5099),
         ("m5max40-48-2tb", "M5 Max · CPU 18 / GPU 40 · 48 GB · 2 TB", 5699)],
}
for inch in (14, 16):
    P(slug=f"macbook-pro-{inch}", name=f"MacBook Pro {inch}\"", category="mac", group="Laptopuri",
      tagline="M5, M5 Pro sau M5 Max. Ecran Liquid Retina XDR." if inch == 14 else "Cel mai mare ecran XDR și cele mai puternice cipuri pentru profesioniști.",
      description=[f"MacBook Pro {inch}\" cu cipuri M5, M5 Pro sau M5 Max este construit pentru editare video 8K, dezvoltare software și modele AI locale.",
                   "Ecranul Liquid Retina XDR de până la 1600 nits în HDR, Thunderbolt 5 pe modelele Pro/Max, HDMI, slot SDXC și până la 24 de ore de autonomie."],
      highlights=[f"Ecran {inch}\" Liquid Retina XDR", "Cipuri M5 / M5 Pro / M5 Max", "Thunderbolt 5 (Pro/Max)", "Până la 24 de ore autonomie", "Opțional: ecran cu nanotextură"],
      specs=[("Ecran", [("Diagonală", f"{'14,2' if inch == 14 else '16,2'}\" Liquid Retina XDR, ProMotion 120 Hz"),
                        ("Luminozitate", "1000 nits SDR (exterior), 1600 nits HDR")]),
             ("Performanță", [("Cip", "M5, M5 Pro sau M5 Max (în funcție de configurație)")]),
             ("Porturi", [("Conectori", "3× Thunderbolt, HDMI, SDXC, MagSafe 3, jack 3,5 mm")]),
             ("Autonomie", [("Streaming video", "până la 24 de ore")]),
             ("Design", [("Greutate", "de la 1,55 kg" if inch == 14 else "de la 2,14 kg"),
                         ("Tastatură", "Magic Keyboard cu Touch ID, layout RO")])],
      in_box=[f"MacBook Pro {inch}\"", "Adaptor de alimentare USB‑C", "Cablu USB‑C la MagSafe 3 (2 m)"],
      options=[{"key": "color", "label": "Culoare", "values": colors("spaceblack", "silver")},
               {"key": "config", "label": "Configurație", "values": [opt(k, l) for k, l, _ in MBP_CFG[inch]]},
               {"key": "display", "label": "Ecran", "values": [opt("standard", "Sticlă standard"),
                                                                opt("nano", "Nanotextură", "reflexii minime")]}],
      eur={k: v for k, _, v in MBP_CFG[inch]}, display_extra_eur=165,
      images={c: [f"mbp{inch}-{c}-cto-hero-202410"] for c in ["spaceblack", "silver"]})

P(slug="imac-24-m4", name="iMac 24\" M4", category="mac", group="Desktop",
  tagline="Totul-în-unu. Șapte culori. Cip M4.",
  description=["iMac cu cip M4 combină un ecran Retina 4.5K de 24\" cu un design de doar 11,5 mm, în șapte culori.",
               "Camera Center Stage de 12 MP, șase difuzoare și accesoriile asortate cu Touch ID fac din iMac centrul casei tale."],
  highlights=["Ecran Retina 4.5K 24\"", "Cip M4", "Cameră 12 MP Center Stage", "Accesorii asortate în culori", "Design de 11,5 mm"],
  specs=[("Ecran", [("Diagonală", "24\" Retina 4.5K, 4480×2520, 500 nits")]),
         ("Performanță", [("Cip", "Apple M4 (CPU 8 sau 10 nuclee)")]),
         ("Porturi", [("Conectori", "2 sau 4× Thunderbolt / USB 4")]),
         ("Design", [("Grosime", "11,5 mm"), ("Accesorii", "Magic Keyboard cu Touch ID (layout RO), Magic Mouse")])],
  in_box=["iMac", "Magic Keyboard cu Touch ID", "Magic Mouse", "Adaptor de alimentare 143 W", "Cablu USB‑C"],
  options=[{"key": "color", "label": "Culoare", "values": colors("blue", "purple", "pink", "orange", "yellow", "green", "silver")},
           {"key": "config", "label": "Configurație", "values": [
               opt("m4-8-16-256", "M4 · CPU 8 / GPU 8 · 16 GB · 256 GB"),
               opt("m4-10-16-512", "M4 · CPU 10 / GPU 10 · 16 GB · 512 GB")]}],
  eur={"m4-8-16-256": 1799, "m4-10-16-512": 1999},
  mpn={("green", "m4-8-16-256"): "MWUE3", ("blue", "m4-8-16-256"): "MWUF3", ("silver", "m4-8-16-256"): "MWUC3",
       ("blue", "m4-10-16-512"): "MWV13", ("green", "m4-10-16-512"): "MWUY3", ("pink", "m4-8-16-256"): "MWUG3",
       ("pink", "m4-10-16-512"): "MWV43", ("silver", "m4-10-16-512"): "MWUU3"}, mpn_suffix="RO/A",
  images={c: [f"imac-touch-id-{c}-selection-hero-202410"] for c in ["blue", "purple", "pink", "orange", "yellow", "green", "silver"]})

P(slug="mac-mini", name="Mac mini", category="mac", group="Desktop", badge="Nou",
  tagline="Cip M6 sau M5 Pro într-un cub de 12,7 cm.",
  description=["Noul Mac mini are cipul M6 — primul cip Apple de 2 nm — sau M5 Pro, cu Thunderbolt 5 și Ethernet 2.5 Gb.",
               "Porturi USB‑C și jack audio în față, HDMI și Thunderbolt în spate, Wi‑Fi 7 — totul într-o carcasă de 12,7 × 12,7 cm."],
  highlights=["Cip M6 (2 nm) sau M5 Pro", "Wi‑Fi 7, Ethernet 2.5 Gb", "Porturi frontale USB‑C", "12,7 × 12,7 × 5 cm"],
  specs=[("Performanță", [("Cip", "M6 (CPU 12 / GPU 12) sau M5 Pro (CPU 15–18 / GPU 16–20)")]),
         ("Porturi", [("Față", "2× USB‑C (10 Gb/s), jack 3,5 mm"), ("Spate", "3× Thunderbolt, HDMI, Ethernet 2.5 Gb")]),
         ("Wireless", [("Conectivitate", "Wi‑Fi 7, Bluetooth 6, Thread")]),
         ("Design", [("Dimensiuni", "12,7 × 12,7 × 5,0 cm"), ("Greutate", "0,67 kg (M6) / 0,73 kg (M5 Pro)")])],
  in_box=["Mac mini", "Cablu de alimentare"],
  options=[{"key": "config", "label": "Configurație", "values": [
      opt("m6-16-256", "M6 · 16 GB · 256 GB"), opt("m6-16-512", "M6 · 16 GB · 512 GB"),
      opt("m6-24-512", "M6 · 24 GB · 512 GB"), opt("m5pro-24-512", "M5 Pro · CPU 15 / GPU 16 · 24 GB · 512 GB")]}],
  eur={"m6-16-256": 1049, "m6-16-512": 1299, "m6-24-512": 1549, "m5pro-24-512": 1999},
  mpn={("m6-16-256",): "MHQK4", ("m5pro-24-512",): "MHQN4"}, mpn_suffix="ZD/A",
  images={"-": ["@bg:mac-mini-chip-unselect-202608-gallery-1", "@bg:mac-mini-chip-unselect-202608-gallery-2"]})

P(slug="mac-studio", name="Mac Studio", category="mac", group="Desktop", badge="Nou",
  tagline="M5 Max sau M5 Ultra. Până la 512 GB memorie unificată.",
  description=["Mac Studio cu M5 Max sau M5 Ultra este stația de lucru compactă pentru randare 3D, post‑producție și modele AI mari.",
               "Thunderbolt 5, Ethernet 10 Gb, slot SDXC și suport pentru până la opt monitoare Pro Display."],
  highlights=["M5 Max sau M5 Ultra", "Până la 512 GB memorie unificată", "Thunderbolt 5", "Ethernet 10 Gb"],
  specs=[("Performanță", [("Cip", "M5 Max (CPU 18 / GPU 32–40) sau M5 Ultra (CPU 30–36 / GPU 64–80)")]),
         ("Porturi", [("Față", "2× USB‑C / Thunderbolt 5, SDXC"), ("Spate", "4× Thunderbolt 5, 2× USB‑A, HDMI, Ethernet 10 Gb, jack 3,5 mm")])],
  in_box=["Mac Studio", "Cablu de alimentare"],
  options=[{"key": "config", "label": "Configurație", "values": [
      opt("m5max32-36-512", "M5 Max · CPU 18 / GPU 32 · 36 GB · 512 GB"),
      opt("m5max40-64-1tb", "M5 Max · CPU 18 / GPU 40 · 64 GB · 1 TB"),
      opt("m5ultra64-96-1tb", "M5 Ultra · CPU 30 / GPU 64 · 96 GB · 1 TB"),
      opt("m5ultra80-256-2tb", "M5 Ultra · CPU 36 / GPU 80 · 256 GB · 2 TB")]}],
  eur={"m5max32-36-512": 2999, "m5max40-64-1tb": 3659, "m5ultra64-96-1tb": 6599, "m5ultra80-256-2tb": 8029},
  mpn={("m5max32-36-512",): "MHL64", ("m5ultra64-96-1tb",): "MHL74"}, mpn_suffix="ZD/A",
  images={"-": ["@bg:mac-studio-chip-unselect-202608-gallery-1", "@bg:mac-studio-chip-unselect-202608-gallery-2"]})

P(slug="studio-display", name="Studio Display", category="mac", group="Monitoare",
  tagline="Monitor 5K de 27\". Cameră 12 MP, șase difuzoare.",
  description=["Studio Display are un ecran Retina 5K de 27\" cu 600 nits, cameră Center Stage de 12 MP și un sistem audio cu șase difuzoare.",
               "Un singur cablu Thunderbolt alimentează și conectează Mac-ul tău."],
  highlights=["Retina 5K 27\"", "Cameră 12 MP Center Stage", "Șase difuzoare, audio spațial", "Opțional nanotextură"],
  specs=[("Ecran", [("Diagonală", "27\" Retina 5K, 5120×2880, 600 nits")]),
         ("Conectivitate", [("Porturi", "Thunderbolt, 3× USB‑C")])],
  in_box=["Studio Display", "Cablu Thunderbolt (1 m)", "Lavetă de curățare"],
  options=[{"key": "glass", "label": "Sticlă", "values": [opt("standard", "Standard"), opt("nano", "Nanotextură")]},
           {"key": "stand", "label": "Suport", "values": [opt("tilt", "Înclinare reglabilă"), opt("height", "Înclinare și înălțime"),
                                                          opt("vesa", "Adaptor VESA")]}],
  eur={("standard", "tilt"): 1699, ("standard", "height"): 2099, ("standard", "vesa"): 1699,
       ("nano", "tilt"): 1999, ("nano", "height"): 2399, ("nano", "vesa"): 1999},
  mpn={("standard", "tilt"): "MFEX4", ("standard", "height"): "MFEW4", ("standard", "vesa"): "MFEY4",
       ("nano", "tilt"): "MFF14", ("nano", "height"): "MFF04", ("nano", "vesa"): "MFF24"}, mpn_suffix="FD/A",
  images={"-": ["MFEX4", "MFEW4"]})

P(slug="studio-display-xdr", name="Studio Display XDR", category="mac", group="Monitoare", badge="Nou",
  tagline="Mini‑LED, 1600 nits HDR, pentru profesioniștii imaginii.",
  description=["Studio Display XDR aduce contrast extrem și luminozitate HDR pentru grading video și fotografie profesională.",
               "Calibrare din fabrică, mod de referință și conectare prin Thunderbolt 5."],
  highlights=["Mini‑LED, HDR 1600 nits", "Moduri de referință", "Thunderbolt 5"],
  specs=[("Ecran", [("Tehnologie", "Mini‑LED, HDR, gamă P3")])],
  in_box=["Studio Display XDR", "Cablu Thunderbolt", "Lavetă de curățare"],
  options=[{"key": "glass", "label": "Sticlă", "values": [opt("standard", "Standard"), opt("nano", "Nanotextură")]},
           {"key": "stand", "label": "Suport", "values": [opt("height", "Înclinare și înălțime"), opt("vesa", "Adaptor VESA")]}],
  eur={("standard", "height"): 3499, ("nano", "height"): 3799, ("standard", "vesa"): 3099, ("nano", "vesa"): 3399},
  mpn={("standard", "height"): "MFEL4", ("nano", "height"): "MFEP4", ("standard", "vesa"): "MFEN4", ("nano", "vesa"): "MFEQ4"},
  mpn_suffix="FD/A", images={"-": ["MFEL4", "MFEP4"]})

# ================================================================= iPad
for inch in (11, 13):
    P(slug=f"ipad-pro-{inch}-m5", name=f"iPad Pro {inch}\" M5", category="ipad", group="iPad Pro", featured=(inch == 13),
      tagline="Ecran Ultra Retina XDR tandem OLED. Cip M5.",
      description=[f"iPad Pro {inch}\" cu cip M5 este incredibil de subțire și are ecran Ultra Retina XDR tandem OLED de până la 1600 nits.",
                   "Compatibil cu Apple Pencil Pro și Magic Keyboard pentru iPad Pro; opțional sticlă cu nanotextură pentru modelele de 1 TB și 2 TB."],
      highlights=[f"Ecran {inch}\" Ultra Retina XDR (OLED tandem)", "Cip M5", "Apple Pencil Pro", "Wi‑Fi 7 · 5G opțional", "Face ID"],
      specs=[("Ecran", [("Diagonală", f"{inch}\" Ultra Retina XDR, ProMotion 120 Hz"), ("Luminozitate", "1000 nits SDR, 1600 nits HDR")]),
             ("Performanță", [("Cip", "Apple M5")]),
             ("Conectivitate", [("Port", "Thunderbolt / USB 4"), ("Wireless", "Wi‑Fi 7, opțional 5G")])],
      in_box=["iPad Pro", "Cablu USB‑C (1 m)"],
      options=[{"key": "color", "label": "Culoare", "values": colors("spaceblack", "silver")},
               {"key": "storage", "label": "Stocare", "values": storage("256gb", "512gb", "1tb", "2tb")},
               {"key": "glass", "label": "Sticlă", "values": [opt("standard", "Sticlă standard"),
                                                              opt("nano", "Nanotextură", "reflexii minime · 1 TB și 2 TB")]},
               {"key": "conn", "label": "Conectivitate", "values": [opt("wifi", "Wi‑Fi"), opt("cell", "Wi‑Fi + Cellular")]}],
      eur_from="de-ipad-pro", eur_size=f"{inch}-inch",
      images={c: [f"ipad-pro-{inch}-select-wifi-{c}-202405"] for c in ["spaceblack", "silver"]})

for inch in (11, 13):
    P(slug=f"ipad-air-{inch}-m4", name=f"iPad Air {inch}\" M4", category="ipad", group="iPad Air", badge="Nou",
      tagline="Cip M4, 12 GB memorie, modem C1X.",
      description=[f"iPad Air {inch}\" cu cip M4 are 12 GB memorie, modem Apple C1X și cip wireless N1 pentru conexiuni mai rapide.",
                   "Funcționează cu Apple Pencil Pro și noul Magic Keyboard pentru iPad Air."],
      highlights=[f"Ecran {inch}\" Liquid Retina", "Cip M4 · 12 GB memorie", "Apple Pencil Pro", "Touch ID"],
      specs=[("Ecran", [("Diagonală", f"{inch}\" Liquid Retina")]),
             ("Performanță", [("Cip", "Apple M4"), ("Memorie", "12 GB")]),
             ("Conectivitate", [("Wireless", "Wi‑Fi 7 (N1), opțional 5G (C1X)")])],
      in_box=["iPad Air", "Cablu USB‑C (1 m)"],
      options=[{"key": "color", "label": "Culoare", "values": colors("spacegray", "blue", "purple", "starlight")},
               {"key": "storage", "label": "Stocare", "values": storage("128gb", "256gb", "512gb", "1tb")},
               {"key": "conn", "label": "Conectivitate", "values": [opt("wifi", "Wi‑Fi"), opt("cell", "Wi‑Fi + Cellular")]}],
      eur_from="de-ipad-air", eur_size=f"{inch}-inch",
      images={c: [f"ipad-air-select-{inch}in-wifi-{c}-202405"] for c in ["spacegray", "blue", "purple", "starlight"]})

P(slug="ipad-a16", name="iPad 11\" A16", category="ipad", group="iPad",
  tagline="iPad-ul colorat pentru toată familia.",
  description=["iPad cu cip A16 și ecran Liquid Retina de 11\" — ideal pentru școală, filme și note de mână cu Apple Pencil (USB‑C).",
               "Patru culori, Touch ID în butonul de sus și 128 GB stocare de bază."],
  highlights=["Ecran 11\" Liquid Retina", "Cip A16", "128 GB din start", "Touch ID"],
  specs=[("Ecran", [("Diagonală", "11\" Liquid Retina")]), ("Performanță", [("Cip", "A16")])],
  in_box=["iPad", "Cablu USB‑C (1 m)"],
  options=[{"key": "color", "label": "Culoare", "values": colors("blue", "pink", "yellow", "silver")},
           {"key": "storage", "label": "Stocare", "values": storage("128gb", "256gb", "512gb")},
           {"key": "conn", "label": "Conectivitate", "values": [opt("wifi", "Wi‑Fi"), opt("cell", "Wi‑Fi + Cellular")]}],
  eur_from="de-ipad", promo=0.05,
  images={c: [f"ipad-2022-hero-{c}-wifi-select"] for c in ["blue", "pink", "yellow", "silver"]})

P(slug="ipad-mini", name="iPad mini", category="ipad", group="iPad mini",
  tagline="Tot iPad-ul, într-o singură mână.",
  description=["iPad mini cu cip A17 Pro și ecran Liquid Retina de 8,3\" — compatibil cu Apple Pencil Pro.",
               "Perfect pentru citit, notițe, navigație și jocuri, oriunde te-ai afla."],
  highlights=["Ecran 8,3\" Liquid Retina", "Cip A17 Pro", "Apple Pencil Pro", "Touch ID"],
  specs=[("Ecran", [("Diagonală", "8,3\" Liquid Retina")]), ("Performanță", [("Cip", "A17 Pro")])],
  in_box=["iPad mini", "Cablu USB‑C (1 m)"],
  options=[{"key": "color", "label": "Culoare", "values": colors("spacegray", "blue", "purple", "starlight")},
           {"key": "storage", "label": "Stocare", "values": storage("128gb", "256gb", "512gb")},
           {"key": "conn", "label": "Conectivitate", "values": [opt("wifi", "Wi‑Fi"), opt("cell", "Wi‑Fi + Cellular")]}],
  eur_from="de-ipad-mini",
  images={c: [f"ipad-mini-select-wifi-{c}-202410"] for c in ["spacegray", "blue", "purple", "starlight"]})

# ================================================================= Watch
S12_COLORS = {"aluminum": ["spacegray", "black", "darkbronze", "lightgold"],
              "titanium": ["natural", "radiantgold"], "ceramic": ["pearlwhite", "nightblue"]}
S12_BAND = {"spacegray": "MJUL4ref", "black": "MJUL4ref", "darkbronze": "MJUL4ref", "lightgold": "MJUC4ref",
            "natural": "MJNH4", "radiantgold": "MJNG4", "pearlwhite": "MJUC4ref", "nightblue": "MY7N3ref"}
S12_CASE = {"spacegray": "aluminum-spacegray-cell", "black": "aluminum-black-nc", "darkbronze": "aluminum-darkbronze-cell",
            "lightgold": "aluminum-lightgold-nc", "natural": "titanium-natural-cell", "radiantgold": "titanium-radiantgold-cell",
            "pearlwhite": "ceramic-pearlwhite-cell", "nightblue": "ceramic-nightblue-cell"}


def s12_img(c):
    case = S12_CASE[c]
    # fața ecranului: pentru ceramică numele include și conectivitatea („-cell”)
    face = case if case.startswith("ceramic") else case.rsplit("-", 1)[0]
    return [f"@watch:{S12_BAND[c]}_VW_34FR|watch-case-46-{case}-s12_VW_34FR|watch-face-46-{face}-s12_VW_34FR",
            f"@watch:{S12_BAND[c]}_VW_34FR|watch-case-42-{case}-s12_VW_34FR|watch-face-42-{face}-s12_VW_34FR"]


WATCH_SPECS = [("Ecran", [("Tip", "Always‑On Retina LTPO3, până la 2000 nits")]),
               ("Performanță", [("Cip", "S11 SiP, 64 GB stocare")]),
               ("Sănătate", [("Senzori", "ECG, ritm cardiac, oxigen în sânge, temperatură"),
                             ("Notificări", "Hipertensiune, apnee în somn, ritm neregulat")]),
               ("Autonomie", [("Utilizare normală", "până la 24 de ore (38 h în Low Power)")]),
               ("Rezistență", [("Apă / praf", "WR50, IP6X")])]

P(slug="apple-watch-series-12", name="Apple Watch Series 12", category="watch", group="Series 12", badge="Nou", featured=True,
  tagline="Mai luminos, mai inteligent, cu noi senzori de sănătate.",
  description=["Apple Watch Series 12 are cipul S11, ecran Always‑On de până la 2000 nits și notificări pentru hipertensiune și apnee în somn.",
               "Alege carcasă din aluminiu, titan sau — nou — ceramică, în 42 sau 46 mm. Fiecare ceas vine cu o curea sport asortată."],
  highlights=["Cip S11", "Ecran până la 2000 nits", "Notificări hipertensiune & apnee", "Aluminiu, titan sau ceramică", "5G opțional"],
  specs=WATCH_SPECS,
  in_box=["Apple Watch", "Curea (mărimea se alege la livrare)", "Încărcător magnetic rapid USB‑C (1 m)"],
  options=[{"key": "material", "label": "Carcasă", "values": [opt("aluminum", "Aluminiu"), opt("titanium", "Titan", "doar GPS + Cellular"),
                                                              opt("ceramic", "Ceramică", "doar GPS + Cellular")]},
           {"key": "color", "label": "Culoare", "values": colors("spacegray", "black", "darkbronze", "lightgold", "natural", "radiantgold", "pearlwhite", "nightblue")},
           {"key": "size", "label": "Mărime", "values": [opt("42mm", "42 mm"), opt("46mm", "46 mm")]},
           {"key": "conn", "label": "Conectivitate", "values": [opt("gps", "GPS"), opt("cell", "GPS + Cellular")]}],
  watch_matrix="s12",
  images={c: s12_img(c) for c in S12_BAND})

P(slug="apple-watch-ultra-4", name="Apple Watch Ultra 4", category="watch", group="Ultra", badge="Nou",
  tagline="Titan, 3000 nits, 50 de ore. Pentru aventuri reale.",
  description=["Apple Watch Ultra 4 are carcasă din titan de 49 mm, ecran de 3000 nits și până la 50 de ore de autonomie (84 h în Low Power).",
               "Comunicare prin satelit, GPS dual-frecvență, scufundări recreative până la 40 m și 5G inclus."],
  highlights=["Titan 49 mm", "Ecran 3000 nits", "Până la 50 de ore", "Satelit + 5G", "Scufundări până la 40 m"],
  specs=[("Ecran", [("Tip", "Always‑On Retina, 3000 nits, safir plat")]), ("Performanță", [("Cip", "S11")]),
         ("Autonomie", [("Utilizare normală", "până la 50 de ore (84 h în Low Power)")]),
         ("Rezistență", [("Apă", "WR100, scufundări recreative 40 m, IP6X")]),
         ("Conectivitate", [("Rețele", "5G RedCap, LTE, satelit, GPS dual-frecvență")])],
  in_box=["Apple Watch Ultra 4", "Curea", "Încărcător magnetic rapid USB‑C (1 m)"],
  options=[{"key": "color", "label": "Culoare titan", "values": colors("natural", "black")}],
  eur_flat=899, mpn={("natural",): "MJCR4", ("black",): "MJCT4"}, mpn_suffix="QF/A",
  images={"natural": ["@watch:MKF34ref_VW_34FR|watch-case-49-titanium-natural-ultra4_VW_34FR|watch-face-49-ultra4_VW_34FR",
                      "@watch:MKAH4ref_VW_34FR|watch-case-49-titanium-natural-ultra4_VW_34FR|watch-face-49-ultra4_VW_34FR"],
          "black": ["@watch:MKAY4_VW_34FR|watch-case-49-titanium-black-ultra4_VW_34FR|watch-face-49-ultra4_VW_34FR",
                    "@watch:MK0F4ref_VW_34FR|watch-case-49-titanium-black-ultra4_VW_34FR|watch-face-49-ultra4_VW_34FR"]})

P(slug="apple-watch-se-3", name="Apple Watch SE 3", category="watch", group="SE",
  tagline="Esențialul Apple Watch, la un preț excelent.",
  description=["Apple Watch SE 3 îți urmărește activitatea, somnul și ritmul cardiac, cu detecția accidentelor și a căzăturilor.",
               "Ecran Always‑On, încărcare rapidă și senzor de temperatură — în 40 sau 44 mm."],
  highlights=["Ecran Always‑On", "Detecție cădere & accident", "Senzor de temperatură", "Încărcare rapidă"],
  specs=[("Ecran", [("Tip", "Always‑On Retina")]), ("Sănătate", [("Funcții", "Puls, somn, temperatură, apnee în somn")]),
         ("Rezistență", [("Apă", "WR50")])],
  in_box=["Apple Watch SE 3", "Curea sport", "Încărcător magnetic rapid USB‑C (1 m)"],
  options=[{"key": "color", "label": "Culoare", "values": colors("midnight", "starlight")},
           {"key": "size", "label": "Mărime", "values": [opt("40mm", "40 mm"), opt("44mm", "44 mm")]},
           {"key": "conn", "label": "Conectivitate", "values": [opt("gps", "GPS"), opt("cell", "GPS + Cellular")]}],
  watch_matrix="se",
  images={"midnight": ["@watch:MJUL4ref_VW_34FR|watch-case-44-aluminum-midnight-nc-se3_VW_34FR|watch-face-44-aluminum-midnight-se3_VW_34FR"],
          "starlight": ["@watch:MJUC4ref_VW_34FR|watch-case-44-aluminum-starlight-nc-se3_VW_34FR|watch-face-44-aluminum-starlight-se3_VW_34FR"]})

# ================================================================= AirPods / audio
P(slug="airpods-pro-3", name="AirPods Pro 3", category="airpods", group="AirPods", featured=True,
  tagline="Cea mai bună anulare a zgomotului din AirPods. Puls în timpul antrenamentelor.",
  description=["AirPods Pro 3 au anulare activă a zgomotului de nouă generație, senzor de ritm cardiac pentru antrenamente și Live Translation.",
               "Până la 8 ore de ascultare cu ANC, IP57 și funcții de sănătate auditivă: test auditiv, aparat auditiv și protecție."],
  highlights=["ANC de nouă generație", "Senzor de puls", "Până la 8 h cu ANC", "Live Translation", "Carcasă MagSafe USB‑C"],
  specs=[("Audio", [("Cip", "H2"), ("Funcții", "ANC, Adaptive Audio, audio spațial personalizat")]),
         ("Autonomie", [("Căști", "până la 8 ore cu ANC"), ("Cu carcasa", "până la 24 de ore")]),
         ("Rezistență", [("Clasă", "IP57")])],
  in_box=["AirPods Pro 3", "Carcasă de încărcare MagSafe (USB‑C)", "Dopuri de silicon (XXS, XS, S, M, L)", "Documentație"],
  options=[], eur_flat=249, mpn_single="MFHP4ZM/A",
  images={"-": ["airpods-pro-3-hero-select-202509", "MFHP4"]})

P(slug="airpods-5", name="AirPods 5", category="airpods", group="AirPods", badge="Nou",
  tagline="Acum cu anulare activă a zgomotului în modelul de bază.",
  description=["AirPods 5 au cip H2, anulare activă a zgomotului, Adaptive Audio și Live Translation, într-un design open-fit confortabil.",
               "Alege carcasa USB‑C sau carcasa wireless cu difuzor pentru Găsire și încărcare Qi."],
  highlights=["ANC", "Cip H2", "Audio spațial personalizat", "IP57", "Carcasă USB‑C sau wireless"],
  specs=[("Audio", [("Cip", "H2"), ("Funcții", "ANC, Transparență, Adaptive Audio")]),
         ("Autonomie", [("Cu ANC", "4 h (USB‑C) / 5 h (wireless)"), ("Total cu carcasa", "20 h / 22 h")]),
         ("Rezistență", [("Clasă", "IP57")])],
  in_box=["AirPods 5", "Carcasă de încărcare", "Documentație"],
  options=[{"key": "case", "label": "Carcasă", "values": [opt("usbc", "Carcasă USB‑C"), opt("wireless", "Carcasă wireless", "Qi, difuzor Găsire")]}],
  eur={"usbc": 149, "wireless": 169}, mpn={("usbc",): "MKFW4", ("wireless",): "MKFT4"}, mpn_suffix="ZM/A",
  images_by_option={"usbc": ["airpods-5-select-202609"], "wireless": ["airpods-5-wcc-select-202609"]})

P(slug="airpods-max-2", name="AirPods Max 2", category="airpods", group="AirPods",
  tagline="Audio de înaltă fidelitate, acum cu cip H2.",
  description=["AirPods Max 2 combină sunetul de înaltă fidelitate cu ANC de nouă generație, Adaptive Audio și Live Translation.",
               "Coroana Digitală, pernițe din spumă cu memorie și până la 20 de ore de autonomie."],
  highlights=["Cip H2", "ANC & Adaptive Audio", "Până la 20 de ore", "Cinci culori"],
  specs=[("Audio", [("Cip", "H2"), ("Funcții", "ANC, Transparență, audio spațial, Loud Sound Reduction")]),
         ("Autonomie", [("Ascultare", "până la 20 de ore cu ANC")])],
  in_box=["AirPods Max 2", "Smart Case", "Cablu USB‑C"],
  options=[{"key": "color", "label": "Culoare", "values": colors("midnight", "starlight", "blue", "purple", "orange")}],
  eur_flat=579, mpn={("midnight",): "MHWK4", ("starlight",): "MHWL4", ("blue",): "MHWM4", ("orange",): "MHWN4", ("purple",): "MHWP4"},
  mpn_suffix="ZM/A",
  images={c: [f"airpods-max-select-202409-{c}_FV1"] for c in ["midnight", "starlight", "blue", "purple", "orange"]})

# ================================================================= TV & Home
P(slug="apple-tv-4k", name="Apple TV 4K", category="tv-home", group="Apple TV",
  tagline="Filme 4K HDR, Dolby Atmos și centrul casei inteligente.",
  description=["Apple TV 4K cu cip A15 Bionic aduce Apple TV+, Netflix, HBO Max și Disney+ în 4K Dolby Vision, cu sunet Dolby Atmos.",
               "Funcționează ca hub pentru Casa (HomeKit, Matter, Thread) și vine cu telecomanda Siri Remote cu USB‑C."],
  highlights=["4K Dolby Vision · HDR10+", "Dolby Atmos", "Siri Remote USB‑C", "Hub Matter & Thread"],
  specs=[("Video", [("Format", "4K HDR, Dolby Vision, HDR10+")]), ("Performanță", [("Cip", "A15 Bionic")])],
  in_box=["Apple TV 4K", "Siri Remote", "Cablu USB‑C", "Cablu de alimentare"],
  options=[{"key": "model", "label": "Model", "values": [opt("wifi64", "Wi‑Fi · 64 GB"), opt("eth128", "Wi‑Fi + Ethernet · 128 GB", "Thread")]}],
  eur={"wifi64": 229, "eth128": 299}, mpn={("wifi64",): "MN873", ("eth128",): "MN893"}, mpn_suffix="FD/A",
  images={"-": ["apple-tv-4k-hero-select-202210"]})

P(slug="homepod", name="HomePod", category="tv-home", group="HomePod",
  tagline="Sunet spațial care umple camera.",
  description=["HomePod oferă audio de înaltă fidelitate, cu woofer de excursie mare și cinci tweetere, calibrat automat pentru camera ta.",
               "Senzori de temperatură și umiditate și control complet al casei inteligente."],
  highlights=["Audio computațional", "Audio spațial", "Hub Matter & Thread", "Siri"],
  specs=[("Audio", [("Difuzoare", "Woofer de 4\", 5 tweetere cu formare de fascicul")])],
  in_box=["HomePod", "Cablu de alimentare"],
  options=[{"key": "color", "label": "Culoare", "values": colors("midnight", "white")}],
  eur_flat=399, mpn={("midnight",): "MDEW4", ("white",): "MDEY4"}, mpn_suffix="D/A",
  images={c: [f"homepod-select-{c}-202210"] for c in ["midnight", "white"]})

P(slug="homepod-mini", name="HomePod mini", category="tv-home", group="HomePod",
  tagline="Sunet surprinzător de mare, în cinci culori.",
  description=["HomePod mini umple camera cu sunet de 360°, controlează casa inteligentă și se poate asocia în stereo.",
               "Intercom, Handoff cu iPhone și senzor de temperatură."],
  highlights=["Sunet 360°", "Cinci culori", "Hub Matter & Thread", "Intercom"],
  specs=[("Audio", [("Sunet", "360°, audio computațional")])],
  in_box=["HomePod mini", "Adaptor 20 W"],
  options=[{"key": "color", "label": "Culoare", "values": colors("midnight", "white", "blue", "orange", "yellow")}],
  eur_flat=139, mpn={("blue",): "MJ2C3", ("orange",): "MJ2D3", ("midnight",): "MTJT3", ("white",): "MY5H2", ("yellow",): "MJ2E3"},
  mpn_suffix="D/A",
  images={"midnight": ["homepod-mini-select-midnight-202407_FV1"],
          **{c: [f"homepod-mini-select-{c}-202110_FV1"] for c in ["white", "blue", "orange", "yellow"]}})

P(slug="airtag", name="AirTag (a doua generație)", category="tv-home", group="AirTag", badge="Nou",
  tagline="Găsește-ți cheile, bagajul, bicicleta — cu precizie.",
  description=["Noul AirTag are cip Ultra Wideband de a doua generație, cu rază de Găsire precisă mai mare și un difuzor mai puternic.",
               "Se configurează instant cu iPhone și funcționează prin rețeaua Găsire, cu protecție împotriva urmăririi nedorite."],
  highlights=["UWB gen. 2", "Difuzor mai puternic", "Rețeaua Găsire", "Baterie înlocuibilă"],
  specs=[("Tehnologie", [("Localizare", "Ultra Wideband gen. 2, Bluetooth, NFC")]), ("Rezistență", [("Clasă", "IP67")])],
  in_box=["AirTag", "Baterie CR2032 instalată"],
  options=[{"key": "pack", "label": "Pachet", "values": [opt("1", "1 bucată"), opt("4", "4 bucăți")]}],
  eur={"1": 35, "4": 119}, mpn={("1",): "MFE94", ("4",): "MFEA4"}, mpn_suffix="ZM/A",
  images_by_option={"1": ["airtag-1pack-select-202601_GEO_EMEA"], "4": ["airtag-4pack-select-202601_GEO_EMEA"]})

# ================================================================= Accesorii și Beats (din Apple Store, toate culorile)
import accessories_data as AD  # noqa: E402
from accessories_i18n import COLOR_RO, SIZE_RO, LENGTH_RO, CONNECTION_RO, FINISH_RO  # noqa: E402

ACC_OPT = [("model", "Model", "dimensionMerchModel"), ("case", "Carcasă", "dimensionCaseSize"), ("color", "Culoare", "dimensionColor"),
           ("finish", "Cataramă", "dimensionMetalFinish"), ("size", "Mărime", None), ("length", "Lungime", "dimensionLength"),
           ("conn", "Conector", "dimensionConnection"), ("layout", "Tastatură", None)]
SOLO_NOTE = "Solo Loop nu are cataramă: alege mărimea după circumferința încheieturii (ghidul Apple de mărimi o măsoară în 1 minut)."
UNKNOWN_COLORS = set()


RAW_LABELS = {"extralarge": "XL", "s_m": "S/M", "m_l": "M/L", "small": "S", "medium": "M", "large": "L",
              "0_2meters": "20 cm", "1_5meters": "1,5 m", "1meter": "1 m", "2meters": "2 m", "3meters": "3 m"}


def _acc_label(key, raw, labels):
    if raw in RAW_LABELS:
        return RAW_LABELS[raw]
    if key == "model":
        return raw
    if key == "case":
        return AD.CASE_SIZE[raw][0]
    if key == "layout":
        return {"ro": "Română", "en": "Engleză (internațională)"}[raw]
    if key == "size":
        lab = AD.clean(labels.get("dimensionbandsize", {}).get(raw) or labels.get("dimensionProductsize", {}).get(raw) or raw)
        return AD.EAR_TIP.get(lab) or SIZE_RO.get(lab, lab)
    dim = dict((k, d) for k, _, d in ACC_OPT)[key]
    lab = AD.clean(labels.get(dim, {}).get(raw, raw))
    table = {"color": COLOR_RO, "length": LENGTH_RO, "conn": CONNECTION_RO, "finish": FINISH_RO}[key]
    if key == "color" and lab not in COLOR_RO:
        UNKNOWN_COLORS.add(lab)
    return table.get(lab, lab)


def _acc_order(key, values, labels, seen):
    if key == "model":
        return sorted(values, key=AD._model_order)
    if key == "case":
        return sorted(values)
    if key == "size" and all(v.isdigit() for v in values):
        return sorted(values, key=int)
    order = list(labels.get(dict((k, d) for k, _, d in ACC_OPT).get(key) or "dimensionbandsize", {})) or []
    if key == "size":
        order = list(labels.get("dimensionbandsize", {})) + list(labels.get("dimensionProductsize", {}))
    return sorted(values, key=lambda v: (order.index(v) if v in order else 99, seen.index(v)))


_ACC, ACC_SKIPPED = AD.build()
for _p in sorted(_ACC.values(), key=lambda p: (AD.GROUPS.index(p["group"]) if p["group"] in AD.GROUPS else -1, p["name"])):
    _rows = _p["rows"]
    for _v, _sw in _p["swatches"].items():  # unele familii au etichetele culorilor doar în mostre
        _p["labels"].setdefault("dimensionColor", {}).setdefault(_v, _sw["label"])
    # opțiunile cu o singură valoare nu ajută la alegere (ex. husa transparentă are o singură „culoare”)
    _keys = [k for k, _, _ in ACC_OPT if len({r[0].get(k) for r in _rows if k in r[0]}) > 1]
    for _r in _rows:
        _r[0] = {k: v for k, v in _r[0].items() if k in _keys}
    _seen = {k: [] for k in _keys}
    for _r in _rows:
        for k in _keys:
            if _r[0][k] not in _seen[k]:
                _seen[k].append(_r[0][k])
    _options = []
    for k, label, _ in ACC_OPT:
        if k not in _keys:
            continue
        vals = []
        for v in _acc_order(k, _seen[k], _p["labels"], _seen[k]):
            o = {"value": v, "label": _acc_label(k, v, _p["labels"])}
            if k == "case":
                o["hint"] = AD.CASE_SIZE[v][1]
            if k == "color":
                o["hex"] = None  # completat la build din mostra Apple
            vals.append(o)
        opt_ = {"key": k, "label": label, "values": vals}
        if k == "size" and "Solo Loop" in _p["name"]:
            opt_["note"] = SOLO_NOTE
        _options.append(opt_)
    # imagini: câte una pe culoare (fotografia din mostra Apple), altfel imaginea principală a produsului
    _first_part = _rows[0][2].split("/")[0][:5]
    if "color" in _keys:
        _imgs = {v: [_p["swatches"][v]["image"]] if v in _p["swatches"] else [(_p["gallery"] or [_first_part])[0]] for v in _seen["color"]}
    else:
        _imgs = {"-": [(_p["gallery"] or [_first_part])[0]]}
    _specs = []
    _models = [v["label"] for o in _options if o["key"] == "model" for v in o["values"]]
    if _p["group"] == "Huse iPhone" and (_models or " pentru " in _p["name"]):
        _specs.append(("Compatibilitate", [("Modele", ", ".join(_models) if _models else _p["name"].split(" pentru ", 1)[1])]))
    if any(o["key"] == "case" for o in _options):
        _specs.append(("Compatibilitate", [(AD.CASE_SIZE[v["value"]][0], AD.CASE_SIZE[v["value"]][1].replace("pentru ", "").capitalize())
                                           for o in _options if o["key"] == "case" for v in o["values"]]))
    _codes = sorted({r[2] for r in _rows})
    P(slug=_p["slug"], name=_p["name"], category=_p["cat"], group=_p["group"], tagline=_p["desc"],
      description=[_p["desc"]] + ([f"Produs original Apple{' / Beats' if 'Beats' in _p['name'] else ''}, cod{'uri' if len(_codes) > 1 else ''} {', '.join(c.split('/')[0] for c in _codes[:3])}{' …' if len(_codes) > 3 else ''}."]),
      highlights=[], specs=_specs, in_box=([_p["name"], "Cablu USB‑C", "Documentație"] if _p["cat"] == "airpods" else []),
      options=_options, promo=_p["promo"],
      variants_explicit=[{"options": r[0], "eur": r[1], "mpn": r[2], "sku": "LV-" + r[2].split("/")[0]} for r in _rows],
      images=_imgs, swatch_images={v: _p["swatches"][v]["image"] for v in _p["swatches"]},
      fill=0.78 if _p["cat"] == "airpods" else None)
