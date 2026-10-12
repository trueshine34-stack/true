# -*- coding: utf-8 -*-
"""
Traducerea în engleză a accesoriilor din catalog (categoria „accesorii”) pentru versiunea EN a magazinului.

  python3 tools/translate_accessories.py            # scrie data/i18n/catalog-accessories.en.json și verifică acoperirea
  python3 tools/translate_accessories.py --check    # doar verificarea (fără scriere)

Citește data/catalog.json (textele românești generate de catalog_data.py + accessories_data.py) și produce:
  labels   – etichete de opțiuni, titluri și etichete de specificații, grupuri
  values   – toate etichetele de valori (culori, mărimi, lungimi, conectori, catarame, tastaturi, modele, indicii)
  products – pentru fiecare accesoriu: nume, tagline, descriere, specificații, opțiuni (etichete, notă, valori,
             notițele valorilor ca „<cheie>@note”) — formatul citit de src/services/catalog-i18n.js

Numele de produse și de culori sunt cele oficiale Apple (Apple Store UK, engleză britanică); culorile sunt traduse
după codul Apple al valorii (ex. „navyblue” → Navy Blue), cu rezervă după eticheta românească.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from accessories_i18n import COLOR_RO  # noqa: E402

CATALOG = os.path.join(ROOT, "data", "catalog.json")
OUT = os.path.join(ROOT, "data", "i18n", "catalog-accessories.en.json")
NB = "‑"  # cratima neseparabilă din „USB‑C”, păstrată și în engleză

# ------------------------------------------------------------------------------------------------ culori
# eticheta RO → numele oficial Apple (UK); unde Apple are nume diferite pentru aceeași etichetă RO, decide codul valorii
COLOR_EN = {
    "After Dark Black": "After Dark Black", "Alb": "White", "Alb crem": "Ivory", "Albastru": "Blue",
    "Albastru ardezie": "Slate Blue", "Albastru cer": "Sky Blue", "Albastru chambray": "Chambray Blue",
    "Albastru clar": "Crisp Blue", "Albastru deschis": "Light Blue", "Albastru flacără": "Flame Blue",
    "Albastru furtună": "Storm Blue", "Albastru gheață": "Frost", "Albastru iarnă": "Winter Blue",
    "Albastru maree": "Riptide Blue", "Albastru marin": "Anchor Blue", "Albastru stâncă": "Bedrock Blue",
    "Albastru turbo": "Nitro Navy", "Albastru zambilă": "Wildflower Blue", "Albastru/Negru": "Blue/Black",
    "Acvamarin": "Aquamarine", "Antracit": "Charcoal Grey", "Argintiu": "Silver", "Auriu strălucitor": "Radiant Gold",
    "Black Unity – Unity Connection": "Black Unity – Unity Connection", "Black Unity – Unity Rhythm": "Black Unity – Unity Rhythm",
    "Bleumarin": "Navy Blue", "Bronz închis": "Dark Bronze", "Bujor": "Peony", "Burgundia": "Burgundy",
    "Burgundia carouri": "Burgundy Plaid", "Calcar": "Lime Stone", "Carambola": "Starfruit", "Cer": "Sky",
    "Chalk Calm": "Chalk Calm", "Cămilă": "Camel", "Denim": "Denim", "Dud": "Mulberry", "Espresso": "Deep Brown",
    "Essential White": "Essential White", "Fucsia": "Fuchsia", "Galben cald": "Sunshine", "Galben neon": "Neon Yellow",
    "Galben yuzu": "Yuzu Yellow", "Green Spark": "Green Spark", "Gresie": "Sandstone", "Gri alpin": "Alpine Grey",
    "Gri deschis": "Light Grey", "Gri energie": "Surge Stone", "Gri fum": "Smoke Grey", "Gri furtună": "Storm Grey",
    "Gri granit": "Granite Grey", "Gri piatră": "Stone Grey", "Gri pietriș": "Gravel Grey", "Gri spațial": "Space Grey",
    "Gri translucid": "Translucent Grey", "Hiperviolet": "Hyper Purple", "Indigo": "Indigo",
    "Lavandă luminoasă": "Electric Lavender", "Liliac apus": "Sunset Purple", "Limonadă": "Lemonade",
    "Magenta": "Magenta", "Mandarină": "Tangerine", "Maro lut": "Clay", "Mentă pal": "Soft Mint",
    "Miezul nopții": "Midnight", "Migdală": "Tan", "Măsliniu închis": "Dark Olive", "Natural": "Natural",
    "Negru": "Black", "Negru Beats": "Beats Black", "Negru Power": "Bolt Black", "Negru diamant": "Jet Black",
    "Negru mat": "Matt Black", "Negru noapte": "Midnight Black", "Negru spațial": "Space Black",
    "Negru translucid": "Translucent Black", "Negru/Auriu": "Black/Gold", "Nevada Orange": "Sierra Orange",
    "Nisip": "Sand", "Nisip de deșert": "Desert", "Nisip mișcător": "Quick Sand", "Noapte montană": "Everest Black",
    "Nor": "Cloud", "Oliv": "Olive", "Pavement Grey": "Pavement Grey", "Pepene roșu": "Watermelon",
    "Piatră de vârf": "Summit Stone", "Portocaliu": "Orange", "Portocaliu aprins": "Spark Orange",
    "Portocaliu neon": "Electric Orange", "Power Pink": "Power Pink", "Pride Edition": "Pride Edition", "Prună": "Plum",
    "Rocky Mountain Blue": "Rocky Blue", "Roz": "Pink", "Roz cretă": "Chalk Pink", "Roz deschis": "Soft Pink",
    "Roz guava": "Bright Guava", "Roz nor": "Cloud Pink", "Roz pietricică": "Pebble Pink", "Roșu": "Red",
    "Roșu fulger": "Rapid Red", "Roșu semnal": "Statement Red", "Roșu transparent": "Transparent Red", "Salvie": "Sage",
    "Siena": "Sienna", "Steaua polară": "Starlight", "Suprafață Multi‑Touch albă": "White Multi‑Touch Surface",
    "Suprafață Multi‑Touch neagră": "Black Multi‑Touch Surface", "Taste albe": "White Keys", "Taste negre": "Black Keys",
    "Taupe": "Taupe", "Teracotă": "Terra Cotta", "Transparent": "Transparent", "Ultramarin": "Ultramarine",
    "Umbră": "Shadow", "Umbră deschisă carouri": "Light Umber Plaid", "Umbră închisă": "Dark Umber", "Vanilie": "Vanilla",
    "Verde": "Green", "Verde agavă pal": "Pale Succulent", "Verde alge translucid": "Translucent Kelp",
    "Verde mare": "Lake Green", "Verde mușchi deschis": "Light Moss", "Violet": "Purple", "Violet ceață": "Purple Fog",
    "Violet deschis": "Light Violet", "Violet polar": "Arctic Purple", "Viorea": "Violet", "Volt": "Volt",
    "Șampanie": "Champagne Gold",
}
# codul Apple al valorii → nume, când aceeași etichetă RO acoperă mai multe culori Apple
COLOR_BY_KEY = {
    "navy": "Navy", "navy_beats": "Navy", "sky_beats": "Sky", "skywithsky": "Sky with Sky", "limestonewhite": "Limestone",
    "cloudwithcloud": "Cloud with Cloud", "blackwithblack": "Black with Black", "pinkwithpink": "Pink with Pink",
}

# ------------------------------------------------------------------------------------------------ alte valori
VALUES_EN = {
    # mărimi
    "Mărime unică": "One size", "XXS": "XXS", "XS": "XS", "S": "S", "M": "M", "L": "L", "XL": "XL", "S/M": "S/M", "M/L": "M/L",
    # lungimi
    "20 cm": "20 cm", "1 m": "1 m", "1,5 m": "1.5 m", "1,8 m": "1.8 m", "2 m": "2 m", "3 m": "3 m",
    # conectori
    "Jack 3,5 mm": "3.5mm Headphone Plug", "Lightning": "Lightning", f"USB{NB}C": f"USB{NB}C",
    # cataramă (Ultra)
    "Titan natural": "Natural Titanium", "Titan negru": "Black Titanium",
    # tastaturi
    "Română": "Romanian", "Engleză (internațională)": "International English", "Engleză (SUA)": "US English",
    "Engleză (Marea Britanie)": "British English", "Germană": "German", "Franceză": "French", "Maghiară": "Hungarian",
    "Rusă": "Russian", "Ucraineană": "Ukrainian", "Italiană": "Italian", "Spaniolă": "Spanish",
}

LABELS_EN = {
    # etichete de opțiuni
    "Model": "Model", "Carcasă": "Case", "Culoare": "Colour", "Cataramă": "Buckle", "Mărime": "Size",
    "Lungime": "Length", "Conector": "Connector", "Tastatură": "Keyboard layout",
    # specificații
    "Compatibilitate": "Compatibility", "Modele": "Models",
    # grupurile accesoriilor
    "Huse iPhone": "iPhone Cases", "iPad și Apple Pencil": "iPad & Apple Pencil", "Curele Apple Watch": "Apple Watch Bands",
    "Încărcare": "Charging", "Cabluri și adaptoare": "Cables & Adapters", "Tastaturi și mouse": "Keyboards & Mice",
    "Audio": "Audio", "Diverse": "Other Accessories",
    # garanția (aceeași la toate produsele)
    "24 de luni garanție (garanția legală de conformitate) + 14 zile drept de retur.":
        "24-month warranty (statutory guarantee of conformity) + 14-day right of return.",
}
# etichete de opțiuni mai precise, doar pentru accesorii
OPTION_LABEL_EN = {"case": "Case size"}

NOTES_EN = {
    "Solo Loop nu are cataramă: alege mărimea după circumferința încheieturii (ghidul Apple de mărimi o măsoară în 1 minut).":
        "The Solo Loop has no buckle, so choose the size that matches your wrist circumference "
        "(Apple’s size guide measures it in a minute).",
}

# ------------------------------------------------------------------------------------------------ nume
NAME_EN = {
    # iPhone
    "Bumper pentru iPhone Air": "iPhone Air Bumper", "Husă cu MagSafe pentru iPhone Air": "iPhone Air Case with MagSafe",
    "Husă Folio cu suport pentru iPhone Duo": "iPhone Duo Folio with Kickstand", "Husă pentru iPhone Duo": "iPhone Duo Case",
    "Portofel FineWoven cu MagSafe": "iPhone FineWoven Wallet with MagSafe",
    "Curea crossbody pentru iPhone": "Crossbody Strap for iPhone", "Curea de mână pentru iPhone": "Wrist Strap for iPhone",
    "Baterie MagSafe pentru iPhone Air": "iPhone Air MagSafe Battery",
    # iPad și Apple Pencil
    "Magic Keyboard pentru iPad Pro (M5)": "Magic Keyboard for iPad Pro (M5)",
    "Magic Keyboard pentru iPad Air (M4)": "Magic Keyboard for iPad Air (M4)",
    "Magic Keyboard Folio pentru iPad (A16)": "Magic Keyboard Folio for iPad (A16)",
    "Apple Pencil Pro": "Apple Pencil Pro", f"Apple Pencil (USB{NB}C)": f"Apple Pencil (USB{NB}C)",
    "Apple Pencil (a doua generație)": "Apple Pencil (2nd generation)",
    "Apple Pencil (prima generație)": "Apple Pencil (1st generation)",
    "Vârfuri Apple Pencil (4 bucăți)": "Apple Pencil Tips (4-pack)",
    f"Adaptor USB{NB}C la Apple Pencil": f"USB{NB}C to Apple Pencil Adapter",
    # Mac
    "Magic Keyboard cu Touch ID și tastatură numerică": "Magic Keyboard with Touch ID and Numeric Keypad",
    "Magic Keyboard cu Touch ID": "Magic Keyboard with Touch ID", f"Magic Keyboard (USB{NB}C)": f"Magic Keyboard (USB{NB}C)",
    f"Magic Mouse (USB{NB}C)": f"Magic Mouse (USB{NB}C)", f"Magic Trackpad (USB{NB}C)": f"Magic Trackpad (USB{NB}C)",
    # Apple Watch
    "Curea sport Nike": "Nike Sport Band", "Curea Sport Loop Nike": "Nike Sport Loop", "Curea sport": "Sport Band",
    "Curea Sport Loop – Pride Edition": "Pride Edition Sport Loop", "Curea Sport Loop – Black Unity": "Black Unity Sport Loop",
    "Curea Sport Loop": "Sport Loop", "Curea Solo Loop împletită – Pride Edition": "Pride Edition Braided Solo Loop",
    "Curea Solo Loop împletită – Black Unity": "Black Unity Braided Solo Loop",
    "Curea Solo Loop împletită": "Braided Solo Loop", "Curea Solo Loop": "Solo Loop",
    "Curea Milanese Loop din titan": "Titanium Milanese Loop", "Curea Milanese Loop": "Milanese Loop",
    "Curea cu închidere magnetică": "Magnetic Link", "Curea cu cataramă modernă": "Modern Buckle",
    "Kit de zale pentru brățara cu zale": "Link Bracelet Kit", "Brățară cu zale": "Link Bracelet",
    "Curea Alpine Loop": "Alpine Loop", "Curea Trail Loop": "Trail Loop",
    "Prelungitor pentru curea Ocean": "Ocean Band Extension", "Curea Ocean": "Ocean Band",
    # încărcare
    "Încărcător MagSafe": "MagSafe Charger",
    f"Încărcător magnetic rapid Apple Watch (USB{NB}C)": f"Apple Watch Magnetic Fast Charger (USB{NB}C)",
    "Cablu magnetic de încărcare Apple Watch": "Apple Watch Magnetic Charging Cable",
    "Cablu prelungitor pentru adaptorul de alimentare": "Power Adapter Extension Cable",
    f"Cablu USB{NB}C la MagSafe 3 (2 m)": f"USB{NB}C to MagSafe 3 Cable (2 m)",
    # cabluri și adaptoare
    f"Cablu Thunderbolt 5 (USB{NB}C) Pro": f"Thunderbolt 5 (USB{NB}C) Pro Cable",
    f"Cablu Thunderbolt 4 (USB{NB}C) Pro": f"Thunderbolt 4 (USB{NB}C) Pro Cable",
    f"Adaptor Thunderbolt 3 (USB{NB}C) la Thunderbolt 2": f"Thunderbolt 3 (USB{NB}C) to Thunderbolt 2 Adapter",
    f"Adaptor USB{NB}C Digital AV Multiport": f"USB{NB}C Digital AV Multiport Adapter",
    f"Adaptor USB{NB}C la USB": f"USB{NB}C to USB Adapter", f"Cititor de carduri USB{NB}C la SD": f"USB{NB}C to SD Card Reader",
    f"Adaptor USB{NB}C la jack 3,5 mm": f"USB{NB}C to 3.5mm Headphone Jack Adapter",
    f"Cablu audio USB{NB}C la jack 3,5 mm (1,2 m)": f"USB{NB}C to 3.5mm Audio Cable (1.2 m)",
    f"Adaptor USB{NB}C la Lightning": f"USB{NB}C to Lightning Adapter", f"Cablu USB{NB}C la Lightning": f"USB{NB}C to Lightning Cable",
    "Cablu Lightning la USB": "Lightning to USB Cable", "Adaptor Lightning Digital AV": "Lightning Digital AV Adapter",
    "Adaptor Lightning la USB 3 pentru cameră": "Lightning to USB 3 Camera Adapter",
    f"Set 2 cabluri țesute Beats USB{NB}A la USB{NB}C": f"Beats USB{NB}A to USB{NB}C Woven Cables (two-pack)",
    f"Set 2 cabluri țesute Beats USB{NB}C la USB{NB}C": f"Beats USB{NB}C to USB{NB}C Woven Cables (two-pack)",
    f"Cablu țesut Beats USB{NB}A la USB{NB}C": f"Beats USB{NB}A to USB{NB}C Woven Cable",
    f"Cablu țesut Beats USB{NB}C la Lightning": f"Beats USB{NB}C to Lightning Woven Cable",
    f"Cablu țesut Beats USB{NB}C la USB{NB}C": f"Beats USB{NB}C to USB{NB}C Woven Cable",
    # audio
    "EarPods": "EarPods",
    "Set dopuri AirPods Pro 3 (5 perechi)": "AirPods Pro 3 Ear Tips (5 pairs)",
    "Dopuri AirPods Pro 3 (2 perechi)": "AirPods Pro 3 Ear Tips (2 pairs)",
    "Set dopuri AirPods Pro 2 (4 perechi)": "AirPods Pro 2 Ear Tips (4 pairs)",
    "Dopuri AirPods Pro 2 (2 perechi)": "AirPods Pro 2 Ear Tips (2 pairs)",
    "Pernițe AirPods Max (Lightning)": "AirPods Max Ear Cushions (Lightning)", "Pernițe AirPods Max": "AirPods Max Ear Cushions",
    "Kit pernițe Beats 360 – Performance": "Beats 360 Cushion Kit – Performance",
    "Kit pernițe Beats 360 – UltraPlush": "Beats 360 Cushion Kit – UltraPlush",
    # diverse
    "Breloc FineWoven pentru AirTag": "AirTag FineWoven Key Ring", "Lavetă de curățare Apple": "Apple Polishing Cloth",
    "Siri Remote": "Siri Remote",
}
# huse vândute pentru unul sau mai multe modele: „<husă> pentru iPhone 17, 17 Pro și 17 Pro Max”
CASE_EN = {
    "Husă din silicon cu MagSafe": "Silicone Case with MagSafe", "Husă transparentă cu MagSafe": "Clear Case with MagSafe",
    "Husă TechWoven cu MagSafe": "TechWoven Case with MagSafe", "Husă Beats cu suport și MagSafe": "Beats Kickstand Case with MagSafe",
    "Husă Beats Rugged cu MagSafe": "Beats Rugged Case with MagSafe", "Husă Beats cu MagSafe": "Beats Case with MagSafe",
}
NAME_RX = [
    (rf"^Adaptor dual USB{NB}C (\d+) W$", lambda m: f"{m[1]}W Dual USB{NB}C Port Power Adapter"),
    (rf"^Adaptor de alimentare USB{NB}C (\d+) W$", lambda m: f"{m[1]}W USB{NB}C Power Adapter"),
    (rf"^Cablu de încărcare USB{NB}C (\d+) W$", lambda m: f"{m[1]}W USB{NB}C Charge Cable"),
    (r"^Adaptor Dynamic (\d+) W \(max\. (\d+) W\)$", lambda m: f"{m[1]}W Dynamic Power Adapter with {m[2]}W Max"),
    (r"^Smart Folio pentru (.+)$", lambda m: f"Smart Folio for {model_en(m[1])}"),
]


def model_en(m):
    """iPad Pro 11" (M5) → iPad Pro 11-inch (M5), ca în Apple Store UK"""
    return re.sub(r'(\d+)"', r"\1-inch", m)


