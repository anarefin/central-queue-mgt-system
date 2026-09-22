# 55 — Configuration versioning, revert and bundle

**What to build:** Changes to routing, priority classes, numbering and business hours are versioned with author and time and can be reverted; admins are warned when a change would affect waiting Tickets; and the whole configuration exports and imports as one signed bundle to clone a client into staging or training.

**Blocked by:** 09 — Queue ordering engine; 08 — Configurable token numbering and scheduled resets; 21 — Issuance rules

**Status:** ready-for-agent

- [x] Versioned config with author/timestamp, revertible to any prior version (FR-CFG-040)
- [x] Warning when a change affects waiting Tickets; no retroactive renumber/reprioritise (FR-CFG-041)
- [x] Signed JSON bundle export/import of full configuration (CFG-004) — scoped to the four FR-CFG-040 configuration areas (Priority classes/defaults, routing strategy, numbering rules, business hours), not the org hierarchy/catalogue those areas are keyed by; see the traceability matrix note for why
- [x] Every profile-set value editable in admin (CFG-003)
- [x] `config.changed` event reaches devices
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
