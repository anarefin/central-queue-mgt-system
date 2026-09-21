# 48 — Reporting store and detailed token report

**What to build:** An admin runs the detailed token report — one row per Ticket with every timing, the Counter, Agent, outcome and transfers — filtered and sorted on screen, from a reporting store that trails live data by under a minute and never slows the queue.

**Blocked by:** 10 — Counter session: call next, start service, complete; 15 — Transfer to a Successor ticket

**Status:** ready-for-agent

- [x] Separate reporting schema of fact/dimension tables refreshed from ticket and ticket_event, ≤ 60 s behind (§16, FR-RPT-020, §18.5)
- [x] Reports never query live transactional tables
- [x] "Tickets issued" counts chain heads (§18.5, ADR-0006)
- [x] Detailed token report columns per §16.1
- [x] Filters: date range, Site, Zone, Service group, Service, Agent, Priority class, channel, visitor category (FR-RPT-001)
- [x] Server-side paging and sort by any column; 1,000 rows over 12 months render in 5 s (FR-RPT-002, NFR-PERF-005)
- [x] `POST /reports/{key}/run`
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