def models_en(s):
    """„iPhone 17, 17 Pro și 17 Pro Max” → „iPhone 17, 17 Pro and 17 Pro Max”"""
    return s.replace(" și ", " and ")


def name_en(ro):
    if ro in NAME_EN:
        return NAME_EN[ro]
    for rx, fn in NAME_RX:
        m = re.match(rx, ro)
        if m:
            return fn(m)
    for base in sorted(CASE_EN, key=len, reverse=True):
        if ro.startswith(base + " pentru "):
            models, en = ro[len(base) + 8:], CASE_EN[base]
            if "," in models or " și " in models:
                return f"{en} for {models_en(models)}"
            # un singur model: denumirea Apple, „iPhone 16 Silicone Case with MagSafe” / „Beats iPhone 16 Case with MagSafe”
            return f"Beats {models} {en[6:]}" if en.startswith("Beats ") else f"{models} {en}"
    return None


# ------------------------------------------------------------------------------------------------ descrieri
DESC_EN = {
    "Silicon moale la atingere, interior din microfibră, magneți MagSafe aliniați perfect.":
        "Silky-soft silicone, a microfibre lining and perfectly aligned MagSafe magnets.",
    "Policarbonat optic care nu se îngălbenește și lasă vizibilă culoarea iPhone‑ului.":
        "Optically clear polycarbonate that resists yellowing and shows off the colour of your iPhone.",
    "Țesătură rezistentă, plăcută la atingere, cu buton de safir pentru Camera Control.":
        "A durable fabric that’s soft to the touch, with a sapphire crystal button for Camera Control.",
    "Suport integrat pentru filme și apeluri video, protecție la căderi și Camera Control.":
        "Built-in kickstand for films and video calls, drop protection and Camera Control.",
    "Protecție sporită la căderi, margini ranforsate și buclă pentru curea.":
        "Extra drop protection, reinforced edges and a loop for a lanyard.",
    "Husă subțire Beats cu protecție la căderi, MagSafe și Camera Control.":
        "A slim Beats case with drop protection, MagSafe and Camera Control.",
    "Protecție pe margini care păstrează spatele liber și linia ultra-subțire.":
        "Edge protection that leaves the back uncovered and keeps the ultra-thin profile.",
    "Subțire și ușoară, păstrează designul de 5,6 mm al iPhone Air.":
        "Thin and light, it keeps the 5.6 mm design of iPhone Air.",
    "Protejează ambele jumătăți și se transformă în suport în modul tabletă.":
        "Protects both halves and turns into a stand in tablet mode.",
    "Protecție pe ambele jumătăți, balamaua rămâne liberă, compatibilă MagSafe.":
        "Protection for both halves with the hinge left free; MagSafe compatible.",
    "Ține până la trei carduri și îți arată în Găsire unde l-ai lăsat.":
        "Holds up to three cards and shows you in Find My where you left it.",
    "Curea reglabilă care se prinde de husă, ca să porți iPhone-ul pe umăr.":
        "An adjustable strap that attaches to your case so you can carry your iPhone over your shoulder.",
    "Buclă scurtă de mână, se prinde de husele compatibile.":
        "A short wrist loop that attaches to compatible cases.",
    "Baterie subțire care se atașează magnetic și încarcă wireless.":
        "A slim battery that attaches magnetically and charges wirelessly.",
    "Protecție față-spate, trezire automată și suport pentru tastare sau vizionare.":
        "Front and back protection, automatic wake and a stand for typing or watching.",
    "Aluminiu, trackpad din sticlă cu haptic, rând de taste funcționale.":
        "Aluminium design, a glass trackpad with haptic feedback and a row of function keys.",
    "Tastatură completă cu trackpad și rând de taste funcționale.":
        "A full keyboard with a trackpad and a row of function keys.",
    "Două piese: tastatură detașabilă cu trackpad și spate cu suport reglabil.":
        "Two pieces: a detachable keyboard with trackpad and a back panel with an adjustable stand.",
    "Squeeze, rotire tip baton, feedback haptic și Găsire.": "Squeeze, barrel roll, haptic feedback and Find My.",
    "Precizie pixel cu pixel, încărcare și asociere prin USB‑C.":
        "Pixel-perfect precision, with pairing and charging via USB‑C.",
    "Pentru iPad‑urile cu încărcare magnetică a creionului (generațiile anterioare).":
        "For iPad models that charge the pencil magnetically (previous generations).",
    "Pentru iPad-urile mai vechi cu port Lightning sau USB‑C (cu adaptor).":
        "For older iPad models with a Lightning or USB‑C port (with adapter).",
    "Vârfuri de schimb pentru toate modelele Apple Pencil.": "Replacement tips for every Apple Pencil model.",
    "Asociază și încarcă Apple Pencil (prima generație) prin USB‑C.":
        "Pair and charge Apple Pencil (1st generation) via USB‑C.",
    "Wireless, cu Touch ID pentru Mac-urile cu cip Apple și bloc numeric.":
        "Wireless, with Touch ID for Mac models with Apple silicon and a numeric keypad.",
    "Wireless, compactă, cu Touch ID pentru Mac-urile cu cip Apple.":
        "Wireless and compact, with Touch ID for Mac models with Apple silicon.",
    "Wireless, compactă, se încarcă prin USB‑C.": "Wireless and compact; charges via USB‑C.",
    "Suprafață Multi‑Touch pentru gesturi, încărcare USB‑C.": "A Multi‑Touch surface for gestures, with USB‑C charging.",
    "Force Touch și gesturi Multi‑Touch pe o suprafață mare din sticlă.":
        "Force Touch and Multi‑Touch gestures on a large glass surface.",
    "Fluoroelastomer cu perforații pentru aerisire, design Nike.":
        "Fluoroelastomer with perforations for breathability, in a Nike design.",
    "Țesătură moale și respirabilă cu fir reflectorizant, ajustare rapidă.":
        "A soft, breathable weave with reflective thread and quick adjustment.",
    "Fluoroelastomer moale și rezistent, închidere pin-and-tuck.":
        "Soft, durable fluoroelastomer with a pin-and-tuck closure.",
    "Ediția Pride: țesătură moale în culorile curcubeului.": "Pride Edition: a soft weave in the colours of the rainbow.",
    "Ediția Black Unity: țesătură moale, ajustare cu scai.":
        "Black Unity edition: a soft weave with a hook-and-loop fastener.",
    "Țesătură moale și respirabilă, ajustare rapidă cu scai.":
        "A soft, breathable weave with quick hook-and-loop adjustment.",
    "Ediția Pride: fir reciclat împletit, extensibil, fără cataramă.":
        "Pride Edition: stretchable braided recycled yarn, with no buckle.",
    "Ediția Black Unity: fir împletit extensibil, fără cataramă.":
        "Black Unity edition: stretchable braided yarn, with no buckle.",
    "Fir reciclat împletit cu silicon, extensibil, fără cataramă.":
        "Recycled yarn braided with silicone threads; stretchable, with no buckle.",
    "Silicon elastic dintr-o singură bucată, fără cataramă.": "Stretchable one-piece silicone, with no buckle.",
    "Pentru Ultra: plasă din titan, închidere ajustabilă.": "For Ultra: titanium mesh with an adjustable closure.",
    "Plasă din oțel inoxidabil, închidere magnetică ajustabilă.":
        "Stainless steel mesh with an adjustable magnetic closure.",
    "Piele fină cu închidere magnetică ascunsă.": "Fine leather with a hidden magnetic closure.",
    "Piele franțuzească cu cataramă magnetică din două piese.": "French leather with a two-piece magnetic buckle.",
    "Zale suplimentare pentru a ajusta brățara cu zale.": "Extra links to adjust the size of your Link Bracelet.",
    "Oțel inoxidabil cu peste 100 de componente, ajustabilă fără unelte.":
        "Stainless steel with more than 100 components, adjustable without tools.",
    "Pentru Ultra: țesătură dublă și cârlig din titan.": "For Ultra: a two-layer weave with a titanium hook.",
    "Pentru Ultra: subțire, ușoară, ajustare rapidă.": "For Ultra: thin, light and quick to adjust.",
    "Pentru purtarea curelei Ocean peste costumul de scufundare.": "Lets you wear the Ocean Band over a wetsuit.",
    "Pentru Ultra: elastomer pentru sporturi acvatice și scufundări.":
        "For Ultra: an elastomer band made for water sports and diving.",
    "Două porturi USB‑C pentru încărcare simultană.": "Two USB‑C ports for charging two devices at once.",
    "Încarcă iPhone 18 Pro la 50% în aproximativ 15 minute.": "Charges iPhone 18 Pro to 50% in about 15 minutes.",
    "Încărcare rapidă și sigură pentru dispozitivele Apple.": "Fast, safe charging for your Apple devices.",
    "Cablu țesut, compatibil cu toate dispozitivele USB‑C.": "A woven cable that works with every USB‑C device.",
    "Încărcare wireless rapidă de până la 25 W.": "Fast wireless charging at up to 25W.",
    "Încărcare rapidă pentru Apple Watch.": "Fast charging for Apple Watch.",
    "Cablu de încărcare clasic pentru Apple Watch.": "The classic charging cable for Apple Watch.",
    "Prelungește adaptorul MacBook cu 1,8 m.": "Extends your MacBook power adapter by 1.8 m.",
    "Pentru MacBook Air și MacBook Pro, în culoarea laptopului tău.":
        "For MacBook Air and MacBook Pro, in a colour to match your laptop.",
    "Transfer de date de până la 120 Gb/s și încărcare de până la 240 W.":
        "Data transfer at up to 120Gb/s and charging at up to 240W.",
    "Date de până la 40 Gb/s și afișaje 6K, cablu țesut.":
        "Data at up to 40Gb/s and support for 6K displays, in a woven cable.",
    "Pentru accesoriile și monitoarele Thunderbolt 2.": "For Thunderbolt 2 accessories and displays.",
    "HDMI 4K60, USB‑A și USB‑C de încărcare.": "4K60 HDMI, USB‑A and a USB‑C charging port.",
    "Conectează stick-uri și accesorii USB‑A.": "Connect USB‑A flash drives and accessories.",
    "Transfer rapid de pe camera foto.": "Fast transfers from your camera.",
    "Pentru căștile cu fir.": "For your wired headphones.",
    "Conectează iPhone sau Mac la sisteme audio cu intrare jack.":
        "Connect iPhone or Mac to audio systems with a 3.5mm input.",
    "Folosește accesoriile Lightning cu dispozitive USB‑C.": "Use your Lightning accessories with USB‑C devices.",
    "Încărcare și sincronizare pentru dispozitivele cu Lightning.": "Charge and sync your devices with a Lightning port.",
    "Pentru încărcătoarele și computerele cu USB‑A.": "For chargers and computers with USB‑A.",
    "Oglindește ecranul pe un televizor prin HDMI.": "Mirror your screen to a TV over HDMI.",
    "Importă fotografii și conectează accesorii USB.": "Import photos and connect USB accessories.",
    "Două cabluri țesute rezistente, 1,5 m.": "Two durable woven cables, 1.5 m each.",
    "Cablu țesut rezistent la încurcare.": "A tangle-resistant woven cable.",
    "Cablu țesut rezistent la încurcare, în mai multe lungimi.": "A tangle-resistant woven cable in several lengths.",
    "Căști cu fir cu telecomandă și microfon.": "Wired earphones with a built-in remote and microphone.",
    "Câte o pereche din fiecare mărime: XXS, XS, S, M, L.": "One pair in each size: XXS, XS, S, M and L.",
    "Dopuri de schimb din silicon, într-o singură mărime.": "Replacement silicone ear tips in a single size.",
    "Câte o pereche din fiecare mărime: XS, S, M, L.": "One pair in each size: XS, S, M and L.",
    "Pernițe din spumă cu memorie pentru AirPods Max cu port Lightning.":
        "Memory foam ear cushions for AirPods Max with a Lightning port.",
    "Pernițe din spumă cu memorie, se prind magnetic.": "Memory foam ear cushions that attach magnetically.",
    "Pernițe și bandă din tricot respirabil, pentru sport.":
        "Ear cushions and headband in a breathable knit, made for workouts.",
    "Pernițe moi din piele ecologică pentru confort îndelungat.": "Soft vegan leather cushions for all-day comfort.",
    "Breloc din țesătură FineWoven pentru chei, cu AirTag la vedere.":
        "A FineWoven key ring that keeps your AirTag on show.",
    "Material neabraziv pentru ecrane, inclusiv cele cu nanotextură.":
        "A non-abrasive cloth for displays, including nano-texture glass.",
    "Telecomanda pentru Apple TV 4K, cu touchpad și încărcare USB‑C.":
        "The remote for Apple TV 4K, with a touch-enabled clickpad and USB‑C charging.",
}

