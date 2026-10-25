"""Hakee Tilastokeskuksen Paavo-postinumeroalueet ja kuntanimet ja kirjoittaa sovelluksen offline-taulukon
app-mobile/src/main/assets/postinumeroalueet.tsv. Kaytto:

    python -I tools/postinumeroalueet.py 2026

Aineisto: Tilastokeskus, Paavo - postinumeroalueittainen avoin tieto, lisenssi CC BY 4.0.
Rivit: postinumero, alueen nimi, kunta (aineiston kuntanimi sellaisenaan, esim. "Maarianhamina - Mariehamn")."""
import json
import os
import sys
import urllib.request

WFS = 'https://geo.stat.fi/geoserver/{ws}/wfs?service=WFS&version=2.0.0&request=GetFeature' \
      '&typeName={ws}:{layer}&outputFormat=application/json&propertyName={props}'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app-mobile', 'src', 'main', 'assets',
                   'postinumeroalueet.tsv')


def features(ws, layer, props):
    url = WFS.format(ws=ws, layer=layer, props=props)
    with urllib.request.urlopen(url, timeout=120) as r:
        return [f['properties'] for f in json.load(r)['features']]


def main():
    year = sys.argv[1] if len(sys.argv) > 1 else '2026'
    kunnat = {p['kunta']: p['nimi'].strip() for p in features('tilastointialueet', f'kunta4500k_{year}', 'kunta,nimi')}
    alueet = features('postialue', f'pno_{year}', 'posti_alue,nimi,kunta')
    rows = []
    for p in sorted(alueet, key=lambda p: p['posti_alue']):
        code, name = p['posti_alue'], p['nimi'].strip()
        if len(code) != 5 or not code.isdigit() or not name or any(c in name for c in '\t\r\n'):
            raise SystemExit(f'odottamaton rivi: {p}')
        rows.append(f'{code}\t{name}\t{kunnat[p["kunta"]]}')
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(f'# Postinumeroalueet {year}: Tilastokeskus, Paavo - postinumeroalueittainen avoin tieto, CC BY 4.0\n')
        f.write('# https://stat.fi/fi/palvelut/tilastodatapalvelut/paikkatietoaineistot/postinumeroalueittainen-paikkatieto-paavo\n')
        f.write('# Lisenssi: https://creativecommons.org/licenses/by/4.0/ - sovellus lyhentää nimiä: kunnan nimi\n')
        f.write('# poistetaan alusta (Turku Keskus -> Keskus) ja lyhenteet avataan (kk -> Kirkonkylä, as -> Asemanseutu).\n')
        f.write('# postinumero<TAB>nimi<TAB>kunta\n')
        f.write('\n'.join(rows) + '\n')
    print(f'{len(rows)} aluetta -> {os.path.normpath(OUT)}')


if __name__ == '__main__':
    main()
