# 08 — Configurable token numbering and scheduled resets

**What to build:** An Org Admin configures how Token numbers look and when they reset per Service or Service group, and sequences restart at the site-local reset time — even if the backend was down at that moment — with no duplicate numbers and prior days intact (UAT U10).

**Blocked by:** 07 — Reception issues a walk-in Ticket

**Status:** done

- [x] Numbering rules per Service or Service group: prefix source (service, group, priority class, fixed), start, padding 0–6, reset boundary daily/weekly/monthly/never, reset time, separator (FR-CFG-018)
- [x] Scheduled resets run once cluster-wide under a database lock at site-local reset time and are replayed if missed (FR-CFG-019, ADR-0010)
- [x] Sequence blocks re-requested at 80% consumption (FR-QUE-201)
- [x] Changing a rule never renumbers issued Tickets (FR-CFG-041)
- [x] Admin preview of the next Token number for a scope
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