ORIGINAL_RX = re.compile(r"^Produs original (\w+), nou și sigilat\.(?: Disponibil în (.+)\.)?$")


def count(n, one, many):
    return f"{n} {one if n == 1 else many}"


def avail_en(s):
    """„5 culori, 115 variante în total” / „26 de variante” → engleză, cu singular/plural corect"""
    out = []
    for part in s.split(", "):
        m = re.fullmatch(r"(\d+) (?:de )?(culori|culoare|variante|variantă)( în total)?", part)
        if not m:
            return None
        n = int(m[1])
        word = count(n, "colour", "colours") if m[2].startswith("cul") else count(n, "variant", "variants")
        out.append(word + (" in total" if m[3] else ""))
    return ", ".join(out)


def sentence_en(s):
    if s in DESC_EN:
        return DESC_EN[s]
    m = ORIGINAL_RX.match(s)
    if m:
        base = f"Genuine {m[1]} product, new and sealed."
        if not m[2]:
            return base
        a = avail_en(m[2])
        return a and f"{base} Available in {a}."
    return None


# ------------------------------------------------------------------------------------------------ specificații, indicii
def sizes_en(s):
    """„38, 40, 41 și 42 mm” → „38mm, 40mm, 41mm and 42mm”"""
    nums = re.findall(r"\d+", s)
    parts = [f"{n}mm" for n in nums]
    return parts[0] if len(parts) == 1 else ", ".join(parts[:-1]) + " and " + parts[-1]


