"""Tulostaa kaikki kaannokset Markdown-taulukkona katselmusta varten.

    python -I tools/i18n_table.py app-mobile/src/main/res app-mobile/core/res > kaannokset.md
"""
import html
import os
import re
import sys

ENTRY = re.compile(r'<(string|plurals)\s+name="([^"]+)"([^>]*)>([\s\S]*?)</\1>')
ITEM = re.compile(r'<item\s+quantity="(\w+)">([\s\S]*?)</item>')


def clean(body):
    body = body.strip()
    items = ITEM.findall(body)
    if items:
        body = ' / '.join(f'{q}: {v.strip()}' for q, v in items)
    body = html.unescape(body).replace('\\\'', "'").replace('\\"', '"').replace('\\n', '⏎')
    return body.replace('|', '\\|').replace('\n', ' ')


def parse(path):
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as f:
        text = f.read()
    return {f'{k}:{n}': (clean(b), 'translatable="false"' in a) for k, n, a, b in ENTRY.findall(text)}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    total = 0
    for res in sys.argv[1:]:
        base = os.path.join(res, 'values')
        for fname in sorted(f for f in os.listdir(base) if 'strings' in f and f.endswith('.xml')):
            en = parse(os.path.join(base, fname))
            fi = parse(os.path.join(res, 'values-fi', fname))
            sv = parse(os.path.join(res, 'values-sv', fname))
            print(f'\n### `{res}/{fname}` ({len(en)} avainta)\n')
            print('| avain | fi | sv | en |')
            print('|---|---|---|---|')
            for k, (en_text, fixed) in en.items():
                name = k.split(':', 1)[1] + (' (plurals)' if k.startswith('plurals') else '')
                if fixed:
                    print(f'| `{name}` | — | — | {en_text} (ei käännetä) |')
                else:
                    print(f'| `{name}` | {fi.get(k, ("**PUUTTUU**",))[0]} | {sv.get(k, ("**PUUTTUU**",))[0]} | {en_text} |')
                total += 1
    print(f'\nYhteensä {total} avainta.')


if __name__ == '__main__':
    main()
