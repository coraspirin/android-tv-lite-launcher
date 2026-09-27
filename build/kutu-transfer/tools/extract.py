"""Rebuilds the served HTML out of Page.java by concatenating its string literals.

Lets the browser page be syntax-checked and executed before the APK is ever installed:

    python extract.py && node --check app.js && node --check app-en.js && node browse-test.js

It writes the Turkish page as gate/app.* and the English one as gate-en/app-en.*.

browse-test.js needs jsdom (npm install jsdom), which is a test-only dependency and is
deliberately not part of the app - the app itself still has none.
"""
import io, re, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'app', 'src', 'main', 'java', 'local', 'kutu',
                   'transfer', 'Page.java')
OUT = HERE

text = io.open(SRC, encoding='utf-8').read()

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
ESC = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\'}


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == '\\':
            i += 1
            n = s[i]
            if n == 'u':
                out.append(chr(int(s[i + 1:i + 5], 16)))
                i += 4
            else:
                out.append(ESC.get(n, n))
        else:
            out.append(c)
        i += 1
    return ''.join(out)


def constant(name):
    """Everything from '= ' after the name up to the terminating ';' at line start-ish."""
    start = text.index('String ' + name + ' =')
    depth = text.index(';\n', start)
    body = text[start:depth]
    # drop // comment lines so their quotes are not mistaken for literals
    body = '\n'.join(l for l in body.split('\n') if not l.strip().startswith('//'))
    return ''.join(unescape(m.group(1)) for m in LIT.finditer(body))


# the words, from the w("key", "tr", "en") table; a value may be split over two literals
WORDS = {'tr': {}, 'en': {}}
for m in re.finditer(r'w\("(\w+)",(.*?)\);\n', text, re.S):
    body = m.group(2)
    # split on the top-level comma that separates tr from en: the last '",'
    cut = body.rindex('",') + 1
    WORDS['tr'][m.group(1)] = ''.join(unescape(x.group(1)) for x in LIT.finditer(body[:cut]))
    WORDS['en'][m.group(1)] = ''.join(unescape(x.group(1)) for x in LIT.finditer(body[cut:]))


def fill(page, words):
    def sub(m):
        if m.group(1) not in words:
            sys.exit('page text missing: ' + m.group(1))
        return words[m.group(1)]
    return re.sub(r'\{\{(\w+)\}\}', sub, page)


head = constant('HEAD')
for lang in ('tr', 'en'):
    suffix = '' if lang == 'tr' else '-en'
    for name in ('GATE', 'APP'):
        # HEAD is referenced by name, not inlined, so prepend it
        page = fill(head + constant(name), WORDS[lang])
        path = os.path.join(OUT, name.lower() + suffix + '.html')
        io.open(path, 'w', encoding='utf-8').write(page)

        scripts = re.findall(r'<script>(.*?)</script>', page, re.S)
        js = '\n'.join(scripts)
        jspath = os.path.join(OUT, name.lower() + suffix + '.js')
        io.open(jspath, 'w', encoding='utf-8').write(js)
        print('%-5s %s html=%5d bytes  js=%5d bytes  -> %s' % (name, lang, len(page), len(js), jspath))
