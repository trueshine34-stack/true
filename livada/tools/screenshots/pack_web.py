# -*- coding: utf-8 -*-
"""
Versiunea online a galeriei de capturi: aceleași pagini și imagini, în câteva sute de fișiere.

  python3 tools/screenshots/pack_web.py [screenshots] [ieșire]

Un artefact publicat admite cel mult 511 fișiere și 256 MB, iar setul complet are ~5000 de imagini și ~320 MB.
  - miniaturile (capturi și fotografii) se lipesc în foi WebP de 4×4 (sprite-uri); browserul decupează miniatura;
  - capturile întregi se micșorează la cât se vede pe ecran (calculator 920 px, telefon 450 px lățime) și stau,
    câte câțiva MB, în pachete JSON {cale: base64}, grupate după variantă și secțiune — galeria descarcă doar ce deschizi;
  - fotografiile mari (1000 px) devin 640 px, tot în pachete JSON.
În manifest, `pk` spune unde e fiecare imagine: [pachet, x, y, l, h] într-o foaie sau [pachet] într-un JSON;
assets/pk.js le extrage în browser. Ieșirea: index.html (fără <html>/<head>, cum îl cere publicarea), manifest.js,
assets/, p/*, și batches.json — fișierele împărțite în loturi de publicare (≤ 60 MB, ≤ 240 de fișiere).
"""
import base64
import io
import json
import os
import re
import shutil
import sys
from concurrent.futures import ProcessPoolExecutor

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SRC = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'screenshots'))
OUT = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'screenshots-web'))
WIDTH = {'desktop': 920, 'mobile': 450}
PHOTO = 640
PACK_JSON = 5_000_000          # octeți de imagine pe pachet JSON (≈ 6,7 MB cu base64)
SHEET = (4, 4)                 # miniaturi pe foaie: coloane × rânduri
BATCH_BYTES, BATCH_FILES = 60_000_000, 240


def webp(im, q):
    buf = io.BytesIO()
    im.save(buf, 'WEBP', quality=q, method=4)
    return buf.getvalue()


def shrink(job):
    """imagine mare → WebP la lățimea dată; întoarce (cale, octeți, lățime, înălțime)"""
    rel, width = job
    src = os.path.join(SRC, rel)
    im = Image.open(src)
    if not width or im.width <= width:
        with open(src, 'rb') as f:
            return rel, f.read(), im.width, im.height
    h = round(im.height * width / im.width)
    im = im.convert('RGBA' if im.mode in ('RGBA', 'LA', 'P') else 'RGB').resize((width, h), Image.LANCZOS)
    return rel, webp(im, 74), width, h


