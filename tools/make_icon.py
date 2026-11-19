# -*- coding: utf-8 -*-
"""Generoi Arkikeskuksen adaptive launcher iconin arkikeskus.png:stä.
Foreground = valkoinen A (läpinäkyvä), background = sininen->vihrea gradientti (näytteistetty).
Tuottaa foreground-tiheysversiot + Play Store 512 + esikatselut. Monochrome-kerros (teemakuvakkeet)
tehdään tämän jälkeen skriptillä make_monochrome_icon.py, joka lukee tässä kirjoitetun foreground-kuvan.

Käyttö: python tools/make_icon.py <polku lähdekuvaan arkikeskus.png>
Lähdekuva ei ole repossa. Kaikki kirjoitettavat polut ovat tämän työpuun sisällä (icon_paths.py)."""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw

from icon_paths import RELEASES as OUT, RES, TOOLS as PREV

if len(sys.argv) != 2:
    sys.exit("Kaytto: python tools/make_icon.py <polku lahdekuvaan arkikeskus.png>")
SRC = sys.argv[1]

src = Image.open(SRC).convert("RGBA")
W, H = src.size
arr = np.array(src).astype(int)
rgb = arr[:, :, :3]
alpha = arr[:, :, 3]
mn = rgb.min(axis=2)
mx = rgb.max(axis=2)
sat = mx - mn
opaque = alpha > 128

# ---------- näytteistä gradientin päätevärit ----------
colored = opaque & (sat > 40) & (mx > 50)
yy, xx = np.mgrid[0:H, 0:W]
diag = yy + xx  # pieni = vasen-yla (sininen), iso = oikea-ala (vihrea)


def region_avg(cond):
    m = colored & cond
    return tuple(int(round(rgb[:, :, c][m].mean())) for c in range(3))


blue = region_avg(diag < W * 0.55)
mid = region_avg(np.abs(diag - W) < W * 0.10)
green = region_avg(diag > W * 1.45)
print("BLUE  =", "#%02X%02X%02X" % blue)
print("MID   =", "#%02X%02X%02X" % mid)
print("GREEN =", "#%02X%02X%02X" % green)

# ---------- erota A (lapinakyva knockout opaakissa neliossa) ----------
# Lahde = opaakki gradienttinelio, A leikattu lapinakyvaksi. Tayta nelion reiat ->
# A-reika = taytetty_nelio AND NOT opaakki (ei marginaali, ei kulmat).
from scipy import ndimage
opaque_mask = alpha > 128
filled_sq = ndimage.binary_fill_holes(opaque_mask)
a_mask = filled_sq & ~opaque_mask
a_mask = ndimage.binary_dilation(a_mask, iterations=2)  # pehmenna reunaa
inv = (255 - alpha).astype(np.uint8)
fg_alpha = np.where(a_mask, inv, 0).astype(np.uint8)
print("A-reika pikselit =", int((fg_alpha > 30).sum()))
fg = np.zeros((H, W, 4), np.uint8)
fg[:, :, 0:3] = 255
fg[:, :, 3] = fg_alpha
fg_img = Image.fromarray(fg, "RGBA")

ys, xs = np.where(fg_alpha > 30)
y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
A = fg_img.crop((x0, y0, x1 + 1, y1 + 1))
aw, ah = A.size
print("A bbox =", (x0, y0, x1, y1), "size", (aw, ah))

# ---------- adaptiiviset foreground + monochrome tiheydet ----------
TARGET_FRAC = 0.54  # A:n suurin sivu = 54% kankaasta (~2 dp pienempi: A ei enaa ihan reunoissa)
DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def place_A(size, solid_black=False):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scale = (size * TARGET_FRAC) / max(aw, ah)
    nw, nh = max(1, round(aw * scale)), max(1, round(ah * scale))
    a = A.resize((nw, nh), Image.LANCZOS)
    if solid_black:
        b = Image.new("RGBA", a.size, (0, 0, 0, 255))
        b.putalpha(a.split()[3])
        a = b
    canvas.alpha_composite(a, ((size - nw) // 2, (size - nh) // 2))
    return canvas


for name, size in DENS.items():
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    place_A(size).save(os.path.join(d, "ic_launcher_foreground.png"))
# Monochrome-kerros (teemakuvakkeet) tehdaan erikseen foreground-kerroksesta: tools/make_monochrome_icon.py.
# Pelkka A ei riita teemakuvakkeeksi, koska kaynnistin korvaa taustan omallaan.
print("foreground-tiheydet kirjoitettu; aja sitten tools/make_monochrome_icon.py")


# ---------- gradientti-tausta (PNG Play Storelle + esikatseluun) ----------
def gradient(size, c0, c1, cmid=None):
    g = np.zeros((size, size, 3), np.uint8)
    yy2, xx2 = np.mgrid[0:size, 0:size]
    t = (yy2 + xx2) / (2.0 * (size - 1))
    for c in range(3):
        if cmid is None:
            g[:, :, c] = (c0[c] + (c1[c] - c0[c]) * t).astype(np.uint8)
        else:
            lo = t < 0.5
            v = np.where(lo, c0[c] + (cmid[c] - c0[c]) * (t * 2),
                         cmid[c] + (c1[c] - cmid[c]) * ((t - 0.5) * 2))
            g[:, :, c] = v.astype(np.uint8)
    return Image.fromarray(g, "RGB").convert("RGBA")


# Play Store 512 (taysi: A gradientin paalla)
ps = gradient(512, blue, green, mid)
fa = place_A(512)  # A samalla mittakaavalla
ps.alpha_composite(fa)
os.makedirs(OUT, exist_ok=True)
ps.convert("RGB").save(os.path.join(OUT, "arkikeskus_play_store_512.png"))
print("Play Store 512 kirjoitettu")

# ---------- esikatselut (ympyra + squircle, 108dp-kokoinen turva-alue huomioiden) ----------
S = 432
full = gradient(S, blue, green, mid)
full.alpha_composite(place_A(S))


def masked(shape):
    m = Image.new("L", (S, S), 0)
    dr = ImageDraw.Draw(m)
    if shape == "circle":
        dr.ellipse([0, 0, S - 1, S - 1], fill=255)
    else:
        dr.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    out = full.copy()
    out.putalpha(m)
    bg = Image.new("RGBA", (S, S), (240, 240, 240, 255))
    bg.alpha_composite(out)
    return bg.convert("RGB")


masked("circle").save(os.path.join(PREV, "icon_preview_circle.png"))
masked("squircle").save(os.path.join(PREV, "icon_preview_squircle.png"))
# adaptiivinen "full bleed" ilman maskia (nakee mita launcher rajaa)
full.convert("RGB").save(os.path.join(PREV, "icon_preview_full.png"))
print("esikatselut kirjoitettu")
print("DONE")
