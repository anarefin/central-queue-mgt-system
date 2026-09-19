# 20 — Console visitor context and personal stats

**What to build:** When a Ticket is called, the Agent sees who and what it is — token, visitor, category, Service, note, wait so far, channel, appointment flag — and can see their own day's counts without being ranked against colleagues.

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** ready-for-agent

- [x] Called-ticket panel shows token, visitor name/code where available, category, Service, purpose note, wait so far, origin channel, appointment flag (FR-AGT-030)
- [x] Completion records outcome and optional free-text note (FR-AGT-032)
- [x] Only the role's configured visitor field set is shown (FR-AGT-034)
- [x] Agent's own current-day counts: served, in queue for their Services, average service time, break time; no ranking (FR-AGT-040)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
