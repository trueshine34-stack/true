# -*- coding: utf-8 -*-
"""
Accesoriile Apple și Beats ale catalogului Livada, generate din tools/data/apple_accessories_de.json
(colectat cu tools/scrape_accessories.py din Apple Store Germania).

Fiecare familie Apple devine un produs cu toate combinațiile reale: model de iPhone / iPad, dimensiunea carcasei
Apple Watch, culoare, mărime, lungime, conector, limba tastaturii. Codurile MPN și prețurile sunt cele Apple,
convertite în lei cu aceiași multiplicatori ca restul catalogului.
"""
import json
import os
import re

from accessories_i18n import COLOR_RO, SIZE_RO, LENGTH_RO, CONNECTION_RO, FINISH_RO

RAW = os.path.join(os.path.dirname(__file__), "data", "apple_accessories_de.json")

# modele de iPhone vândute azi (Livada) + generația anterioară, pentru care Apple încă vinde huse
CURRENT_MODELS = ["iPhone 18 Pro", "iPhone 18 Pro Max", "iPhone Duo", "iPhone Air", "iPhone 17", "iPhone 17 Pro",
                  "iPhone 17 Pro Max", "iPhone 17e", "iPhone 16"]
EXCLUDE = [r"Herm[eè]s", r"Vision Pro", r"MagSafe 2 Power", r"^iPhone 1[45]\b", r"^iPhone 16e"]

# compatibilitatea oficială Apple (secțiunea „Kompatibilität” din paginile curelelor)
CASE_SIZE = {"40mm": ("40 mm", "pentru carcase de 38, 40 și 41 mm"), "42mm": ("42 mm", "pentru carcase de 38, 40, 41 și 42 mm"),
             "46mm": ("46 mm", "pentru carcase de 44, 45, 46 și 49 mm"), "49mm": ("49 mm", "pentru carcase de 44, 45, 46 și 49 mm")}
EAR_TIP = {"Extra extra klein": "XXS", "Extra klein": "XS", "Klein": "S", "Mittel": "M", "Groß": "L"}

# ------------------------------------------------------------------------------------------------ reguli
# (expresie pe titlul Apple, categorie, grup, nume RO, descriere scurtă RO, promoție)
R = []


def rule(pattern, cat, group, name, desc, promo=None, slug=None):
    R.append((re.compile(pattern, re.I), cat, group, name, desc, promo, slug))


