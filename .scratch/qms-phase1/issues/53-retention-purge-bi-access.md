# 53 — Retention, purge and BI access

**What to build:** Old detail data is purged or anonymised on the client's schedule while aggregates survive for years; a client's BI tool can read a stable view layer with a read-only user, or pick up a nightly extract.

**Blocked by:** 50 — Operational reports and KPIs

**Status:** done

- [x] Ticket detail retention configurable (default 24 months), then purged or reduced to anonymised aggregates (FR-RPT-021)
- [x] Aggregates retained longer (default 7 years) and survive detail purge, conditional on the client leaving `ticket_detail` at its default `anonymize` mode; if a client instead sets `ticket_detail` to `mode = purge` (a legitimate FR-RPT-021 choice), rows past that cutoff are deleted outright and never reach the aggregate stage (FR-RPT-022)
- [x] Retention per data class; purge job logs removed volumes in aggregate (FR-SEC-032)
- [x] Audit retention independent, default 24 months (FR-SEC-043)
- [x] Provisionable read-only reporting DB user with documented views stable across minor releases (FR-RPT-023)
- [x] Nightly Parquet or CSV extract to a configured location (FR-INT-060)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
