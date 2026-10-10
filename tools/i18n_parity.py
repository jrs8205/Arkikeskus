"""Tarkistaa, etta values/, values-fi/ ja values-sv/ sisaltavat samat string/plurals-avaimet
samoilla muotoiluargumenteilla. Kaytto:

    python -I tools/i18n_parity.py app-mobile/src/main/res app-mobile/core/res [--strict]

Ilman --strict tiedosto, joka puuttuu kokonaan fi/sv-kansiosta, on vain varoitus (siirtymavaihe).
Paluuarvo 0 = ok, 1 = virheita."""
import os
import re
import sys

ENTRY = re.compile(r'<(string|plurals)\s+name="([^"]+)"([^>]*)>([\s\S]*?)</\1>')
ARG = re.compile(r'%(\d+)\$[-0-9.]*[sdfx]')


def parse(path):
    out = {}
    with open(path, encoding='utf-8') as f:
        text = f.read()
    for m in ENTRY.finditer(text):
        kind, name, attrs, body = m.groups()
        args = tuple(sorted(set(ARG.findall(body))))
        translatable = 'translatable="false"' not in attrs
        out[f'{kind}:{name}'] = (args, translatable, body.strip())
    return out


def check(res, strict):
    errors, warnings = [], []
    base = os.path.join(res, 'values')
    if not os.path.isdir(base):
        return errors, warnings
    files = sorted(f for f in os.listdir(base) if 'strings' in f and f.endswith('.xml'))
    for fname in files:
        en = parse(os.path.join(base, fname))
        for loc in ('values-fi', 'values-sv'):
            p = os.path.join(res, loc, fname)
            if not os.path.exists(p):
                (errors if strict else warnings).append(f'{loc}/{fname} puuttuu kokonaan')
                continue
            tr = parse(p)
            for k, (args, translatable, body) in en.items():
                if not translatable:
                    if k in tr:
                        errors.append(f'{loc}/{fname}: {k} on translatable=false, poista kaannos')
                    continue
                if k not in tr:
                    errors.append(f'{loc}/{fname}: puuttuu {k}')
                elif tr[k][0] != args:
                    errors.append(f'{loc}/{fname}: {k} argumentit {tr[k][0]} != en {args}')
                elif loc == 'values-fi' and tr[k][2] == body and len(body) > 3 \
                        and not re.fullmatch(r'[\d\s%°.,:/–\-+]+|[A-Z0-9ÄÖÅ][A-Za-z0-9ÄÖÅäöå .\-]{1,30}', body) \
                        and re.sub(r'%\d+\$[-0-9.]*[sdfx]|[\s°C·•.,:/–\-()]+|\b(min|avg|max)\b|%', '', body):
                    warnings.append(f'{loc}/{fname}: {k} on sama kuin en ("{body[:40]}") - kaantamatta?')
            for k in tr:
                if k not in en:
                    errors.append(f'{loc}/{fname}: ylimaarainen {k} (ei values/-tiedostossa)')
        # Avainten jarjestys: sama kuin en-tiedostossa
        for loc in ('values-fi', 'values-sv'):
            p = os.path.join(res, loc, fname)
            if os.path.exists(p):
                order_en = [k for k, v in en.items() if v[1]]
                order_tr = [k for k in parse(p) if k in en]
                if order_tr != [k for k in order_en if k in order_tr]:
                    warnings.append(f'{loc}/{fname}: avainten jarjestys poikkeaa en-tiedostosta')
    # fi/sv-tiedostot, joilla ei ole en-vastinetta
    for loc in ('values-fi', 'values-sv'):
        d = os.path.join(res, loc)
        if os.path.isdir(d):
            for f in os.listdir(d):
                if 'strings' in f and f.endswith('.xml') and f not in files:
                    errors.append(f'{loc}/{f} ilman values/-vastinetta')
    return errors, warnings


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    strict = '--strict' in sys.argv
    total_err = 0
    for res in args:
        errors, warnings = check(res, strict)
        for w in warnings:
            print(f'VAROITUS {res}: {w}')
        for e in errors:
            print(f'VIRHE {res}: {e}')
        total_err += len(errors)
    print('OK' if total_err == 0 else f'{total_err} virhetta')
    sys.exit(0 if total_err == 0 else 1)


if __name__ == '__main__':
    main()
