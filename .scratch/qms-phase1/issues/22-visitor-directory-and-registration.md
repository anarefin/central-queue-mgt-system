# 22 — Visitor directory and walk-in registration

**What to build:** Reception can search for a known visitor by code, phone or QR, register an unknown walk-in with a minimal record and pass reference, and issue their Ticket with a note and Priority class — and issuance never stalls if the directory is slow.

**Blocked by:** 07 — Reception issues a walk-in Ticket

**Status:** ready-for-agent

- [ ] Lookup goes through a `VisitorDirectory` interface; local implementation in v1 (FR-INT-010, FR-INT-012)
- [ ] `lookup(code | phone | qr)` returns external code, name, category, phone, flags with a hard timeout (default 1.5 s) and fallback to local data (FR-INT-012)
- [ ] No queue operation blocks on the directory; on timeout the Ticket is issued without visitor details (FR-INT-013)
- [ ] `GET /visitors/lookup`, `POST /visitors`: register walk-in with name, phone, optional email/category/purpose and issue a visitor pass reference (FR-ISS-021)
- [ ] Reception issues on a visitor's behalf with directory search, Priority class and agent-visible note (FR-ISS-020)
- [ ] Only configured visitor fields are captured (FR-SEC-023)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
