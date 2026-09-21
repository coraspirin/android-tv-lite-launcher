"""Rebuilds the served HTML out of Page.java by concatenating its string literals.

Lets the browser page be syntax-checked and executed before the APK is ever installed:

    python extract.py && node --check app.js && node browse-test.js

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


head = constant('HEAD')
for name in ('GATE', 'APP'):
    page = constant(name).replace('HEAD_PLACEHOLDER', '')
    # HEAD is referenced by name, not inlined, so prepend it
    page = head + page
    path = os.path.join(OUT, name.lower() + '.html')
    io.open(path, 'w', encoding='utf-8').write(page)

    scripts = re.findall(r'<script>(.*?)</script>', page, re.S)
    js = '\n'.join(scripts)
    jspath = os.path.join(OUT, name.lower() + '.js')
    io.open(jspath, 'w', encoding='utf-8').write(js)
    print('%-5s html=%5d bytes  js=%5d bytes  -> %s' % (name, len(page), len(js), jspath))