A, AUD = "accesorii", "airpods"
# --- Beats (în categoria audio, cu toate culorile)
rule(r"^Beats 360 Over-Ear", AUD, "Beats", "Beats 360", "Căștile over-ear noi de la Beats: pernițe Performance din tricot, ANC și până la 50 de ore de autonomie.", slug="beats-360")
rule(r"^Beats Studio Pro", AUD, "Beats", "Beats Studio Pro", "ANC, audio spațial personalizat, până la 40 de ore și audio fără pierderi prin USB‑C.", slug="beats-studio-pro")
rule(r"^Beats Solo 4", AUD, "Beats", "Beats Solo 4", "On-ear, ușoare și pliabile, până la 50 de ore de autonomie și audio spațial.", slug="beats-solo-4")
rule(r"^Beats Solo Buds", AUD, "Beats", "Beats Solo Buds", "Cele mai mici căști Beats, cu sunet puternic și până la 18 ore de ascultare.", slug="beats-solo-buds")
rule(r"^Beats Studio Buds", AUD, "Beats", "Beats Studio Buds +", "In-ear cu ANC, mod Transparență și până la 36 de ore cu carcasa.", slug="beats-studio-buds-plus")
rule(r"^Beats Flex", AUD, "Beats", "Beats Flex", "Căști in-ear cu fir de legătură, magneți care le țin la gât și 12 ore de autonomie.", slug="beats-flex")
rule(r"^Beats Pill", AUD, "Beats", "Beats Pill", "Boxă portabilă IP67, 24 de ore de autonomie, poate încărca telefonul prin USB‑C.", slug="beats-pill")
rule(r"^Powerbeats Pro 2 – Nike", AUD, "Beats", "Powerbeats Pro 2 – Nike Special Edition", "Ediție specială Nike: cârlige flexibile, ANC și senzor de puls pentru antrenamente.", slug="powerbeats-pro-2-nike")
rule(r"^Powerbeats Pro 2", AUD, "Beats", "Powerbeats Pro 2", "Căști sport cu cârlige flexibile, ANC, senzor de puls și rezistență IPX4.", slug="powerbeats-pro-2")
rule(r"^Powerbeats Fit", AUD, "Beats", "Powerbeats Fit", "Căști pentru fitness cu aripioare flexibile care stau fix, ANC și IPX4.", slug="powerbeats-fit")
# --- huse și accesorii iPhone
rule(r"Silikon Case", A, "Huse iPhone", "Husă din silicon cu MagSafe", "Silicon moale la atingere, interior din microfibră, magneți MagSafe aliniați perfect.")
rule(r"Clear Case", A, "Huse iPhone", "Husă transparentă cu MagSafe", "Policarbonat optic care nu se îngălbenește și lasă vizibilă culoarea iPhone‑ului.")
rule(r"Funktionsgewebe Case", A, "Huse iPhone", "Husă TechWoven cu MagSafe", "Țesătură rezistentă, plăcută la atingere, cu buton de safir pentru Camera Control.")
rule(r"^Beats .*Kickstand Case", A, "Huse iPhone", "Husă Beats cu suport și MagSafe", "Suport integrat pentru filme și apeluri video, protecție la căderi și Camera Control.")
rule(r"^Beats .*Rugged Case", A, "Huse iPhone", "Husă Beats Rugged cu MagSafe", "Protecție sporită la căderi, margini ranforsate și buclă pentru curea.")
rule(r"^Beats .*Case mit MagSafe", A, "Huse iPhone", "Husă Beats cu MagSafe", "Husă subțire Beats cu protecție la căderi, MagSafe și Camera Control.")
rule(r"^iPhone Air Bumper", A, "Huse iPhone", "Bumper pentru iPhone Air", "Protecție pe margini care păstrează spatele liber și linia ultra-subțire.")
rule(r"^iPhone Air Case", A, "Huse iPhone", "Husă cu MagSafe pentru iPhone Air", "Subțire și ușoară, păstrează designul de 5,6 mm al iPhone Air.")
rule(r"^iPhone Duo Folio", A, "Huse iPhone", "Husă Folio cu suport pentru iPhone Duo", "Protejează ambele jumătăți și se transformă în suport în modul tabletă.")
rule(r"^iPhone Duo Case", A, "Huse iPhone", "Husă pentru iPhone Duo", "Protecție pe ambele jumătăți, balamaua rămâne liberă, compatibilă MagSafe.")
rule(r"Feingewebe Wallet", A, "Huse iPhone", "Portofel FineWoven cu MagSafe", "Ține până la trei carduri și îți arată în Găsire unde l-ai lăsat.", promo=0.12)
rule(r"^Crossbody Band", A, "Huse iPhone", "Curea crossbody pentru iPhone", "Curea reglabilă care se prinde de husă, ca să porți iPhone-ul pe umăr.")
rule(r"^Handgelenk Band", A, "Huse iPhone", "Curea de mână pentru iPhone", "Buclă scurtă de mână, se prinde de husele compatibile.")
rule(r"MagSafe Batterie", A, "Încărcare", "Baterie MagSafe pentru iPhone Air", "Baterie subțire care se atașează magnetic și încarcă wireless.")
# --- iPad
rule(r"^Smart Folio für (?:das )?(?P<m>.+?)\s*[–-]", A, "iPad și Apple Pencil", "Smart Folio pentru {m}", "Protecție față-spate, trezire automată și suport pentru tastare sau vizionare.")
rule(r"^Magic Keyboard für (?:das )?11\" iPad Pro", A, "iPad și Apple Pencil", "Magic Keyboard pentru iPad Pro (M5)", "Aluminiu, trackpad din sticlă cu haptic, rând de taste funcționale.")
rule(r"^Magic Keyboard für (?:das )?11\" iPad Air", A, "iPad și Apple Pencil", "Magic Keyboard pentru iPad Air (M4)", "Tastatură completă cu trackpad și rând de taste funcționale.")
rule(r"^Magic Keyboard Folio", A, "iPad și Apple Pencil", "Magic Keyboard Folio pentru iPad (A16)", "Două piese: tastatură detașabilă cu trackpad și spate cu suport reglabil.")
rule(r"^Apple Pencil Pro", A, "iPad și Apple Pencil", "Apple Pencil Pro", "Squeeze, rotire tip baton, feedback haptic și Găsire.", promo=0.08)
rule(r"^Apple Pencil \(USB-C\)", A, "iPad și Apple Pencil", "Apple Pencil (USB‑C)", "Precizie pixel cu pixel, încărcare și asociere prin USB‑C.")
rule(r"^Apple Pencil \(2\.", A, "iPad și Apple Pencil", "Apple Pencil (a doua generație)", "Pentru iPad‑urile cu încărcare magnetică a creionului (generațiile anterioare).")
rule(r"^Apple Pencil \(1\.", A, "iPad și Apple Pencil", "Apple Pencil (prima generație)", "Pentru iPad-urile mai vechi cu port Lightning sau USB‑C (cu adaptor).")
rule(r"^Apple Pencil Spitzen", A, "iPad și Apple Pencil", "Vârfuri Apple Pencil (4 bucăți)", "Vârfuri de schimb pentru toate modelele Apple Pencil.")
rule(r"auf Apple Pencil Adapter", A, "iPad și Apple Pencil", "Adaptor USB‑C la Apple Pencil", "Asociază și încarcă Apple Pencil (prima generație) prin USB‑C.")
# --- Mac
rule(r"Magic Keyboard mit Touch ID und Ziffern", A, "Tastaturi și mouse", "Magic Keyboard cu Touch ID și tastatură numerică", "Wireless, cu Touch ID pentru Mac-urile cu cip Apple și bloc numeric.")
rule(r"Magic Keyboard mit Touch ID", A, "Tastaturi și mouse", "Magic Keyboard cu Touch ID", "Wireless, compactă, cu Touch ID pentru Mac-urile cu cip Apple.")
rule(r"^Magic Keyboard \(USB", A, "Tastaturi și mouse", "Magic Keyboard (USB‑C)", "Wireless, compactă, se încarcă prin USB‑C.")
rule(r"^Magic Mouse", A, "Tastaturi și mouse", "Magic Mouse (USB‑C)", "Suprafață Multi‑Touch pentru gesturi, încărcare USB‑C.", promo=0.10)
rule(r"^Magic Trackpad", A, "Tastaturi și mouse", "Magic Trackpad (USB‑C)", "Force Touch și gesturi Multi‑Touch pe o suprafață mare din sticlă.")
# --- Apple Watch: curele
W = "Curele Apple Watch"
rule(r"Nike Sportarmband", A, W, "Curea sport Nike", "Fluoroelastomer cu perforații pentru aerisire, design Nike.")
rule(r"Nike Sport Loop", A, W, "Curea Sport Loop Nike", "Țesătură moale și respirabilă cu fir reflectorizant, ajustare rapidă.")
rule(r"Sportarmband", A, W, "Curea sport", "Fluoroelastomer moale și rezistent, închidere pin-and-tuck.")
rule(r"Sport Loop Pride", A, W, "Curea Sport Loop – Pride Edition", "Ediția Pride: țesătură moale în culorile curcubeului.")
rule(r"Black Unity Sport Loop", A, W, "Curea Sport Loop – Black Unity", "Ediția Black Unity: țesătură moale, ajustare cu scai.")
rule(r"Sport Loop", A, W, "Curea Sport Loop", "Țesătură moale și respirabilă, ajustare rapidă cu scai.")
rule(r"Geflochtenes Solo Loop Pride", A, W, "Curea Solo Loop împletită – Pride Edition", "Ediția Pride: fir reciclat împletit, extensibil, fără cataramă.")
rule(r"Black Unity Geflochtenes Solo Loop", A, W, "Curea Solo Loop împletită – Black Unity", "Ediția Black Unity: fir împletit extensibil, fără cataramă.")
rule(r"Geflochtenes Solo Loop", A, W, "Curea Solo Loop împletită", "Fir reciclat împletit cu silicon, extensibil, fără cataramă.")
rule(r"Solo Loop", A, W, "Curea Solo Loop", "Silicon elastic dintr-o singură bucată, fără cataramă.")
rule(r"49 mm Milanaise", A, W, "Curea Milanese Loop din titan", "Pentru Ultra: plasă din titan, închidere ajustabilă.")
rule(r"Milanaise", A, W, "Curea Milanese Loop", "Plasă din oțel inoxidabil, închidere magnetică ajustabilă.")
rule(r"Armband mit Magnetverschluss", A, W, "Curea cu închidere magnetică", "Piele fină cu închidere magnetică ascunsă.")
rule(r"Armband mit Moderner Schließe", A, W, "Curea cu cataramă modernă", "Piele franțuzească cu cataramă magnetică din două piese.")
rule(r"^Kit für das", A, W, "Kit de zale pentru brățara cu zale", "Zale suplimentare pentru a ajusta brățara cu zale.")
rule(r"Gliederarmband", A, W, "Brățară cu zale", "Oțel inoxidabil cu peste 100 de componente, ajustabilă fără unelte.")
rule(r"Alpine Loop", A, W, "Curea Alpine Loop", "Pentru Ultra: țesătură dublă și cârlig din titan.")
rule(r"Trail Loop", A, W, "Curea Trail Loop", "Pentru Ultra: subțire, ușoară, ajustare rapidă.")
rule(r"^Verlängerung für 49 mm Ocean", A, W, "Prelungitor pentru curea Ocean", "Pentru purtarea curelei Ocean peste costumul de scufundare.")
rule(r"Ocean Armband", A, W, "Curea Ocean", "Pentru Ultra: elastomer pentru sporturi acvatice și scufundări.")
# --- încărcare
rule(r"^(\d+)W Dual USB", A, "Încărcare", "Adaptor dual USB‑C {1} W", "Două porturi USB‑C pentru încărcare simultană.")
rule(r"^(\d+)W Dynamic", A, "Încărcare", "Adaptor Dynamic 40 W (max. 60 W)", "Încarcă iPhone 18 Pro la 50% în aproximativ 15 minute.")
rule(r"^(\d+)W USB.C Power Adapter", A, "Încărcare", "Adaptor de alimentare USB‑C {1} W", "Încărcare rapidă și sigură pentru dispozitivele Apple.")
rule(r"^(\d+)W USB.C Ladekabel", A, "Încărcare", "Cablu de încărcare USB‑C {1} W", "Cablu țesut, compatibil cu toate dispozitivele USB‑C.")
rule(r"^MagSafe Ladegerät", A, "Încărcare", "Încărcător MagSafe", "Încărcare wireless rapidă de până la 25 W.", promo=0.10)
rule(r"Apple Watch magnetisches Schnellladegerät", A, "Încărcare", "Încărcător magnetic rapid Apple Watch (USB‑C)", "Încărcare rapidă pentru Apple Watch.")
rule(r"Apple Watch Magnetisches Ladekabel", A, "Încărcare", "Cablu magnetic de încărcare Apple Watch", "Cablu de încărcare clasic pentru Apple Watch.")
rule(r"Verlängerungskabel", A, "Încărcare", "Cablu prelungitor pentru adaptorul de alimentare", "Prelungește adaptorul MacBook cu 1,8 m.")
rule(r"MagSafe 3 Kabel", A, "Încărcare", "Cablu USB‑C la MagSafe 3 (2 m)", "Pentru MacBook Air și MacBook Pro, în culoarea laptopului tău.")
# --- cabluri și adaptoare
C = "Cabluri și adaptoare"
rule(r"^Thunderbolt 5", A, C, "Cablu Thunderbolt 5 (USB‑C) Pro", "Transfer de date de până la 120 Gb/s și încărcare de până la 240 W.")
rule(r"^Thunderbolt 4", A, C, "Cablu Thunderbolt 4 (USB‑C) Pro", "Date de până la 40 Gb/s și afișaje 6K, cablu țesut.")
rule(r"^Thunderbolt 3 .*Thunderbolt 2", A, C, "Adaptor Thunderbolt 3 (USB‑C) la Thunderbolt 2", "Pentru accesoriile și monitoarele Thunderbolt 2.")
rule(r"Digital AV Multiport", A, C, "Adaptor USB‑C Digital AV Multiport", "HDMI 4K60, USB‑A și USB‑C de încărcare.")
rule(r"^USB.C auf USB Adapter", A, C, "Adaptor USB‑C la USB", "Conectează stick-uri și accesorii USB‑A.")
rule(r"SD Kartenleseger", A, C, "Cititor de carduri USB‑C la SD", "Transfer rapid de pe camera foto.")
rule(r"Kopfhörer.?anschluss Adapter", A, C, "Adaptor USB‑C la jack 3,5 mm", "Pentru căștile cu fir.")
rule(r"3,5 mm Audiokabel", A, C, "Cablu audio USB‑C la jack 3,5 mm (1,2 m)", "Conectează iPhone sau Mac la sisteme audio cu intrare jack.")
rule(r"^USB.C auf Lightning Adapter", A, C, "Adaptor USB‑C la Lightning", "Folosește accesoriile Lightning cu dispozitive USB‑C.")
rule(r"^USB.C auf Lightning Kabel", A, C, "Cablu USB‑C la Lightning", "Încărcare și sincronizare pentru dispozitivele cu Lightning.")
rule(r"^Lightning auf USB Kabel", A, C, "Cablu Lightning la USB", "Pentru încărcătoarele și computerele cu USB‑A.")
rule(r"^Lightning Digital AV", A, C, "Adaptor Lightning Digital AV", "Oglindește ecranul pe un televizor prin HDMI.")
rule(r"^Lightning auf USB 3 Kamera", A, C, "Adaptor Lightning la USB 3 pentru cameră", "Importă fotografii și conectează accesorii USB.")
rule(r"^2er-Pack Beats USB.A", A, C, "Set 2 cabluri țesute Beats USB‑A la USB‑C", "Două cabluri țesute rezistente, 1,5 m.")
rule(r"^2er-Pack Beats USB.C", A, C, "Set 2 cabluri țesute Beats USB‑C la USB‑C", "Două cabluri țesute rezistente, 1,5 m.")
rule(r"^Beats USB.A auf USB.C", A, C, "Cablu țesut Beats USB‑A la USB‑C", "Cablu țesut rezistent la încurcare.", slug="cablu-tesut-beats-usb-a-la-usb-c")
rule(r"^Beats USB.C auf Lightning", A, C, "Cablu țesut Beats USB‑C la Lightning", "Cablu țesut rezistent la încurcare.", slug="cablu-tesut-beats-usb-c-la-lightning")
rule(r"^Beats USB.C auf USB.C", A, C, "Cablu țesut Beats USB‑C la USB‑C", "Cablu țesut rezistent la încurcare, în mai multe lungimi.", slug="cablu-tesut-beats-usb-c-la-usb-c")
# --- audio și diverse
rule(r"^EarPods", A, "Audio", "EarPods", "Căști cu fir cu telecomandă și microfon.")
rule(r"AirPods Pro 3 Ohreinsätze − 5 Paar", A, "Audio", "Set dopuri AirPods Pro 3 (5 perechi)", "Câte o pereche din fiecare mărime: XXS, XS, S, M, L.")
rule(r"AirPods Pro 3 Ohreinsätze", A, "Audio", "Dopuri AirPods Pro 3 (2 perechi)", "Dopuri de schimb din silicon, într-o singură mărime.")
rule(r"AirPods Pro 2 Ohreinsätze − 4 Paar", A, "Audio", "Set dopuri AirPods Pro 2 (4 perechi)", "Câte o pereche din fiecare mărime: XS, S, M, L.")
rule(r"AirPods Pro 2 Ohreinsätze", A, "Audio", "Dopuri AirPods Pro 2 (2 perechi)", "Dopuri de schimb din silicon, într-o singură mărime.")
rule(r"AirPods Max \(Lightning", A, "Audio", "Pernițe AirPods Max (Lightning)", "Pernițe din spumă cu memorie pentru AirPods Max cu port Lightning.")
rule(r"AirPods Max Ohrpolster", A, "Audio", "Pernițe AirPods Max", "Pernițe din spumă cu memorie, se prind magnetic.")
rule(r"Beats 360 Cushion Kit – Performance", A, "Audio", "Kit pernițe Beats 360 – Performance", "Pernițe și bandă din tricot respirabil, pentru sport.")
rule(r"Beats 360 Cushion Kit – UltraPlush", A, "Audio", "Kit pernițe Beats 360 – UltraPlush", "Pernițe moi din piele ecologică pentru confort îndelungat.")
rule(r"AirTag Feingewebe", A, "Diverse", "Breloc FineWoven pentru AirTag", "Breloc din țesătură FineWoven pentru chei, cu AirTag la vedere.")
rule(r"^Poliertuch", A, "Diverse", "Lavetă de curățare Apple", "Material neabraziv pentru ecrane, inclusiv cele cu nanotextură.")
rule(r"^Siri Remote", A, "Diverse", "Siri Remote", "Telecomanda pentru Apple TV 4K, cu touchpad și încărcare USB‑C.")

