# 59 — Multi-node operation

**What to build:** The backend runs as two or more nodes behind a load balancer: realtime events and revocations reach every node's subscribers, scheduled jobs run exactly once, and REST needs no sticky sessions (Medium tier).

**Blocked by:** 11 — Realtime hub; 08 — Configurable token numbering and scheduled resets

**Status:** done

- [x] Cross-node fan-out of queue events and `principal.changed` over PostgreSQL LISTEN/NOTIFY (ADR-0010, ADR-0009)
- [x] Scheduled jobs single-run cluster-wide, verified on two nodes (ADR-0010)
- [x] No sticky sessions for REST; realtime may use consistent hashing (NFR-SCL-001)
- [x] Two-node integration test covers call on node A reaching a display on node B within 2 s
- [x] High-availability option has no single point of failure in the server tier (NFR-AVL-005); supports the 99.5% operating-hours availability target (NFR-AVL-001)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
