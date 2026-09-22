# 54 — Privacy controls

**What to build:** An Org Admin controls exactly which visitor fields each surface shows and which are captured at all, switches a Site into clinical-sensitivity mode so public displays, announcements and notifications use neutral labels, restricts free-text notes, and can export or anonymise everything held about one visitor.

**Blocked by:** 22 — Visitor directory and walk-in registration; 28 — Display board: now-serving table; 38 — Notification pipeline and in-app channel

**Status:** ready-for-agent

- [x] Configurable visitor field set per surface with §25.3 defaults (FR-SEC-020)
- [x] Clinical sensitivity per Site replaces service/group names with neutral labels on displays, announcements, notifications (FR-SEC-021)
- [x] Notes visible only with explicit permission (FR-SEC-022)
- [x] Disabled fields neither captured nor retained (FR-SEC-023)
- [x] Visitor data export and deletion by Org Admin; deletion anonymises Ticket rows (FR-SEC-031)
- [x] Consent and retention changes audited (FR-SEC-030, FR-SEC-040)
- [ ] Application-layer encryption of visitor name, phone, email, notes where the platform lacks encryption at rest (NFR-SEC-011) — delivered in full for `ticket.purpose_note` only (`configuration.privacy.PiiCipher`, wired into `TicketRepository`/`SessionRepository`/`JourneyRepository`); `visitor.name`/`.phone`/`.email` deliberately not encrypted — `phone`/`email` are FR-INT-010's/the visitor OTP sign-in's own equality-lookup keys, `visitor.name` is copied by a pure-SQL `INSERT...SELECT` into the reporting warehouse, and all three are written directly by raw-SQL fixtures in ~20 other tickets' own integration tests. See the NFR-SEC-011 row in docs/traceability-matrix.md for the full reasoning.
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