def text_en(s):
    """etichete și valori scurte: specificații, indicii de mărime, modele"""
    if s in LABELS_EN:
        return LABELS_EN[s]
    if s in VALUES_EN:
        return VALUES_EN[s]
    if s in COLOR_EN:
        return COLOR_EN[s]
    m = re.fullmatch(r"(\d+) mm", s)
    if m:
        return f"{m[1]}mm"
    m = re.fullmatch(r"[Cc]arcase de ([\d, și]+) mm", s)
    if m:
        return f"{sizes_en(m[1])} cases"
    m = re.fullmatch(r"pentru carcase de ([\d, și]+) mm", s)
    if m:
        return f"fits {sizes_en(m[1])} cases"
    if re.fullmatch(r"\d+", s):
        return s
    if re.fullmatch(r"(iPhone|iPad)[\w .,\"()‑-]*", s) and " și " not in s and " pentru " not in s:
        return model_en(s)
    return None


def value_en(key, val, label):
    if key == "color":
        return COLOR_BY_KEY.get(val) or COLOR_EN.get(label)
    return text_en(label)


# ------------------------------------------------------------------------------------------------ generare
def accessories(cat):
    return [p for p in cat["products"] if p["category"] == "accesorii"]


def build(cat):
    labels, values, products, missing = {}, {}, {}, []

    def need(what, ro, en):
        if en is None:
            missing.append(f"{what}: {ro}")
        return en if en is not None else ro

    for p in accessories(cat):
        e = {"name": need(p["slug"] + " name", p["name"], name_en(p["name"]))}
        if p.get("tagline"):
            e["tagline"] = need(p["slug"] + " tagline", p["tagline"], sentence_en(p["tagline"]))
        if p.get("description"):
            e["description"] = [need(p["slug"] + " description", s, sentence_en(s)) for s in p["description"]]
        if p.get("highlights"):
            e["highlights"] = [need(p["slug"] + " highlight", s, sentence_en(s) or text_en(s)) for s in p["highlights"]]
        if p.get("specs"):
            e["specs"] = []
            for g in p["specs"]:
                labels[g["group"]] = need("spec group", g["group"], text_en(g["group"]))
                rows = []
                for r in g["rows"]:
                    k = labels[r["k"]] = need("spec label", r["k"], text_en(r["k"]))
                    rows.append({"k": k, "v": need("spec value", r["v"], text_en(r["v"]))})
                e["specs"].append({"group": labels[g["group"]], "rows": rows})
        if p.get("in_box"):
            e["in_box"] = [need(p["slug"] + " in_box", s, name_en(s) or text_en(s)) for s in p["in_box"]]
        if p.get("badge"):
            e["badge"] = need(p["slug"] + " badge", p["badge"], text_en(p["badge"]))
        if p.get("group"):
            labels[p["group"]] = need("group", p["group"], text_en(p["group"]))
        if p.get("warranty"):
            labels[p["warranty"]] = need("warranty", p["warranty"], text_en(p["warranty"]))
        opts = {}
        for o in p["options"]:
            labels[o["label"]] = need("option label", o["label"], text_en(o["label"]))
            oe = {"label": OPTION_LABEL_EN.get(o["key"], labels[o["label"]])}
            if o.get("note"):
                oe["note"] = need(p["slug"] + " note", o["note"], NOTES_EN.get(o["note"]))
            vals = {}
            for v in o["values"]:
                en = vals[v["value"]] = need(f"{p['slug']} {o['key']}={v['value']}", v["label"], value_en(o["key"], v["value"], v["label"]))
                values.setdefault(v["label"], COLOR_EN.get(v["label"], en) if o["key"] == "color" else en)
                if v.get("hint"):  # notița valorii (ex. „pentru carcase de …”) stă în values sub „<cheie>@note”
                    vals[v["value"] + "@note"] = values[v["hint"]] = need("hint", v["hint"], text_en(v["hint"]))
            oe["values"] = vals
            opts[o["key"]] = oe
        if opts:
            e["options"] = opts
        products[p["slug"]] = e

    # toate culorile accesoriilor și Beats (și cele care nu sunt azi în catalog) + celelalte valori cunoscute
    for ro in set(COLOR_RO.values()):
        values.setdefault(ro, need("colour", ro, COLOR_EN.get(ro)))
    for ro, en in VALUES_EN.items():
        values.setdefault(ro, en)
    for p in cat["products"]:  # culorile căștilor Beats (categoria airpods) vin din același tabel de culori
        if p["category"] != "accesorii" and p["slug"].startswith(("beats", "powerbeats")):
            for o in p["options"]:
                for v in o["values"]:
                    if o["key"] == "color":
                        values.setdefault(v["label"], need(f"{p['slug']} color", v["label"], value_en("color", v["value"], v["label"])))
    for k, v in LABELS_EN.items():
        labels.setdefault(k, v)
    out = {"labels": dict(sorted(labels.items())), "values": dict(sorted(values.items())),
           "products": dict(sorted(products.items()))}
    return out, missing


