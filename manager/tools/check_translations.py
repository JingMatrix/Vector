import re, sys, pathlib, xml.etree.ElementTree as ET

# Run from anywhere: the resources are found relative to this file, not to the shell.
base = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent / 'src/main/res'
def units(path):
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        if el.tag == 'string':
            if el.get('translatable') == 'false': continue
            out[el.get('name')] = ''.join(el.itertext())
        elif el.tag == 'plurals':
            out[el.get('name')] = {i.get('quantity'): ''.join(i.itertext()) for i in el}
    return out

english = {}
for f in sorted((base/'values').glob('strings*.xml')):
    english[f.name] = units(f)

# scripts that must not appear in a given language's text
SCRIPT = {
 'zh-rCN': r'[一-鿿]', 'zh-rTW': r'[一-鿿]',
 'ru': r'[Ѐ-ӿ]', 'uk': r'[Ѐ-ӿ]',
}
CJK = re.compile(r'[一-鿿぀-ヿ]')
problems = 0
LOCALE = re.compile(r'^[a-z]{2}(-r[A-Z]{2})?$')
for d in sorted(base.glob('values-*')):
    lang = d.name[len('values-'):]
    if not LOCALE.match(lang):  # night, v31, sw600dp … are not languages
        continue
    for name, en in english.items():
        f = d/name
        if not f.exists():
            print(f"  {lang}/{name}: MISSING"); problems += 1; continue
        tr = units(f)
        missing = set(en) - set(tr)
        extra = set(tr) - set(en)
        if missing: print(f"  {lang}/{name}: missing {sorted(missing)}"); problems += 1
        if extra:   print(f"  {lang}/{name}: unknown {sorted(extra)}"); problems += 1
        for k, v in tr.items():
            src = en.get(k)
            if src is None: continue
            # Which %1$-style indices appear, plus how many bare %s/%d one item can carry. Bare
            # arguments have to be counted as well as indexed ones: a translation holding a %d the
            # source does not have prints the literal "%d" to the user, and looking only for the
            # indexed form walks straight past it. Indices go in a set and bare ones are maxed over
            # the items rather than summed, because a plural's item count is a property of the
            # language -- Polish has four forms where English has two -- and is not a mismatch.
            def args(t):
                items = [t] if isinstance(t, str) else list(t.values())
                idx = set()
                bare = 0
                for i in items:
                    idx |= set(re.findall(r'%(\d+)\$[sd]', i))
                    bare = max(bare, len(re.findall(r'%[sd]', i)))
                return sorted(idx), bare
            sv = v if isinstance(v, str) else ' '.join(v.values())
            if args(v) != args(src):
                print(f"  {lang}/{name}:{k}: placeholder mismatch {args(src)} -> {args(v)}"); problems += 1
            # A Latin-script translation containing Han or kana is almost always a line
            # pasted from the wrong file — the mistake that put a Chinese sentence inside the
            # Russian strings. Languages that legitimately use those scripts are exempt.
            if not lang.startswith(('zh', 'ja', 'ko')) and CJK.search(sv):
                print(f"  {lang}/{name}:{k}: stray CJK text"); problems += 1
print("problems:", problems)
sys.exit(1 if problems else 0)
