# 21 — Issuance rules: hours, cut-offs, caps, duplicates, rate limits, maintenance

**What to build:** Issuance refuses — with a specific, localised reason — whenever it should: outside business hours or holidays, past a channel's cut-off, when the daily cap is hit, for inactive Services, with no Agent rostered, for duplicates, when rate-limited, or while the system is in maintenance mode.

**Blocked by:** 07 — Reception issues a walk-in Ticket

**Status:** done

- [x] Weekly hours per Site with per-Service overrides; holiday calendar incl. half-days (FR-CFG-020, FR-CFG-021)
- [x] Per-channel cut-off before close (FR-CFG-022)
- [x] Daily cap per Service with configurable message (FR-CFG-023)
- [x] Reason codes for outside hours, past cut-off, cap reached, service inactive, no agent rostered (configurable), duplicate (FR-ISS-003)
- [x] Duplicate policy per Service: allow, warn, block (FR-ISS-004)
- [x] Rate limits per device and per visitor (defaults 30/min per kiosk, 5/h per visitor), configurable, 429 with Retry-After (API-090)
- [x] Maintenance mode stops new issuance with a configurable message while the queue drains (FR-OPS-043)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
