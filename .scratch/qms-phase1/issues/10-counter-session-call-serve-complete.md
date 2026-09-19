# 10 — Counter session: call next, start service, complete

**What to build:** An Agent opens a Counter session on a permitted Counter, presses F2 to call the highest-scoring eligible Ticket, F4 to start service and F5 to complete with an outcome, and closes the session with F10 — keyboard only, surviving a browser refresh, with no two counters ever calling the same Ticket.

**Blocked by:** 09 — Queue ordering engine

**Status:** ready-for-agent

- [x] Open session on a permitted Counter; one open session per Counter enforced by a partial unique index (FR-AGT-001, §18.4)
- [x] Choose which of the Counter's Services to serve this session, default all (FR-AGT-003)
- [x] Call next selects the highest Score across the Counter's queues, preferring primary links within a configurable tolerance (FR-QUE-002, FR-QUE-030)
- [x] Session binding set under optimistic concurrency on ticket version (If-Match); concurrent calls never bind one Ticket twice (FR-QUE-031, ADR-0008, Invariant 2)
- [x] Call next disabled while a Ticket is called or serving (FR-AGT-010)
- [x] Start service and complete with outcome from the Service's list (FR-AGT-032)
- [x] `wait_seconds` and `service_seconds` computed and stored at closure; wait accrues only in waiting/remote (§18.5, Invariant 1)
- [x] Agent-only actions check "own record" via the Session binding (FR-CFG-105)
- [x] Session survives browser refresh, short network loss and device restart, restoring the in-progress Ticket (FR-AGT-004)
- [x] Closing requires resolving the in-progress Ticket (FR-AGT-005); session states per §19.3
- [x] F2/F4/F5/F10 shortcuts; call, serve and complete possible without a mouse (§11.2, NFR-USA-002)
- [x] Console actions acknowledge within 500 ms at P95 (NFR-PERF-003)
- [x] Engine suite covers the waiting→called→serving→completed transitions (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
