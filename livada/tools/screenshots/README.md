# Capturi de ecran + galerie

`capture.mjs` fotografiază toate paginile și stările magazinului (RO + EN), ale panoului de administrare (RO + RU)
și emailurile (RO + EN), în tema luminoasă și întunecată, pe desktop (1440×900) și pe telefon (390×844, 2×).
Rezultatul ajunge în `screenshots/` (ignorat de git): `index.html` (galeria din `gallery/`), `assets/`,
`manifest.json` + `manifest.js`, `img/<limbă>-<temă>-<dispozitiv>/<id>.webp`, `thumb/…` și `photos/` (fotografiile produselor).

```sh
npm run screens                                   # totul (~2 000 de capturi)
npm run screens -- --limit=2 --products=sample    # verificare rapidă: 2 pagini din fiecare grup, toate variantele
npm run screens -- --only=admin --lang=ru --theme=dark --device=mobile
npm run screens -- --match='^checkout\.' --force  # refă doar checkout-ul
```

Opțiuni: `--only=store|admin|email|<grup>` (grupuri: store, catalog, product, checkout, account, auth, info, admin, email),
`--lang=ro,en,ru`, `--theme=light,dark`, `--device=desktop,mobile`, `--limit=N` (pagini pe grup), `--products=all|sample`,
`--match=<regex pe id>`, `--workers=N`, `--force` (refă și capturile existente), `--out=<dosar>`, `--no-tiles` (hartă fără dale reale).

- Pornește propriul server pe un port liber, cu o bază de date temporară și datele demo (`seed:demo`), apoi pregătește
  un client confirmat cu trei comenzi, un cont neconfirmat, coșuri de vizitator și doi administratori (RO și RU).
- Rulările sunt reluabile: fișierele existente sunt sărite; manifestul cuprinde mereu tot ce există pe disc.
- Necesită Playwright cu Chromium (local, global sau `PLAYWRIGHT_PATH`) și Python 3 cu Pillow (`webp.py` face conversia în WebP).
- Capturile mai înalte de 16 000 px (limita WebP e 16 383 px) se salvează micșorate în `file`, iar la rezoluție completă în `parts`.

## Versiunea online

Un artefact publicat admite cel mult 511 fișiere și 256 MB, iar setul complet are ~5000 de imagini și ~320 MB.
`python3 tools/screenshots/pack_web.py screenshots <ieșire>` face din el o versiune de ~240 MB în ~290 de fișiere:
miniaturile se lipesc în foi WebP 4×4, capturile întregi se micșorează la cât se vede pe ecran (calculator 920 px,
telefon 450 px) și stau în pachete JSON de câțiva MB, fotografiile mari devin 640 px. `gallery/pk.js` le extrage în
browser la cerere (și eliberează foile și pachetele nefolosite), galeria rămâne aceeași; online lipsesc doar
„Deschide originalul”, descărcarea și linkul spre o vedere. `batches.json` din ieșire împarte fișierele în loturi
de publicare (≤ 60 MB fiecare).
