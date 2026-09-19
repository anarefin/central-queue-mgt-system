#!/usr/bin/env python3
"""Validate plan/BACKLOG.md against plan/requirements.csv.

Checks:
  1. Every Phase 1 requirement is owned by exactly one slice ("Requirements:").
     NFRs and cross-cutting IDs may instead appear under "Constraints:" in any
     number of slices, or under "Gates:" (CI-enforced) — at least once.
  2. Every Phase 2 requirement is either unmapped or listed under "Seams:".
  3. No slice cites an ID that is not defined in requirements.csv.
  4. Every "Depends on:" target exists and the dependency graph is acyclic.
  5. A slice never depends on a slice in a later milestone.
  6. Every slice has Requirements, Acceptance, and Tests fields and <= MAX ids.
Exit code 1 if any check fails, so it can run in CI.

Usage: python3 check_backlog.py [plan/BACKLOG.md] [plan/requirements.csv] [--max-ids 10]
"""
import csv, re, sys, argparse
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument("backlog", nargs="?", default="plan/BACKLOG.md")
ap.add_argument("reqs", nargs="?", default="plan/requirements.csv")
ap.add_argument("--max-ids", type=int, default=10)
ap.add_argument("--id-regex", default=r"(?:FR|NFR)-[A-Z0-9]+-\d{3}|(?:CFG|API)-\d{3}")
a = ap.parse_args()
IDRE = re.compile(rf"\b({a.id_regex})\b")
SLICE = re.compile(r"^#{2,4}\s+(S-[A-Z0-9]+-\d+)\b")
FIELD = re.compile(r"^\s*[-*]\s*\**([A-Za-z ]+?)\**\s*:\s*(.*)")

reqs = {r["id"]: r for r in csv.DictReader(open(a.reqs, encoding="utf-8"))}
slices, cur = {}, None
for ln in open(a.backlog, encoding="utf-8"):
    m = SLICE.match(ln)
    if m:
        cur = m.group(1); slices[cur] = defaultdict(str); continue
    if cur:
        f = FIELD.match(ln)
        if f: slices[cur][f.group(1).strip().lower()] += " " + f.group(2)

errs, warns = [], []
owner = defaultdict(list); soft = defaultdict(list); seams = set()
milestone = {}
for sid, f in slices.items():
    own = IDRE.findall(f["requirements"])
    for r in own: owner[r].append(sid)
    for r in IDRE.findall(f["constraints"] + f["gates"]): soft[r].append(sid)
    seams.update(IDRE.findall(f["seams"]))
    mm = re.search(r"M(\d+)", f["milestone"]); milestone[sid] = int(mm.group(1)) if mm else None
    if milestone[sid] is None: errs.append(f"{sid}: missing Milestone")
    for fld in ("requirements", "acceptance", "tests"):
        if not f[fld].strip(): errs.append(f"{sid}: missing '{fld.title()}'")
    if len(own) > a.max_ids: warns.append(f"{sid}: owns {len(own)} IDs (> {a.max_ids}); consider splitting")
    for r in IDRE.findall(" ".join(f.values())):
        if r not in reqs: errs.append(f"{sid}: cites undefined {r}")

for rid, r in reqs.items():
    if r["phase"] == "2":
        if rid in owner: errs.append(f"{rid} is Phase 2 but owned by {owner[rid]}; list it under Seams instead")
        continue
    if len(owner[rid]) > 1: errs.append(f"{rid} owned by several slices: {owner[rid]}")
    if not owner[rid] and not soft[rid]: errs.append(f"{rid} ({r['section']}) not mapped to any slice")

deps = {s: re.findall(r"S-[A-Z0-9]+-\d+", f["depends on"]) for s, f in slices.items()}
for s, ds in deps.items():
    for d in ds:
        if d not in slices: errs.append(f"{s}: depends on unknown {d}")
        elif milestone[d] is not None and milestone[s] is not None and milestone[d] > milestone[s]:
            errs.append(f"{s} (M{milestone[s]}) depends on later {d} (M{milestone[d]})")
state = {}
def visit(n, path):
    if state.get(n) == 1: errs.append("dependency cycle: " + " → ".join(path + [n])); return
    if state.get(n) == 2 or n not in slices: return
    state[n] = 1
    for d in deps[n]: visit(d, path + [n])
    state[n] = 2
for s in slices: visit(s, [])

p1 = [r for r in reqs.values() if r["phase"] == "1"]
mapped = sum(1 for r in p1 if owner[r["id"]] or soft[r["id"]])
print(f"Slices: {len(slices)}  Milestones: {sorted(set(v for v in milestone.values() if v is not None))}")
print(f"Phase 1 coverage: {mapped}/{len(p1)}  Phase 2 seams listed: {len(seams)}")
for w in warns: print("WARN ", w)
for e in errs: print("ERROR", e)
sys.exit(1 if errs else 0)
