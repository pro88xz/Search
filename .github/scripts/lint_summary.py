"""Prints Android lint's findings from its XML report: counts per check, then every error in full."""
import sys
import xml.etree.ElementTree as ET
from collections import Counter

try:
    root = ET.parse(sys.argv[1]).getroot()
except (OSError, ET.ParseError) as e:
    print("no lint report:", e)
    sys.exit(0)
issues = root.findall("issue")
count = Counter((i.get("severity"), i.get("id")) for i in issues)
print(f"{len(issues)} lint issues")
for (sev, iid), n in sorted(count.items(), key=lambda kv: (kv[0][0] != "Fatal", kv[0][0] != "Error", -kv[1])):
    print(f"  {sev:12} {iid:40} {n}")
for i in issues:
    if i.get("severity") in ("Error", "Fatal"):
        loc = i.find("location")
        where = f"{loc.get('file', '?').split('/app/')[-1]}:{loc.get('line', '?')}" if loc is not None else "?"
        print(f"{i.get('severity').upper()} {i.get('id')} {where}: {i.get('message')}")