GROUPS = ["Huse iPhone", "iPad și Apple Pencil", "Curele Apple Watch", "Încărcare", "Cabluri și adaptoare",
          "Tastaturi și mouse", "Audio", "Diverse"]


def slugify(s):
    for a, b in [("ă", "a"), ("â", "a"), ("î", "i"), ("ș", "s"), ("ț", "t"), ("‑", "-"), ("–", "-"), ("+", " plus "),
                 ("\"", ""), ("(", ""), (")", ""), (",", ""), ("/", "-"), ("’", ""), (".", "")]:
        s = s.lower().replace(a, b)
    return "-".join(re.sub(r"[^a-z0-9-]+", " ", s).split()).replace("--", "-")[:80].strip("-")


def clean(s):
    return (s or "").replace("\xa0", " ").replace("­", "").strip()


def model_name(raw, label):
    """iPhone 18 Pro, iPad Pro 11" (M5) … din eticheta/valoarea Apple"""
    t = clean(label or raw)
    fixed = {"iphone17e": "iPhone 17e", "iphoneair": "iPhone Air"}
    t = fixed.get(t, t)
    m = re.match(r'(\d+)" (iPad (?:Pro|Air)) \((M\d)\)', t)
    return f'{m.group(2)} {m.group(1)}" ({m.group(3)})' if m else t


