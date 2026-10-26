"""Usage: python3 parse_curated.py README.md app/src/main/assets/curated_stations.json

Parse deroverda/recommended-radio-streams README (CC0) into a flat station list."""
import json, re, sys
src, out = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().splitlines()
link = re.compile(r'\[([^\]]+)\]\(([^)\s]+)\)')
tail = re.compile(r'((?:\s*/?\s*\[[^\]]+\]\([^)\s]+\))+)\s*$')
genre, in_dir, stations, skipped = None, False, [], 0
for ln in lines:
    if ln.startswith('## '):
        in_dir = 'Station Directory' in ln or 'Experimental' in ln
        if 'Experimental' in ln:
            genre = 'Experimental & Nerdy'
        continue
    if ln.startswith('### ') and in_dir:
        genre = re.sub(r'<a .*', '', ln[4:]).strip(); continue
    if not in_dir or not ln.startswith('- '):
        continue
    body = ln[2:].strip()
    fav = body.startswith('⭐')
    body = body.lstrip('⭐ ').strip()
    m = re.match(r'\[([^\]]+)\]\(([^)\s]+)\):?\s*(.*)$', body)
    if not m:
        continue
    name, site, rest = m.groups()
    t = tail.search(rest)
    if not t:
        skipped += 1; continue
    desc = rest[:t.start()].strip()
    if '(down)' in desc.lower():
        skipped += 1; continue
    desc = re.sub(r'\*', '', link.sub(r'\1', desc)).strip()
    streams = link.findall(t.group(1))
    for label, url in streams:
        url = url.rstrip(',')
        if not url.startswith('http'):
            continue
        nm = name if (len(streams) == 1 or label.lower() == 'stream') else f"{name}: {label}"
        stations.append({"id": "cur:" + url, "name": nm, "url": url, "homepage": site, "description": desc,
                         "genre": genre, "favorite": fav, "source": "curated"})
seen=set(); stations=[s for s in stations if not (s["url"] in seen or seen.add(s["url"]))]
json.dump({"source": "github.com/deroverda/recommended-radio-streams (CC0)", "stations": stations},
          open(out, "w"), ensure_ascii=False, separators=(",", ":"))
print(len(stations), "stations;", skipped, "skipped")
from collections import Counter
print(Counter(s["genre"] for s in stations))
