# Aarong Central Queue Management demo

Seeds the scenario from the *Aarong Central Queue Management System* pitch deck into a running stack. It creates:

- one central Site with 8 floors across CS-1, CS-2 and Haque Centre
- 19 departments, each with S100-style token numbering
- 45 desks
- a Super Admin, a Reception user, a Team Admin per department and 37 Officers
- 40 producers
- phone appointments
- notice boards
- 3 kiosks and 9 TVs to pair
- two weeks of history for the reports

**Start with [`aarong-onboarding.html`](aarong-onboarding.html).** Open it in a browser. It is the step-by-step walkthrough, from starting the stack to reading the reports, and maps every slide of the deck to a screen.

## Quick start

```bash
export QMS_DB_PASSWORD=aarongdemo QMS_HTTP_PORT=8091 COMPOSE_PROJECT_NAME=aarong-demo
export QMS_BOOTSTRAP_ADMIN_USERNAME=admin QMS_BOOTSTRAP_ADMIN_PASSWORD='Admin@2026'
export BASE_URL=http://localhost:8091

docker compose -f deploy/compose.yaml up -d --build --wait
./deploy/demo/aarong/seed.sh all              # about a minute; prints every login (password Aarong@2026)
./deploy/demo/aarong/seed.sh devices --wait   # pairing codes for the kiosks and TVs
```

This runs a separate `aarong-demo` stack on port 8091, next to anything on 8080. The seed applies the *Producer services* profile, which relabels screens and changes branding, so don't point it at an installation you care about.

To reset, run `docker compose -f deploy/compose.yaml down -v`. The ticket history is append-only, so a fresh database is the only reset.

## Files

| File | What it is |
|---|---|
| `seed.sh` | The loader, driven through the REST API. It is safe to re-run. `seed.sh --help` lists every step and helper. |
| `history.sql` | Backfills past working days. Past-dated tokens can't go through the API, so this writes the same rows the app would write, run through `docker compose exec postgres psql`. |
| `data/departments.json` | Departments, token prefixes, floors, services, desks, hourly volumes (deck slide 3), Team Admins and Officers. |
| `data/site.json` | The site, floors, break types, notice text, branding and printed-token fields. |
| `data/producers.csv` | Made-up producers with 4-digit codes, imported like a real producer master list. |
| `data/devices.json` | The kiosks and TVs to pair. |
| `aarong-onboarding.html` | The walkthrough. It is generated, so edit `guide.template.html` instead. |
| `guide.template.html`, `build_guide.py` | Rebuild the walkthrough after changing the data: `python3 deploy/demo/aarong/build_guide.py deploy/demo/aarong deploy/demo/aarong/guide.template.html` |
