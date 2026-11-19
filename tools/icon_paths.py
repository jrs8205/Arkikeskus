# -*- coding: utf-8 -*-
"""Kuvakegeneraattorien yhteiset polut.

make_icon.py kirjoittaa foreground-kerroksen ja make_monochrome_icon.py lukee sen. Molempien pitää
käyttää saman työpuun resursseja, joten polut johdetaan yhdestä paikasta: tämän tiedoston sijainnista.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app-mobile", "src", "main", "res")
TOOLS = os.path.join(ROOT, "tools")
RELEASES = os.path.join(ROOT, "releases")
