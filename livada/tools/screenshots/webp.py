"""Conversie PNG → WebP pentru capturile de ecran (folosit de capture.mjs).

Proces de lungă durată: citește de pe stdin câte o comandă JSON pe linie și răspunde cu o linie JSON.
  {"op": "convert", "src": "a.png", "out": "img/x.webp", "thumb": "thumb/x.webp", "q": 72, "tq": 70}
  {"op": "dims", "files": ["img/x.webp", ...]}
WebP are limita de 16383 px pe latură: o captură mai înaltă se salvează micșorată (cât să încapă) în `out`,
iar la rezoluția completă în bucăți `out` → `x.p1.webp`, `x.p2.webp` … (câmpul `parts` din răspuns).
"""
import json
import os
import sys

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
MAX = 16383
PART = 16000
THUMB_W, THUMB_H = 480, 640


def save(im, path, q):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + '.tmp'
    im.save(tmp, 'WEBP', quality=q, method=4)
    os.replace(tmp, path)
    return os.path.getsize(path)


def convert(c):
    im = Image.open(c['src'])
    im = im.convert('RGB')
    w, h = im.size
    res = {'ok': True, 'fullW': w, 'fullH': h, 'bytes': 0}
    out = c['out']
    base = out[:-5]
    # bucățile unei capturi mai vechi (pagina poate fi mai scurtă acum)
    d = os.path.dirname(out) or '.'
    if os.path.isdir(d):
        for f in os.listdir(d):
            if f.startswith(os.path.basename(base) + '.p') and f.endswith('.webp') and f[len(os.path.basename(base)) + 2:-5].isdigit():
                os.remove(os.path.join(d, f))
    if h > MAX or w > MAX:
        k = min(MAX / h, MAX / w)
        small = im.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
        res['bytes'] += save(small, out, c.get('q', 72))
        res['w'], res['h'] = small.size
        res['scaled'] = round(k, 4)
        parts = []
        for i, y in enumerate(range(0, h, PART)):
            p = f'{base}.p{i + 1}.webp'
            piece = im.crop((0, y, w, min(h, y + PART)))
            res['bytes'] += save(piece, p, c.get('q', 72))
            parts.append({'path': p, 'w': piece.size[0], 'h': piece.size[1]})
        res['parts'] = parts
    else:
        res['bytes'] += save(im, out, c.get('q', 72))
        res['w'], res['h'] = w, h
    if c.get('thumb'):
        # miniatura: 480 px lățime, partea de sus a paginii (max. 480×640)
        crop_h = min(h, round(THUMB_H * w / THUMB_W))
        top = im.crop((0, 0, w, crop_h))
        tw = min(THUMB_W, w)
        th = max(1, round(crop_h * tw / w))
        res['bytes'] += save(top.resize((tw, th), Image.LANCZOS), c['thumb'], c.get('tq', 70))
    if c.get('removeSrc'):
        os.remove(c['src'])
    return res


def dims(c):
    out = {}
    for f in c['files']:
        try:
            with Image.open(f) as im:
                out[f] = list(im.size)
        except Exception:
            out[f] = None
    return {'ok': True, 'dims': out}


for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        cmd = json.loads(line)
        r = convert(cmd) if cmd.get('op', 'convert') == 'convert' else dims(cmd)
    except Exception as e:  # noqa: BLE001 — raportăm eroarea către capture.mjs
        r = {'ok': False, 'error': f'{type(e).__name__}: {e}'}
    sys.stdout.write(json.dumps(r) + '\n')
    sys.stdout.flush()
