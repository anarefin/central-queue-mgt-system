# 23 — Visitor CSV import

**What to build:** An admin loads the client's visitor master data from CSV — by manual upload or a scheduled folder pickup — maps columns, sees a validation report, and existing visitors are updated by external code.

**Blocked by:** 22 — Visitor directory and walk-in registration

**Status:** ready-for-agent

- [ ] Manual upload and scheduled folder pickup (FR-INT-011)
- [ ] Column mapping, validation report, upsert by external code (FR-INT-011)
- [ ] CSV import exposed as the second `VisitorDirectory` source (FR-INT-010)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
