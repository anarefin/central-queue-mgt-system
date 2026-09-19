# 58 — Service accounts and host-system API

**What to build:** A client's own systems (a bank's app, a hospital portal, a BI tool) authenticate as scoped service accounts and can create Tickets, book appointments, query queue status and cancel through the public API.

**Blocked by:** 33 — Staff appointment booking; 04 — Roles, scopes, user administration and audit log

**Status:** ready-for-agent

- [ ] Client id + secret exchanged for a scoped JWT (reporting role or host-system scope) (§20.2)
- [ ] Host system can create a Ticket, book an appointment, query queue status and cancel (FR-INT-030)
- [ ] Same permission checks and rate limits as other principals; no privileged internal path (§20)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