def join_models(models):
    ms = list(models)
    if len(ms) <= 1:
        return ms[0] if ms else ""
    # „iPhone 17, 17 Pro și 17 Pro Max”
    short = [ms[0]] + [m.replace("iPhone ", "") if m.startswith("iPhone ") and ms[0].startswith("iPhone ") else m for m in ms[1:]]
    return ", ".join(short[:-1]) + " și " + short[-1]


def build(eur_to_lei_cat=None):
    fams = json.load(open(RAW, encoding="utf-8"))
    products = {}
    skipped = []
    for f in fams:
        title = clean(f["title"])
        if any(re.search(x, title) for x in EXCLUDE):
            skipped.append(title)
            continue
        hit = next(((rx, *rest) for rx, *rest in R if rx.search(title)), None)
        if not hit:
            skipped.append("FĂRĂ REGULĂ: " + title)
            continue
        rx, cat, group, name, desc, promo, slug = hit
        m = rx.search(title)
        if "{m}" in name:
            name = name.replace("{m}", model_name(m.group("m"), m.group("m")))
        if "{1}" in name:
            name = name.replace("{1}", m.group(1))
        labels = f["labels"]

        rows = []
        for part, v in f["variants"].items():
            d = dict(v["dims"])
            model = d.pop("dimensionMerchModel", None)
            mname = model_name(model, labels.get("dimensionMerchModel", {}).get(model)) if model else None
            if mname and mname.startswith("iPhone") and mname not in CURRENT_MODELS:
                continue
            price = f["prices"].get(part)
            if price is None:
                continue
            o = {}
            if mname:
                o["model"] = mname
            if "dimensionCaseSize" in d:
                o["case"] = d["dimensionCaseSize"]
            if "dimensionColor" in d:
                o["color"] = d["dimensionColor"]
            if "dimensionMetalFinish" in d:
                o["finish"] = d["dimensionMetalFinish"]
            if "dimensionbandsize" in d:
                o["size"] = d["dimensionbandsize"]
            if "dimensionProductsize" in d and d["dimensionProductsize"] != "all_sizes":
                o["size"] = d["dimensionProductsize"]
            if "dimensionLength" in d:
                o["length"] = d["dimensionLength"]
            if "dimensionConnection" in d:
                o["conn"] = d["dimensionConnection"]
            mpn = part
            if "dimensionLanguage" in d:
                lang = d["dimensionLanguage"]
                if lang != "enintl":
                    continue
                o["layout"] = "en"
                rows.append([{**o, "layout": "ro"}, price, part.split("Z/A")[0] + "RO/A" if part.endswith("Z/A") else part])
            rows.append([o, price, mpn])
        if not rows:
            skipped.append("FĂRĂ VARIANTE CURENTE: " + title)
            continue

        # modelul face parte din nume la husele pentru un singur telefon (Air, Duo) → fără selector de model
        if "iPhone" in name:
            for r in rows:
                r[0].pop("model", None)
        models = sorted({r[0]["model"] for r in rows if "model" in r[0]}, key=_model_order)
        if group == "Huse iPhone" and models:
            name = f"{name} pentru {join_models(models)}"
            if len(models) == 1:
                for r in rows:
                    r[0].pop("model", None)
        slug = slug or slugify(name)
        p = products.get(slug)
        if not p:
            p = products[slug] = {"slug": slug, "cat": cat, "group": group, "name": name, "desc": desc, "promo": promo,
                                  "rows": [], "labels": {}, "swatches": {}, "gallery": f["gallery"], "titles": []}
        p["rows"].extend(rows)
        p["titles"].append(title)
        for k, lab in labels.items():
            p["labels"].setdefault(k, {}).update(lab)
        for k, sw in f["swatches"].items():
            p["swatches"].setdefault(k, sw)
        if not p["gallery"]:
            p["gallery"] = f["gallery"]
    return products, skipped


def _model_order(m):
    return CURRENT_MODELS.index(m) if m in CURRENT_MODELS else 50 + len(m)
