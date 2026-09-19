# 09 — Queue ordering engine, Priority classes and dry-run

**What to build:** An admin defines Priority classes with a Head start and maximum wait, picks an ordering strategy per Service group, and can see in a dry-run view exactly how each waiting Ticket is scored and ordered. A priority visitor is ordered ahead on arrival, and no Ticket past its class's maximum wait is left behind (UAT U6).

**Blocked by:** 07 — Reception issues a walk-in Ticket

**Status:** done

- [x] Priority classes with name, Head start minutes (normal = 0), optional max wait, optional prefix override (FR-QUE-010, ADR-0003)
- [x] Score = effective wait + Head start + appointment bonus + Escalation bonus + Score adjustment; ties by creation time then id (FR-QUE-020)
- [x] Strategies `weighted_wait`, `strict_priority`, `fifo` selectable per Service group (FR-QUE-021)
- [x] Escalation on real wait past max wait overrides negative Score adjustments; flagged for the dashboard (FR-QUE-022, ADR-0004)
- [x] One logical queue per (Site, Service); Tickets never move tables (FR-QUE-001)
- [x] Engine is a pure component with no transport dependency (ADR-0001)
- [x] Dry-run endpoint and admin view show computed order with each term (FR-QUE-023)
- [x] Ordering of 500 waiting Tickets computes in under 50 ms (NFR-SCL-003)
- [x] Reception can choose a Priority class at issue
- [x] Engine test suite covers every strategy (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
