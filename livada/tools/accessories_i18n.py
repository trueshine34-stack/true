# -*- coding: utf-8 -*-
"""Traduceri DE → RO pentru catalogul de accesorii Apple (culori, mărimi, lungimi, conectori)."""

# denumirile Apple au variante oficiale în română la revânzători (iSTYLE, eMAG); unde nu există, folosim
# o traducere fidelă; numele de colecție Nike/Beats rămân în engleză, ca pe ambalaj
COLOR_RO = {
    "After Dark Black": "After Dark Black", "Alpengrau": "Gri alpin", "Anthrazit": "Antracit", "Aquamarin": "Acvamarin",
    "Beats Schwarz": "Negru Beats", "Bergnacht": "Noapte montană", "Black Unity - Unity Connection": "Black Unity – Unity Connection",
    "Black Unity - Unity Rhythm": "Black Unity – Unity Rhythm", "Blassmint": "Mentă pal", "Blau": "Albastru",
    "Blau/Schwarz": "Albastru/Negru", "Brillantgold": "Auriu strălucitor", "Burgunder": "Burgundia",
    "Burgunder Kariert": "Burgundia carouri", "Chalk Calm": "Chalk Calm", "Chambrayblau": "Albastru chambray",
    "Cremeweiß": "Alb crem", "Denim": "Denim", "Diamantschwarz": "Negru diamant", "Dunkelbronze": "Bronz închis",
    "Dunkeloliv": "Măsliniu închis", "Dunkelumbra": "Umbră închisă", "Eisblau": "Albastru gheață", "Espresso": "Espresso",
    "Essential White": "Essential White", "Felsblau": "Albastru stâncă", "Flammenblau": "Albastru flacără",
    "Flut Blau": "Albastru maree", "Fuchsia": "Fucsia", "Gipfelstein": "Piatră de vârf", "Granitgrau": "Gri granit",
    "Gravelgrau": "Gri pietriș", "Green Spark": "Green Spark", "Grün": "Verde", "Guavepink": "Roz guava",
    "Halbtransparentes Grau": "Gri translucid", "Halbtransparentes Schwarz": "Negru translucid",
    "Halbtransparentes Seegrasgrün": "Verde alge translucid", "Hellblau": "Albastru deschis", "Hellgrau": "Gri deschis",
    "Hellmoosgrün": "Verde mușchi deschis", "Hellrosa": "Roz deschis", "Hellumbra Kariert": "Umbră deschisă carouri",
    "Hellviolett": "Violet deschis", "Himmel": "Cer", "Himmelblau": "Albastru cer", "Himmelblau mit Himmelblau": "Albastru cer",
    "Hyazinthenblau": "Albastru zambilă", "Hyperviolett": "Hiperviolet", "Indigo": "Indigo", "Kalkrosa": "Roz cretă",
    "Kalkstein": "Calcar", "Kamelbraun": "Cămilă", "Kieselrosa": "Roz pietricică", "Klarblau": "Albastru clar",
    "Knallorange": "Portocaliu aprins", "Leuchtendes Lavendel": "Lavandă luminoasă", "Leuchtorange": "Portocaliu neon",
    "Limonade": "Limonadă", "Magenta": "Magenta", "Mandarine": "Mandarină", "Mandel": "Migdală", "Maritimblau": "Albastru marin",
    "Mattschwarz": "Negru mat", "Mitternacht": "Miezul nopții", "Mulberry": "Dud", "Nachtschwarz": "Negru noapte",
    "Natur": "Natural", "Navy": "Bleumarin", "Navyblau": "Bleumarin", "Nebelviolett": "Violet ceață", "Neongelb": "Galben neon",
    "Nevada Orange": "Nevada Orange", "Oliv": "Oliv", "Orange": "Portocaliu", "Pavement Grey": "Pavement Grey",
    "Pfingstrose": "Bujor", "Pflaume": "Prună", "Pink": "Roz", "Pink mit Pink": "Roz", "Polarstern": "Steaua polară",
    "Polarviolett": "Violet polar", "Powerpink": "Power Pink", "Pride Edition": "Pride Edition", "Rauchgrau": "Gri fum",
    "Rocky Mountain Blau": "Rocky Mountain Blue", "Rot": "Roșu", "Salbei": "Salvie", "Sand": "Nisip", "Sandstein": "Gresie",
    "Schatten": "Umbră", "Schieferblau": "Albastru ardezie", "Schwarz": "Negru", "Schwarz / Gold": "Negru/Auriu",
    "Schwarz mit Schwarz": "Negru", "Schwarze Multi-Touch Oberfläche": "Suprafață Multi‑Touch neagră",
    "Schwarze Tasten": "Taste negre", "Seegrün": "Verde mare", "Siena": "Siena", "Silber": "Argintiu",
    "Space Schwarz": "Negru spațial", "Space Grau": "Gri spațial", "Steingrau": "Gri piatră", "Sternfrucht": "Carambola",
    "Sturmblau": "Albastru furtună", "Sturmgrau": "Gri furtună", "Sunset Lila": "Liliac apus", "Taupe": "Taupe",
    "Terracotta": "Teracotă", "Tonbraun": "Maro lut", "Transparent": "Transparent", "Transparent-Rot": "Roșu transparent",
    "Treibsand": "Nisip mișcător", "Ultramarin": "Ultramarin", "Vanille": "Vanilie", "Veilchen": "Viorea",
    "Violett": "Violet", "Volt": "Volt", "Warmgelb": "Galben cald", "Wassermelone": "Pepene roșu", "Weiß": "Alb",
    "Weiße Multi-Touch Oberfläche": "Suprafață Multi‑Touch albă", "Weiße Tasten": "Taste albe", "Winterblau": "Albastru iarnă",
    "Wolke": "Nor", "Wolke mit Wolke": "Nor", "Wolkenrosa": "Roz nor", "Wüstensand": "Nisip de deșert", "Yuzugelb": "Galben yuzu",
    "Zartes Agavengrün": "Verde agavă pal", "Signalrot": "Roșu semnal", "Energie-Grau": "Gri energie",
    "Turbo-Blau": "Albastru turbo", "Power-Schwarz": "Negru Power", "Blitz-Rot": "Roșu fulger", "Champagner": "Șampanie",
    "Mitternacht ": "Miezul nopții", "midnight": "Miezul nopții", "terracotta": "Teracotă", "orange": "Portocaliu",
}

SIZE_RO = {"S/M": "S/M", "M/L": "M/L", "Small": "S", "Medium": "M", "Large": "L",
           "One Size (passt den meisten Handgelenken)": "Mărime unică"}

LENGTH_RO = {"1 Meter": "1 m", "1,8 m": "1,8 m", "2 Meter": "2 m", "3 Meter": "3 m", "1,5 m": "1,5 m", "20 cm": "20 cm"}

CONNECTION_RO = {"3,5 mm Kopfhörerstecker": "Jack 3,5 mm", "Lightning Anschluss": "Lightning", "USB‑C": "USB‑C", "USB-C": "USB‑C"}

FINISH_RO = {"Natur": "Titan natural", "Schwarz": "Titan negru"}

LANGUAGE_RO = {"Englisch (International)": "Engleză (internațională)", "Englisch (USA)": "Engleză (SUA)",
               "Englisch (Großbritannien)": "Engleză (Marea Britanie)", "Deutsch": "Germană", "Französisch": "Franceză",
               "Ungarisch": "Maghiară", "Russisch": "Rusă", "Ukrainisch": "Ucraineană", "Italienisch": "Italiană",
               "Spanisch": "Spaniolă"}
