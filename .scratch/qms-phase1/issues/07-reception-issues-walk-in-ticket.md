# 07 — Reception issues a walk-in Ticket (tracer bullet)

**What to build:** A Reception Operator picks a Service and issues a Ticket for a walk-in; they get back a Token number, position and ticket secret, and the Ticket appears in that Service's queue snapshot. This is the first end-to-end issuance path that every other channel reuses.

**Blocked by:** 06 — Service catalogue administration

**Status:** ready-for-agent

- [x] `POST /tickets` requires an Idempotency-Key; replay within 24 h returns the original result (§20.1)
- [x] Sequence allocation, Ticket row and queue insertion are atomic (FR-ISS-001)
- [x] Token number drawn from a per-site sequence block using the default numbering rule (prefix-separator-padded sequence, daily reset key) (FR-QUE-201, §4.4)
- [x] Partial unique constraint on (site, reset key, token number) for chain heads (§18.4, ADR-0006)
- [x] A Visit is created implicitly with the first Ticket; `visit_id` NOT NULL (ADR-0007)
- [x] Ticket denormalises service group, site and zone at issue (§18.5)
- [x] Every transition writes exactly one ticket_event carrying device time and server time plus per-ticket sequence number (Invariant 3, FR-QUE-070, ADR-0001)
- [x] Response carries token number, service, zone, building, floor, position, estimate placeholder and ticket secret (hashed at rest) (FR-ISS-002, §20.5)
- [x] `origin_channel` recorded; the issuance service is channel-agnostic (§8.5)
- [x] `GET /tickets/{id}` for staff and `GET /queues/{service_id}` snapshot
- [x] Reception screen: choose Service, issue, show result; see the queue
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
