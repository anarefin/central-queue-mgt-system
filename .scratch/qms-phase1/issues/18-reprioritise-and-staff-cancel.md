# 18 — Re-prioritise and staff cancel

**What to build:** A Ticket gets its Priority class from the right source at issue, a supervisor or reception can change a waiting Ticket's class with a reason and see it take effect within seconds (UAT U9), and staff can cancel waiting Tickets.

**Blocked by:** 09 — Queue ordering engine; 04 — Roles, scopes, user administration and audit log

**Status:** ready-for-agent

- [ ] Class precedence at issue: manual staff assignment, appointment class, visitor category mapping, channel default, service default (FR-QUE-011)
- [ ] `POST /tickets/{id}/priority` with mandatory reason, audited (FR-QUE-012, FR-SEC-040)
- [ ] Configuration change never reprioritises issued Tickets retroactively (FR-CFG-041)
- [ ] Staff cancel of an active Ticket per §5.2 (agents: own only) via `POST /tickets/{id}/cancel`; `ticket.cancelled`
- [ ] Engine suite covers waiting→cancelled (NFR-MNT-004)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
