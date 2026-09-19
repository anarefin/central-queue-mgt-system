# 17 — Call timeout, out-of-order call and parallel serving

**What to build:** A called Ticket nobody acts on prompts the Agent and can return to the queue with its original wait preserved; an Agent can call a specific waiting Ticket out of order with a reason; and desks configured for parallel serving can handle several visitors at once.

**Blocked by:** 12 — Re-announce and Miss

**Status:** done

- [x] Configurable call timeout (default 90 s) prompts the Agent and may return the Ticket to waiting, position restored via Score adjustment (FR-QUE-032, ADR-0004)
- [x] Call a specific waiting Ticket where permitted, with mandatory reason, audited as an out-of-order call (FR-AGT-012, FR-SEC-040)
- [x] Parallel-serving flag per Service with configurable max concurrent Tickets per Counter (FR-AGT-010, FR-AGT-011)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
