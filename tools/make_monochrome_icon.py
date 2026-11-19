# -*- coding: utf-8 -*-
"""Generoi adaptive iconin monochrome-kerroksen (teemakuvakkeet, Android 13+).

Teemakuvakkeissa käynnistin piirtää vain tämän kerroksen yhdellä värillä ja korvaa taustan omallaan.
Pelkkä A-kirjain ei siksi näytä Arkikeskuksen merkiltä: mukaan tarvitaan merkin pyöristetty neliö.
Kerros on täytetty pyöristetty neliö, josta A on leikattu pois (sama asetelma kuin värillisessä
kuvakkeessa: valkoinen A neliön päällä).

A:n muoto luetaan foreground-kerroksesta (mipmap-xxxhdpi/ic_launcher_foreground.png), joten kirjain
pysyy samana kuin värillisessä kuvakkeessa. Mitat ovat dp-yksikköinä 108 dp:n kankaalla; koko merkki
pysyy 66 dp:n turva-alueen (ympyrä, säde 33 dp) sisällä, jottei mikään käynnistimen maski leikkaa sitä.

Käyttö (repon juuresta tai mistä tahansa):  python tools/make_monochrome_icon.py
Kirjoittaa: app-mobile/src/main/res/mipmap-*/ic_launcher_monochrome.png ja tools/icon_preview_themed.png
"""
import math
import os

from PIL import Image, ImageDraw

from icon_paths import RES, ROOT, TOOLS

GLYPH_SRC = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
PREVIEW = os.path.join(TOOLS, "icon_preview_themed.png")

CANVAS_DP = 108.0
SAFE_RADIUS_DP = 33.0
SQUARE_DP = 48.0        # neliön sivu
CORNER_DP = 5.0         # kulman säde (vanhan kuvakkeen neliössä noin 8 % sivusta, tässä hieman pyöreämpi)
GLYPH_HEIGHT_DP = 32.0  # A:n korkeus: 2/3 neliön sivusta, kuten vanhassa kuvakkeessa
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SUPERSAMPLE = 4

# Kauimmainen piste keskipisteestä on kulman kaarella: sen pitää pysyä turva-alueella.
corner_reach = (SQUARE_DP / 2 - CORNER_DP) * math.sqrt(2) + CORNER_DP
assert corner_reach <= SAFE_RADIUS_DP, "nelio ei mahdu turva-alueelle: %.2f dp" % corner_reach
assert GLYPH_HEIGHT_DP < SQUARE_DP


def load_glyph():
    """A-kirjaimen peitto (L-kuva) rajattuna kirjaimen ääriviivoihin."""
    alpha = Image.open(GLYPH_SRC).convert("RGBA").split()[3]
    box = alpha.point(lambda v: 255 if v > 30 else 0).getbbox()
    return alpha.crop(box)


def render(size, glyph):
    """Monochrome-kerros kokoon size x size: musta pyöristetty neliö, josta A on leikattu pois."""
    big = size * SUPERSAMPLE
    px = big / CANVAS_DP
    center = big / 2.0
    half = SQUARE_DP * px / 2.0

    square = Image.new("L", (big, big), 0)
    ImageDraw.Draw(square).rounded_rectangle(
        [center - half, center - half, center + half - 1, center + half - 1], radius=CORNER_DP * px, fill=255)

    glyph_h = round(GLYPH_HEIGHT_DP * px)
    glyph_w = round(glyph.width * glyph_h / glyph.height)
    scaled = glyph.resize((glyph_w, glyph_h), Image.LANCZOS)
    cutout = Image.new("L", (big, big), 0)
    cutout.paste(scaled, (round(center - glyph_w / 2.0), round(center - glyph_h / 2.0)))

    alpha = Image.composite(Image.new("L", (big, big), 0), square, cutout)  # neliö miinus A
    alpha = alpha.resize((size, size), Image.LANCZOS)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.putalpha(alpha)
    return layer


def themed_preview(layer, background, tint):
    """Miltä kerros näyttää teemakuvakkeena: käynnistimen tausta + yksivärinen merkki, ympyrämaski."""
    size = layer.width
    view = round(size * 72 / CANVAS_DP)  # käynnistin näyttää kankaasta keskimmäiset 72 dp
    off = (size - view) // 2
    alpha = layer.split()[3].crop((off, off, off + view, off + view))
    icon = Image.new("RGBA", (view, view), background + (255,))
    icon.paste(Image.new("RGBA", (view, view), tint + (255,)), (0, 0), alpha)
    mask = Image.new("L", (view * SUPERSAMPLE, view * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, view * SUPERSAMPLE - 1, view * SUPERSAMPLE - 1], fill=255)
    icon.putalpha(mask.resize((view, view), Image.LANCZOS))
    return icon


def main():
    glyph = load_glyph()
    for name, size in DENSITIES.items():
        path = os.path.join(RES, "mipmap-" + name, "ic_launcher_monochrome.png")
        render(size, glyph).save(path, optimize=True)
        print("kirjoitettu", os.path.relpath(path, ROOT))

    layer = render(432, glyph)
    light = themed_preview(layer, (215, 227, 255), (0, 27, 62))
    dark = themed_preview(layer, (40, 71, 119), (215, 227, 255))
    gap = 24
    sheet = Image.new("RGBA", (light.width * 2 + gap * 3, light.height + gap * 2), (245, 245, 245, 255))
    sheet.alpha_composite(light, (gap, gap))
    dark_bg = Image.new("RGBA", (light.width + gap * 2, sheet.height), (24, 24, 28, 255))
    sheet.alpha_composite(dark_bg, (light.width + gap, 0))
    sheet.alpha_composite(dark, (light.width + gap * 2, gap))
    sheet.convert("RGB").save(PREVIEW)
    print("kirjoitettu", os.path.relpath(PREVIEW, ROOT))
    print("nelio %.0f dp, kulma %.0f dp, A %.0f dp; kulman etaisyys keskelta %.1f dp (turva-alue %.0f dp)"
          % (SQUARE_DP, CORNER_DP, GLYPH_HEIGHT_DP, corner_reach, SAFE_RADIUS_DP))


if __name__ == "__main__":
    main()
