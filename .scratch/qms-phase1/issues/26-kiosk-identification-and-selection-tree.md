# 26 — Kiosk identification and full selection tree

**What to build:** A registered visitor identifies themself at the kiosk by typed code, camera QR or mobile number, confirms their name and category, and can go on to pick a team, a specific on-duty Agent or a custom level where the Service group enables it.

**Blocked by:** 25 — Kiosk common path; 22 — Visitor directory and walk-in registration

**Status:** ready-for-agent

- [x] Identification by typed code, camera-based QR, mobile number, or none where the Service allows (FR-ISS-013, §22.6)
- [x] Resolved code shows only name and category for confirmation (FR-ISS-014)
- [x] Selection tree up to five levels: group, Service, team, individual Agent, one custom level (FR-ISS-010)
- [x] Each level enabled/disabled per Service group (FR-ISS-011)
- [x] Individual Agent selectable only when on duty; warns when their queue is longer than the group's (FR-ISS-012)
- [x] Mandatory visitor identifier enforced per Service (FR-CFG-013)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
