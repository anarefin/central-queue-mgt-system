"""Fills the generated tables into deploy/demo/aarong/aarong-onboarding.html from data/*.json and producers.csv.

The guide's hand-written template has three markers: <!--DEPARTMENTS-->, <!--LOGINS--> and <!--PRODUCERS-->.
"""
import csv
import html
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
template = pathlib.Path(sys.argv[2]).read_text()
depts = json.loads((root / "data/departments.json").read_text())
site = json.loads((root / "data/site.json").read_text())
zones = {z["key"]: z for z in site["zones"]}
e = html.escape


def floor(zkey):
    z = zones[zkey]
    return f'{e(z["building_label"])} · {e(z["floor_label"])}'


# Desk numbers follow the order seed.sh creates them in.
desk = 0
rows = []
for d in depts:
    first = desk + 1
    desk += d["counters"]
    desks = f"{first}" if d["counters"] == 1 else f"{first}–{desk}"
    services = ", ".join(e(s["name_i18n"]["en"]) for s in d["services"])
    officers = ", ".join(e(a["display_name"]) for a in d["agents"])
    pick = ' <span class="chip">pick an Officer</span>' if d.get("individual_selectable") else ""
    appt = ' <span class="chip">phone booking</span>' if d.get("appointments") else ""
    rows.append(
        f'<tr><td><span class="tok">{e(d["prefix"])}100</span></td><td><b>{e(d["name_i18n"]["en"])}</b>{pick}{appt}'
        f'<div class="sub">{services}</div></td><td>{floor(d["zone"])}</td><td class="num">{desks}</td>'
        f'<td class="num">{d["hourly_volume"]}</td><td>{officers}</td></tr>'
    )
departments = "\n".join(rows)

logins = [
    '<tr><td>Super Admin</td><td><code>aarong.admin</code></td><td>Aarong Super Admin</td><td>Everything</td><td>/admin/</td></tr>',
    '<tr><td>Reception</td><td><code>aarong.reception</code></td><td>CS-1 Reception</td><td>All departments</td><td>/admin/reception/</td></tr>',
]
for d in depts:
    ta = d["team_admin"]
    logins.append(
        f'<tr><td>Team Admin</td><td><code>{e(ta["username"])}</code></td><td>{e(ta["display_name"])}</td>'
        f'<td>{e(d["name_i18n"]["en"])}</td><td>/admin/</td></tr>'
    )
    for a in d["agents"]:
        logins.append(
            f'<tr><td>Officer</td><td><code>{e(a["username"])}</code></td><td>{e(a["display_name"])}</td>'
            f'<td>{e(d["name_i18n"]["en"])}</td><td>/console/</td></tr>'
        )

with open(root / "data/producers.csv", newline="") as f:
    producers = "\n".join(
        f'<tr><td><code>{e(r["external_code"])}</code></td><td>{e(r["name"])}</td><td>{e(r["category"])}</td><td class="num">{e(r["phone"])}</td></tr>'
        for r in csv.DictReader(f)
    )

out = (
    template.replace("<!--DEPARTMENTS-->", departments)
    .replace("<!--LOGINS-->", "\n".join(logins))
    .replace("<!--PRODUCERS-->", producers)
    .replace("<!--DEPT_COUNT-->", str(len(depts)))
    .replace("<!--DESK_COUNT-->", str(desk))
    .replace("<!--OFFICER_COUNT-->", str(sum(len(d["agents"]) for d in depts)))
    .replace("<!--USER_COUNT-->", str(2 + sum(len(d["agents"]) + 1 for d in depts)))
)
(root / "aarong-onboarding.html").write_text(out)
print("wrote", root / "aarong-onboarding.html", len(out), "bytes")
