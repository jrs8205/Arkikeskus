# -*- coding: utf-8 -*-
"""Generoi ilmoitusten tilapalkkikuvakkeen (pieni kuvake).

Androidin ohjeen mukaan ilmoituskuvake on 24 x 24 dp, kuvio mahtuu 22 x 22 dp:n alueelle ja täyttää sen
lähes kokonaan; järjestelmä käyttää kuvasta vain peiton (alfan) ja värittää sen itse. Käynnistimen
teemakuvakkeen kerros (ic_launcher_monochrome) ei sovi tähän: sen merkki on 108 dp:n kankaalla vain 48 dp,
joten 24 dp:hen pienennettynä siitä jää noin 11 dp ja kuvake näyttää tilapalkissa muita selvästi pienemmältä.

Merkki on sama kuin teemakuvakkeessa: täytetty pyöristetty neliö, josta A on leikattu pois. A:n muoto
luetaan foreground-kerroksesta, joten kirjain pysyy samana kuin sovelluksen kuvakkeessa.

Käyttö (repon juuresta tai mistä tahansa):  python tools/make_notification_icon.py
Kirjoittaa: app-mobile/src/main/res/drawable-*/mobile_ic_stat_arkikeskus.png ja
tools/icon_preview_notification.png
"""
import os

from PIL import Image, ImageDraw

from icon_paths import RES, ROOT, TOOLS

GLYPH_SRC = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
OLD_SRC = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_monochrome.png")
PREVIEW = os.path.join(TOOLS, "icon_preview_notification.png")
NAME = "mobile_ic_stat_arkikeskus.png"

CANVAS_DP = 24.0
OPTICAL_DP = 22.0       # ohjeen mukainen kuvion alue
SQUARE_DP = 20.0        # neliön sivu: täysi neliö on kuvioista raskain, joten 1 dp alueen reunasta sisään
CORNER_DP = 2.1         # sama suhde kuin teemakuvakkeessa (5 / 48)
GLYPH_HEIGHT_DP = SQUARE_DP * 2.0 / 3.0   # A:n korkeus 2/3 neliön sivusta, kuten teemakuvakkeessa
DENSITIES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
SUPERSAMPLE = 8

assert SQUARE_DP <= OPTICAL_DP <= CANVAS_DP


def load_glyph():
    """A-kirjaimen peitto (L-kuva) rajattuna kirjaimen ääriviivoihin."""
    alpha = Image.open(GLYPH_SRC).convert("RGBA").split()[3]
    box = alpha.point(lambda v: 255 if v > 30 else 0).getbbox()
    return alpha.crop(box)


def render(size, glyph, square_dp=SQUARE_DP):
    """Kuvake kokoon size x size: valkoinen pyöristetty neliö, josta A on leikattu pois."""
    big = size * SUPERSAMPLE
    px = big / CANVAS_DP
    center = big / 2.0
    half = square_dp * px / 2.0
    corner = CORNER_DP * square_dp / SQUARE_DP * px

    square = Image.new("L", (big, big), 0)
    ImageDraw.Draw(square).rounded_rectangle(
        [center - half, center - half, center + half - 1, center + half - 1], radius=corner, fill=255)

    glyph_h = round(square_dp * 2.0 / 3.0 * px)
    glyph_w = round(glyph.width * glyph_h / glyph.height)
    scaled = glyph.resize((glyph_w, glyph_h), Image.LANCZOS)
    cutout = Image.new("L", (big, big), 0)
    cutout.paste(scaled, (round(center - glyph_w / 2.0), round(center - glyph_h / 2.0)))

    alpha = Image.composite(Image.new("L", (big, big), 0), square, cutout)  # neliö miinus A
    alpha = alpha.resize((size, size), Image.LANCZOS)
    layer = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    layer.putalpha(alpha)
    return layer


def tinted(alpha, color=(255, 255, 255)):
    img = Image.new("RGBA", alpha.size, color + (0,))
    img.putalpha(alpha)
    return img


def main():
    glyph = load_glyph()
    for name, size in DENSITIES.items():
        folder = os.path.join(RES, "drawable-" + name)
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, NAME)
        render(size, glyph).save(path, optimize=True)
        print("kirjoitettu", os.path.relpath(path, ROOT))

    # Esikatselu: tilapalkin kokoinen rivi (24 dp, suurennettu), vasemmalta vanha kuvake, uusi kuvake ja
    # vertailuksi 20 dp:n ympyrä (järjestelmäkuvakkeiden tavallinen koko).
    cell = 240
    old = Image.open(OLD_SRC).convert("RGBA").split()[3].resize((cell, cell), Image.LANCZOS)
    new = render(cell, glyph).split()[3]
    ref = Image.new("L", (cell * 4, cell * 4), 0)
    r = 20.0 / CANVAS_DP * cell * 4 / 2.0
    ImageDraw.Draw(ref).ellipse([cell * 2 - r, cell * 2 - r, cell * 2 + r, cell * 2 + r], fill=255)
    ref = ref.resize((cell, cell), Image.LANCZOS)
    gap = 40
    sheet = Image.new("RGBA", (cell * 3 + gap * 4, cell + gap * 2), (20, 22, 28, 255))
    frame = ImageDraw.Draw(sheet)
    for i, alpha in enumerate((old, new, ref)):
        x = gap + i * (cell + gap)
        frame.rectangle([x, gap, x + cell - 1, gap + cell - 1], outline=(70, 74, 84, 255))
        sheet.alpha_composite(tinted(alpha), (x, gap))
    sheet.convert("RGB").save(PREVIEW)
    print("kirjoitettu", os.path.relpath(PREVIEW, ROOT))
    print("kangas %.0f dp, nelio %.0f dp, kulma %.1f dp, A %.1f dp" % (CANVAS_DP, SQUARE_DP, CORNER_DP, GLYPH_HEIGHT_DP))


if __name__ == "__main__":
    main()
