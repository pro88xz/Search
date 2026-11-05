#!/usr/bin/env python3
"""Extract a Chrome-for-Android blueprint from a sparse Chromium checkout.

Outputs JSON to OUT_DIR:
  strings.json   resource name -> English text (from .grd/.grdp)
  tokens.json    dimens / integers / colors / fractions per module and qualifier, resolved
  settings.json  settings tree starting at main_preferences.xml
  features.json  ChromeFeatureList constants with default state where found
"""
import json, os, re, sys, glob
import xml.etree.ElementTree as ET

SRC = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
ANDROID = '{http://schemas.android.com/apk/res/android}'

def rel(p):
    return os.path.relpath(p, SRC)

def walk(pattern_fn):
    for root, dirs, files in os.walk(SRC):
        if '/.git' in root:
            continue
        for f in files:
            p = os.path.join(root, f)
            if pattern_fn(p):
                yield p

# ---------- strings ----------
strings = {}
def flatten(el):
    out = el.text or ''
    for c in el:
        if c.tag == 'ph':
            ex = c.find('ex')
            name = c.get('name', '')
            out += '{' + name + '}'
        else:
            out += flatten(c)
        out += c.tail or ''
    return out

for p in walk(lambda p: p.endswith(('.grd', '.grdp')) and '/translations/' not in p):
    try:
        tree = ET.parse(p)
    except ET.ParseError:
        continue
    for m in tree.iter('message'):
        name = m.get('name', '')
        if not name.startswith('IDS_'):
            continue
        key = name[4:].lower()
        txt = re.sub(r'\s+', ' ', flatten(m)).strip()
        # grd messages sometimes have formatter_data / variants; keep first
        strings.setdefault(key, txt)
json.dump(strings, open(os.path.join(OUT, 'strings.json'), 'w'), indent=0, sort_keys=True)

def S(ref):
    if not ref:
        return None
    if ref.startswith('@string/'):
        return strings.get(ref[8:], ref)
    return ref

# ---------- tokens ----------
tokens = {}  # name -> list of {value, file, qualifier, kind}
for p in walk(lambda p: re.search(r'/res/values[^/]*/[^/]+\.xml$', p) is not None):
    qual = re.search(r'/res/(values[^/]*)/', p).group(1)
    try:
        tree = ET.parse(p)
    except ET.ParseError:
        continue
    root = tree.getroot()
    if root.tag != 'resources':
        continue
    for el in root:
        kind = el.tag
        if kind == 'item':
            kind = el.get('type', 'item')
        if kind not in ('dimen', 'integer', 'color', 'fraction', 'bool'):
            continue
        name = el.get('name')
        val = (el.text or '').strip()
        tokens.setdefault(kind + '/' + name, []).append({'value': val, 'file': rel(p), 'qualifier': qual})

def resolve(kind, name, qual='values', depth=0):
    entries = tokens.get(kind + '/' + name)
    if not entries or depth > 10:
        return None
    pick = [e for e in entries if e['qualifier'] == qual] or [e for e in entries if e['qualifier'] == 'values'] or entries
    v = pick[0]['value']
    m = re.match(r'@(?:\*android:)?(dimen|integer|color|fraction|bool)/(\S+)', v)
    if m:
        r = resolve(m.group(1), m.group(2), qual, depth + 1)
        return r if r is not None else v
    return v

resolved = {}
for k, entries in tokens.items():
    kind, name = k.split('/', 1)
    quals = sorted(set(e['qualifier'] for e in entries))
    resolved[k] = {
        'values': {q: resolve(kind, name, q) for q in quals},
        'files': sorted(set(e['file'] for e in entries)),
    }
json.dump(resolved, open(os.path.join(OUT, 'tokens.json'), 'w'), indent=1, sort_keys=True)

# ---------- settings tree ----------
xml_index = {os.path.splitext(os.path.basename(p))[0]: p
             for p in walk(lambda p: '/res/xml/' in p and p.endswith('.xml'))}
java_index = {}
for p in walk(lambda p: p.endswith(('.java', '.kt')) and '/src/' in p):
    java_index.setdefault(os.path.splitext(os.path.basename(p))[0], p)

def fragment_xmls(cls):
    short = cls.split('.')[-1]
    p = java_index.get(short)
    if not p:
        return None, []
    src = open(p, encoding='utf-8', errors='replace').read()
    return rel(p), sorted(set(re.findall(r'R\.xml\.(\w+)', src)))

seen = set()
def parse_screen(xml_name, depth=0):
    p = xml_index.get(xml_name)
    if not p or xml_name in seen or depth > 6:
        return {'xml': xml_name, 'missing': p is None}
    seen.add(xml_name)
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError:
        return {'xml': xml_name, 'error': 'parse'}
    def node(el):
        tag = el.tag.split('.')[-1]
        n = {'type': tag}
        for a in ('key', 'title', 'summary', 'fragment', 'summaryOn', 'summaryOff', 'defaultValue'):
            v = el.get(ANDROID + a)
            if v is not None:
                n[a] = S(v) if a in ('title', 'summary', 'summaryOn', 'summaryOff') else v
        if n.get('fragment'):
            jsrc, xmls = fragment_xmls(n['fragment'])
            n['fragment_source'] = jsrc
            n['children_screens'] = [parse_screen(x, depth + 1) for x in xmls if x not in seen]
        kids = [node(c) for c in el]
        if kids:
            n['children'] = kids
        return n
    return {'xml': xml_name, 'file': rel(p), 'items': [node(c) for c in root]}

settings = parse_screen('main_preferences')
# also every other preference xml not reached from main (sub-screens built in code)
others = {}
for name in sorted(xml_index):
    if name not in seen and ('pref' in name or 'setting' in name):
        others[name] = parse_screen(name)
json.dump({'main': settings, 'other_screens': others}, open(os.path.join(OUT, 'settings.json'), 'w'), indent=1)

# ---------- features ----------
feat = {}
cfl = glob.glob(os.path.join(SRC, 'chrome/browser/flags/android/java/src/**/ChromeFeatureList.java'), recursive=True)
if cfl:
    src = open(cfl[0]).read()
    for m in re.finditer(r'public static final String (\w+)\s*=\s*"([^"]+)"', src):
        feat[m.group(2)] = {'const': m.group(1)}
defaults = {}
for p in walk(lambda p: p.endswith(('.cc', '.h'))):
    s = open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'BASE_FEATURE\(\s*(?:k\w+\s*,\s*)?(?:"([^"]+)"\s*,\s*)?base::FEATURE_(ENABLED|DISABLED)_BY_DEFAULT', s):
        pass
    for m in re.finditer(r'BASE_FEATURE\(\s*k(\w+)\s*,\s*(?:"([^"]+)"\s*,\s*)?base::FEATURE_(ENABLED|DISABLED)_BY_DEFAULT', s):
        name = m.group(2) or m.group(1)
        defaults[name] = {'default': m.group(3).lower(), 'file': rel(p)}
for k, v in feat.items():
    if k in defaults:
        v.update(defaults[k])
json.dump({'chrome_feature_list': feat, 'all_defined_in_checkout': defaults},
          open(os.path.join(OUT, 'features.json'), 'w'), indent=1, sort_keys=True)

print('strings', len(strings), 'tokens', len(resolved), 'settings screens', len(seen) + len(others),
      'features', len(feat), 'with defaults', sum(1 for v in feat.values() if 'default' in v))
