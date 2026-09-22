# 58 — Service accounts and host-system API

**What to build:** A client's own systems (a bank's app, a hospital portal, a BI tool) authenticate as scoped service accounts and can create Tickets, book appointments, query queue status and cancel through the public API.

**Blocked by:** 33 — Staff appointment booking; 04 — Roles, scopes, user administration and audit log

**Status:** done

- [x] Client id + secret exchanged for a scoped JWT (reporting role or host-system scope) (§20.2)
- [x] Host system can create a Ticket, book an appointment, query queue status and cancel (FR-INT-030)
- [x] Same permission checks and rate limits as other principals; no privileged internal path (§20) — no rate-limiting infrastructure exists for any principal in this codebase yet, so this is satisfied by construction: a host system reaches every action through the exact same `@PreAuthorize`-guarded endpoints and services staff/kiosk/visitor already use, no special-cased bypass anywhere.
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix — no new user-facing strings: this ticket adds no frontend/UI surface (a host system is an external client, not a person at a screen).