RO_LEFT = re.compile(r"[ăâîșțĂÂÎȘȚ]|\b(pentru|și|cu|din|husă|curea|culori|variante|disponibil|produs)\b", re.I)


def check(cat, out):
    """fiecare accesoriu are intrare și fiecare text are traducere (în produs sau prin labels/values)"""
    errs = []
    L, V, P = out["labels"], out["values"], out["products"]
    for p in accessories(cat):
        e = P.get(p["slug"])
        if not e:
            errs.append(f"lipsește produsul {p['slug']}")
            continue
        for f in ("name", "tagline"):
            if p.get(f) and not e.get(f):
                errs.append(f"{p['slug']}: lipsește {f}")
        for f in ("description", "highlights", "in_box"):
            if len(p.get(f) or []) != len(e.get(f) or []):
                errs.append(f"{p['slug']}: {f} are alt număr de elemente")
        if len(p.get("specs") or []) != len(e.get("specs") or []):
            errs.append(f"{p['slug']}: specs diferă ca formă")
        for g, ge in zip(p.get("specs") or [], e.get("specs") or []):
            if len(g["rows"]) != len(ge["rows"]):
                errs.append(f"{p['slug']}: rândurile din {g['group']} diferă")
        for o in p["options"]:
            oe = (e.get("options") or {}).get(o["key"], {})
            if not (oe.get("label") or L.get(o["label"])):
                errs.append(f"{p['slug']}: eticheta opțiunii {o['label']}")
            if o.get("note") and not oe.get("note"):
                errs.append(f"{p['slug']}: nota opțiunii {o['key']}")
            for v in o["values"]:
                if not ((oe.get("values") or {}).get(v["value"]) or V.get(v["label"])):
                    errs.append(f"{p['slug']}: valoarea {o['key']}={v['label']}")
                if v.get("hint") and not ((oe.get("values") or {}).get(v["value"] + "@note") or V.get(v["hint"])):
                    errs.append(f"{p['slug']}: indiciul {v['hint']}")
        # textele englezești nu trebuie să mai conțină română
        for s in _strings(e):
            if RO_LEFT.search(s):
                errs.append(f"{p['slug']}: pare română → {s}")
    for k, v in list(L.items()) + list(V.items()):
        if RO_LEFT.search(v):
            errs.append(f"label/value pare română: {k} → {v}")
    return errs


def _strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, list):
        for y in x:
            yield from _strings(y)
    elif isinstance(x, dict):
        for y in x.values():
            yield from _strings(y)


def main():
    cat = json.load(open(CATALOG, encoding="utf-8"))
    out, missing = build(cat)
    errs = missing + check(cat, out)
    if "--check" not in sys.argv:
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"scris {os.path.relpath(OUT, ROOT)}", file=sys.stderr)
    print(f"accesorii: {len(out['products'])}/{len(accessories(cat))}, labels: {len(out['labels'])}, "
          f"values: {len(out['values'])}, probleme: {len(errs)}", file=sys.stderr)
    for e in errs:
        print("  !!", e, file=sys.stderr)
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    main()
