# 05 — Site, Zone and Counter administration

**What to build:** An Org Admin sets up the physical hierarchy in the admin app — Sites with timezone, address and languages; Zones labelled by building and floor; Counters with display labels — and can deactivate any of them without breaking history.

**Blocked by:** 02 — Language packs and i18n foundation; 04 — Roles, scopes, user administration and audit log

**Status:** ready-for-agent

- [ ] Create, rename and soft-deactivate Sites, Zones and Counters; historical references keep resolving (FR-CFG-001)
- [ ] Site carries timezone, address, default language and ordered enabled languages; timestamps stored UTC, rendered in site timezone (FR-CFG-002, FR-I18N-002)
- [ ] Zone carries floor label and optional building label (FR-CFG-003, ADR-0002)
- [ ] Counter carries short display label, Zone and optional location note (FR-CFG-004)
- [ ] Adding a Site needs no code change or restart (NFR-SCL-002)
- [ ] Changes audited with before/after values (FR-SEC-040)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
