# 46 — Live dashboard

**What to build:** A supervisor opens a live dashboard filtered to their Site, Zone, Service group, Service or Priority class, sees where things are going wrong right now — waits, serving, counters, SLA risk, throughput, appointments, remote queue, device health — and acts from it.

**Blocked by:** 18 — Re-prioritise and staff cancel; 16 — Breaks and agent availability; 11 — Realtime hub

**Status:** ready-for-agent

- [x] Refreshes via `site:{id}:dashboard` topic, ≤ 5 s stale (FR-MON-001, NFR-PERF-004)
- [x] Filters by Site, Zone, Service group, Service, Priority class, shareable as a URL (FR-MON-002)
- [x] Tiles per §15.1 incl. escalated Tickets flagged; tiles for features not yet built show empty state (FR-MON-003, FR-QUE-022)
- [x] Served-per-open-counter view for load balancing (FR-QUE-033)
- [x] Supervisor actions: re-prioritise, open/close counter, change agent status, send staff alert (FR-MON-004)
- [x] Scope-limited: all groups for Org Admin, own groups otherwise (§5.2); `GET /dashboard/live`
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
