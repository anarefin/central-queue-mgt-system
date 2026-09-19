# 06 — Service catalogue administration

**What to build:** An Org Admin defines Service groups, their Services, which Counters serve which Services, each group's Team, and the outcome codes agents will record — all in the admin app with per-language names.

**Blocked by:** 05 — Site, Zone and Counter administration

**Status:** ready-for-agent

- [x] Service groups per Site with per-language names, token prefix, display order, active flag
- [x] Service carries per-language name, group, token prefix, expected handling minutes, SLA wait target, enabled channels, active flag (FR-CFG-010)
- [x] Service display order and kiosk icon (FR-CFG-012); visitor-identifier requirement not/optional/mandatory (FR-CFG-013); appointment-only / walk-in-only / both (FR-CFG-014)
- [x] Counter↔Service links with preference weight (1 = primary) (FR-CFG-011)
- [x] One Team per Service group with members (membership changes by Team Admin go through approval from ticket 04)
- [x] Outcome codes per Service, configurable and localisable (FR-AGT-032, FR-AGT-033)
- [x] Deleting a Service with tickets is blocked; only deactivation allowed (FR-CFG-015)
- [x] Translatable-name inputs show one field per enabled language and warn, not block, on missing translations (FR-I18N-010)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