def sheet(job):
    """foaie de miniaturi: celule de mărimea celei mai mari miniaturi; întoarce (octeți, [(cale, x, y, l, h)])"""
    rels = job
    alpha = any(Image.open(os.path.join(SRC, r)).mode in ('RGBA', 'LA', 'P') for r in rels[:1])  # fotografiile au fundal transparent
    ims = [Image.open(os.path.join(SRC, r)).convert('RGBA' if alpha else 'RGB') for r in rels]
    cw, ch = max(i.width for i in ims), max(i.height for i in ims)
    cols = min(SHEET[0], len(ims))
    rows = (len(ims) + cols - 1) // cols
    out = Image.new('RGBA' if alpha else 'RGB', (cw * cols, ch * rows), (0, 0, 0, 0) if alpha else (255, 255, 255))
    pos = []
    for k, (r, im) in enumerate(zip(rels, ims)):
        x, y = (k % cols) * cw, (k // cols) * ch
        out.paste(im, (x, y))
        pos.append((r, x, y, im.width, im.height))
    return webp(out, 72), pos


def main():
    man = json.load(open(os.path.join(SRC, 'manifest.json'), encoding='utf-8'))
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(os.path.join(OUT, 'p'))
    os.makedirs(os.path.join(OUT, 'assets', 'fonts'))
    shots, photos = man['shots'], man.get('photos', [])

    # grupele: variantă × secțiune (capturi), categorie (fotografii)
    thumb_groups, full_groups = {}, {}
    for s in shots:
        var = s['file'].split('/')[1]
        thumb_groups.setdefault(f't-{var}-{s["group"]}', []).append(s['thumb'])
        full_groups.setdefault(f'f-{var}-{s["group"]}', []).append((s['file'], WIDTH.get(s['device'])))
    for ph in photos:
        cat = ph.get('category') or 'other'
        thumb_groups.setdefault(f'pt-{cat}', []).append(ph['thumb'])
        full_groups.setdefault(f'pf-{cat}', []).append((ph['file'], PHOTO))

    packs, index = [], {}
    with ProcessPoolExecutor() as ex:
        # 1. foile de miniaturi
        jobs, names = [], []
        per = SHEET[0] * SHEET[1]
        for key in sorted(thumb_groups):
            rels = thumb_groups[key]
            for i in range(0, len(rels), per):
                jobs.append(rels[i:i + per])
                names.append(f'{key}-{i // per}')
        for name, (data, pos) in zip(names, ex.map(sheet, jobs)):
            fn = f'p/{len(packs):03d}-{name}.webp'
            with open(os.path.join(OUT, fn), 'wb') as f:
                f.write(data)
            for r, x, y, w, h in pos:
                index[r] = [len(packs), x, y, w, h]
            packs.append(fn)

        # 2. imaginile mari, micșorate, în pachete JSON
        dims = {}
        for key in sorted(full_groups):
            res = list(ex.map(shrink, full_groups[key], chunksize=4))
            chunk, size, part = {}, 0, 0
            def flush():
                nonlocal part
                if not chunk:
                    return
                fn = f'p/{len(packs):03d}-{key}-{part}.json'
                with open(os.path.join(OUT, fn), 'w') as f:
                    json.dump(chunk, f, separators=(',', ':'))
                for r in chunk:
                    index[r] = [len(packs)]
                packs.append(fn)
                part += 1
            for rel, data, w, h in res:
                dims[rel] = (w, h)
                if chunk and size + len(data) > PACK_JSON:
                    flush()
                    chunk, size = {}, 0
                chunk[rel] = base64.b64encode(data).decode('ascii')
                size += len(data)
            flush()

    for s in shots:
        s['w'], s['h'] = dims[s['file']]
        for k in ('parts', 'scaled', 'fullW', 'fullH'):
            s.pop(k, None)
    man['packs'], man['pk'], man['web'] = packs, index, True
    with open(os.path.join(OUT, 'manifest.js'), 'w', encoding='utf-8') as f:
        f.write('window.MANIFEST = ' + json.dumps(man, ensure_ascii=False, separators=(',', ':')) + ';\n')

    # 3. pagina și resursele galeriei
    G = os.path.join(HERE, 'gallery')
    for f in ('gallery.css', 'gallery.js', 'pk.js'):
        shutil.copy(os.path.join(G, f), os.path.join(OUT, 'assets', f))
    for f in ('inter-latin', 'inter-latin-ext', 'inter-cyrillic', 'instrument-serif-italic-latin'):
        shutil.copy(os.path.join(ROOT, 'public', 'fonts', f + '.woff2'), os.path.join(OUT, 'assets', 'fonts', f + '.woff2'))
    page = open(os.path.join(G, 'index.html'), encoding='utf-8').read()
    head = re.search(r'<head>([\s\S]*?)</head>', page).group(1)
    body = re.search(r'<body>([\s\S]*?)</body>', page).group(1)
    head = re.sub(r'<meta charset[^>]*>\s*|<meta name="viewport"[^>]*>\s*', '', head)
    head = re.sub(r'<title>[^<]*</title>', '<title>Скриншоты Livada</title>', head)
    body = body.replace('<script src="manifest.js"></script>', '<script src="manifest.js"></script>\n<script src="assets/pk.js"></script>')
    with open(os.path.join(OUT, 'index.html'), 'w', encoding='utf-8') as f:
        f.write(head.strip() + '\n' + body.strip() + '\n')

    # 4. loturile de publicare: resursele și foile de miniaturi întâi, apoi pachetele mari
    files = []
    for dp, _, fs in os.walk(OUT):
        for fn in fs:
            rel = os.path.relpath(os.path.join(dp, fn), OUT)
            if rel not in ('index.html', 'batches.json'):
                files.append(rel)
    files.sort(key=lambda r: (not r.startswith(('assets', 'manifest')), not r.endswith('.webp'), r))
    batches, cur, size = [], [], 0
    for rel in files:
        n = os.path.getsize(os.path.join(OUT, rel))
        if cur and (size + n > BATCH_BYTES or len(cur) >= BATCH_FILES):
            batches.append(cur)
            cur, size = [], 0
        cur.append(rel)
        size += n
    if cur:
        batches.append(cur)
    json.dump(batches, open(os.path.join(OUT, 'batches.json'), 'w'), indent=0)

    total = sum(os.path.getsize(os.path.join(OUT, r)) for r in files) + os.path.getsize(os.path.join(OUT, 'index.html'))
    print(f'{len(shots)} capturi, {len(photos)} fotografii → {len(packs)} pachete, {len(files) + 1} fișiere, '
          f'{total / 1e6:.1f} MB, {len(batches)} loturi de publicare → {OUT}')


if __name__ == '__main__':
    main()
