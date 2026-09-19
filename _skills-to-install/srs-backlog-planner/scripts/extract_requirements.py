#!/usr/bin/env python3
"""Build a requirement inventory from a Markdown SRS.

Finds every requirement *definition* (a paragraph starting with **ID.** or a
table row whose first cell is the ID), records its section, phase, RFC 2119
strength and text, and reports IDs that are referenced but never defined,
defined twice, or gaps in numbering.

Usage:
  python3 extract_requirements.py SRS.md [-o plan/requirements.csv]
         [--id-regex REGEX] [--phase2-marker "Phase 2"]
Standard library only.
"""
import argparse, csv, re, sys
from collections import Counter, defaultdict

DEFAULT_ID = r"(?:FR|NFR)-[A-Z0-9]+-\d{3}|(?:CFG|API)-\d{3}"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("srs")
    ap.add_argument("-o", "--out", default="plan/requirements.csv")
    ap.add_argument("--id-regex", default=DEFAULT_ID)
    ap.add_argument("--phase2-marker", default="Phase 2")
    a = ap.parse_args()

    idre = re.compile(rf"\b({a.id_regex})\b")
    para_def = re.compile(rf"^\*\*({a.id_regex})\.?\*\*\.?\s*(.*)")
    row_def = re.compile(rf"^\|\s*`?({a.id_regex})`?\s*\|(.*)")

    lines = open(a.srs, encoding="utf-8").read().splitlines()
    section, reqs, refs = "", [], defaultdict(set)
    in_code = False
    i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("```"):
            in_code = not in_code
        if not in_code and ln.startswith("#"):
            section = ln.lstrip("#").strip()
        m = None if in_code else (para_def.match(ln) or row_def.match(ln))
        if m:
            rid, text = m.group(1), m.group(2)
            if ln.startswith("|"):
                text = " / ".join(c.strip() for c in text.strip().strip("|").split("|"))
            else:  # paragraph continues until blank line
                j = i + 1
                while j < len(lines) and lines[j].strip() and not para_def.match(lines[j]):
                    text += " " + lines[j].strip(); j += 1
            strength = next((k for k in ("MUST NOT", "MUST", "SHOULD", "MAY") if k in text), "")
            phase = "2" if a.phase2_marker.lower() in text.lower()[:120] else "1"
            reqs.append(dict(id=rid, area=rid.rsplit("-", 1)[0], section=section,
                             phase=phase, strength=strength, line=i + 1,
                             text=re.sub(r"\s+", " ", text)[:300]))
        for r in idre.findall(ln):
            refs[r].add(i + 1)
        i += 1

    # Placeholder IDs used in the conventions table (e.g. FR-QUE-014 as example) are
    # reported, not dropped: a human decides.
    defined = Counter(r["id"] for r in reqs)
    dupes = [k for k, v in defined.items() if v > 1]
    dangling = sorted(set(refs) - set(defined))
    gaps = []
    by_area = defaultdict(list)
    for k in defined:
        by_area[k.rsplit("-", 1)[0]].append(int(k.rsplit("-", 1)[1]))
    for area, nums in sorted(by_area.items()):
        nums.sort()
        for x, y in zip(nums, nums[1:]):
            if y - x > 1 and (y // 10 == x // 10):  # ignore jumps to a new decade block
                gaps.append(f"{area}: {x:03d}→{y:03d}")

    # Keep one row per ID; a requirement is Phase 2 if any of its definitions says so.
    merged = {}
    for r in reqs:
        if r["id"] in merged:
            m = merged[r["id"]]
            m["phase"] = max(m["phase"], r["phase"]); m["strength"] = m["strength"] or r["strength"]
        else:
            merged[r["id"]] = r
    reqs = list(merged.values())

    import os
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(reqs[0].keys()) if reqs else ["id"])
        w.writeheader(); w.writerows(reqs)

    total = len(defined)
    p2 = sum(1 for r in reqs if r["phase"] == "2")
    print(f"Requirements defined: {total}  (Phase 1: {total - p2}, Phase 2: {p2})")
    print("By area:", ", ".join(f"{k}={len(v)}" for k, v in sorted(by_area.items())))
    print("By strength:", dict(Counter(r["strength"] or "none" for r in reqs)))
    print(f"Wrote {a.out}")
    if dupes: print("\nDEFINED MORE THAN ONCE (merged; make one canonical):", ", ".join(sorted(dupes)))
    if dangling:
        print("\nREFERENCED BUT NEVER DEFINED:")
        for d in dangling: print(f"  {d}  (lines {sorted(refs[d])[:5]})")
    no_kw = [r["id"] for r in reqs if not r["strength"]]
    if no_kw: print("\nNO MUST/SHOULD/MAY (check testability):", ", ".join(no_kw))
    if gaps: print("\nNUMBERING GAPS (usually harmless):", "; ".join(gaps))

if __name__ == "__main__":
    sys.exit(main())
