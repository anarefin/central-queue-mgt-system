# 56 — Vertical profiles and first-run setup wizard

**What to build:** A consultant installs the system, picks a vertical profile (banking, healthcare, producer services, government, generic), and is walked through org, sites, zones, counters, services, users and devices — and cannot go live until a test token has been issued, printed, called and announced end to end.

**Blocked by:** 55 — Configuration versioning, revert and bundle; 29 — Voice announcements; 27 — Branding and printed token template

**Status:** ready-for-agent

- [x] Five shipped profiles carrying label overrides, starter catalogue, priority classes, numbering, report/KPI defaults, feature flags (§3.3, §3.4) — starter catalogue/numbering are Site-scoped and carried in the profile data for the wizard's later "services and numbering" step to seed (no Site exists yet when a profile is picked); see traceability matrix note
- [x] Terminology remapping via label keys resolved through pack and profile (§3.2)
- [x] No industry branches in code — variation only via config, labels, flags (CFG-001, NFR-MNT-005)
- [x] Profile applied only at first run or explicit reset; never on upgrade (CFG-002)
- [x] Setup wizard steps per §26.2; go-live blocked until an end-to-end test token passes (FR-OPS-010)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
