# -*- coding: utf-8 -*-
"""Generează identitatea vizuală Livada (SVG) din fontul Inter (OFL). Necesită fontTools.

   python3 tools/build_brand.py
"""
import os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "public", "img", "brand")
INK, LEAF, PAPER, MIST, LEAF_LIGHT = "#0E1411", "#1F7A4D", "#FFFFFF", "#F4F5F1", "#8EE0AE"


def wordmark(text="livada", wght=640, tracking=-0.035):
    f = TTFont(os.path.join(ROOT, "public/fonts/inter-latin.woff2"))
    inst = instancer.instantiateVariableFont(f, {"wght": wght})
    gs, cmap, hmtx, upm = inst.getGlyphSet(), inst.getBestCmap(), inst["hmtx"], inst["head"].unitsPerEm
    x, parts, bp = 0, [], BoundsPen(gs)
    for ch in text:
        gn = cmap[ord(ch)]
        pen = SVGPathPen(gs)
        gs[gn].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
        gs[gn].draw(TransformPen(bp, (1, 0, 0, -1, x, 0)))
        parts.append(pen.getCommands())
        x += hmtx[gn][0] + tracking * upm
    return " ".join(parts), bp.bounds


def leaf(x, y, s, r=0.12):
    """pătrat cu colțurile stânga-sus și dreapta-jos rotunjite complet = frunză"""
    R, q = s, s * r
    return (f"M{x+R:.3f},{y:.3f} L{x+s-q:.3f},{y:.3f} Q{x+s:.3f},{y:.3f} {x+s:.3f},{y+q:.3f} L{x+s:.3f},{y+s-R+0.001:.3f} "
            f"A{R},{R} 0 0 1 {x+s-R+0.001:.3f},{y+s:.3f} L{x+q:.3f},{y+s:.3f} Q{x:.3f},{y+s:.3f} {x:.3f},{y+s-q:.3f} "
            f"L{x:.3f},{y+R:.3f} A{R},{R} 0 0 1 {x+R:.3f},{y:.3f} Z")


def mark_shapes(ink, leaf_c, ox=0, oy=0):
    t, g = 14, 4
    sq = lambda x, y: f'<rect x="{ox+x}" y="{oy+y}" width="{t}" height="{t}" rx="3.5" fill="{ink}"/>'
    return sq(0, 0) + sq(0, t + g) + sq(t + g, t + g) + f'<path d="{leaf(ox+t+g, oy, t)}" fill="{leaf_c}"/>'


def write(name, svg):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(svg)


def main():
    os.makedirs(OUT, exist_ok=True)
    d, (x0, y0, x1, y1) = wordmark()
    W, H = x1 - x0, y1 - y0
    # semnul (32×32)
    write("mark.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">{mark_shapes(INK, LEAF)}</svg>')
    write("mark-light.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">{mark_shapes(PAPER, LEAF_LIGHT)}</svg>')
    # wordmark scalat la înălțimea x a semnului: lockup 32 px înalt
    scale = 26 / H
    ww = W * scale
    gap = 10
    total_w = 32 + gap + ww

    def lockup(ink, leaf_c, word_c):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_w:.2f} 32" width="{total_w:.0f}" height="32" role="img" aria-label="Livada">'
                f'{mark_shapes(ink, leaf_c)}<g transform="translate({32 + gap} 3) scale({scale:.6f}) translate({-x0:.2f} {-y0:.2f})">'
                f'<path d="{d}" fill="{word_c}"/></g></svg>')

    write("logo.svg", lockup(INK, LEAF, INK))
    write("logo-light.svg", lockup(PAPER, LEAF_LIGHT, PAPER))
    write("wordmark.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0:.0f} {y0:.0f} {W:.0f} {H:.0f}"><path d="{d}" fill="{INK}"/></svg>')
    # favicon: dală întunecată cu semnul alb/verde, adaptivă la temă
    fav = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><style>@media (prefers-color-scheme:dark){{.bg{{fill:#1C2420}}}}</style>'
           f'<rect class="bg" width="48" height="48" rx="12" fill="{INK}"/>{mark_shapes(PAPER, LEAF_LIGHT, 8, 8)}</svg>')
    write("favicon.svg", fav)
    # icon pentru ecranul principal iOS / Android (pătrat plin, fără colțuri — sistemul le rotunjește)
    write("app-icon.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180"><rect width="180" height="180" fill="{INK}"/>'
          f'<g transform="translate(42 42) scale(3)">{mark_shapes(PAPER, LEAF_LIGHT)}</g></svg>')
    # logo pentru email (randat ca PNG 2×)
    write("email-logo.svg", lockup(INK, LEAF, INK).replace(f'width="{total_w:.0f}" height="32"', f'width="{total_w * 4:.0f}" height="128"'))
    print("brand ok", round(total_w, 2))


if __name__ == "__main__":
    main()
