# 54 — Privacy controls

**What to build:** An Org Admin controls exactly which visitor fields each surface shows and which are captured at all, switches a Site into clinical-sensitivity mode so public displays, announcements and notifications use neutral labels, restricts free-text notes, and can export or anonymise everything held about one visitor.

**Blocked by:** 22 — Visitor directory and walk-in registration; 28 — Display board: now-serving table; 38 — Notification pipeline and in-app channel

**Status:** ready-for-agent

- [ ] Configurable visitor field set per surface with §25.3 defaults (FR-SEC-020)
- [ ] Clinical sensitivity per Site replaces service/group names with neutral labels on displays, announcements, notifications (FR-SEC-021)
- [ ] Notes visible only with explicit permission (FR-SEC-022)
- [ ] Disabled fields neither captured nor retained (FR-SEC-023)
- [ ] Visitor data export and deletion by Org Admin; deletion anonymises Ticket rows (FR-SEC-031)
- [ ] Consent and retention changes audited (FR-SEC-030, FR-SEC-040)
- [ ] Application-layer encryption of visitor name, phone, email, notes where the platform lacks encryption at rest (NFR-SEC-011)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
