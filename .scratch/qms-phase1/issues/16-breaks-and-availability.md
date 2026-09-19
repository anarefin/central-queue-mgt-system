# 16 — Breaks and agent availability

**What to build:** An Agent takes a typed break (F9) and stops receiving Tickets until they return; break time is recorded; and a Team or Org Admin can set an Agent's availability directly.

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** ready-for-agent

- [ ] Configurable break types with localised names and optional max duration (FR-AGT-020)
- [ ] Starting a break requires the current Ticket resolved and stops new assignment immediately; session `on_break` (FR-AGT-021, §19.3)
- [ ] Break records with start/end, reportable per Agent and break type (FR-AGT-022)
- [ ] Team/Org Admin can change an Agent's availability status (FR-AGT-024)
- [ ] `POST /sessions/{id}/break`; F9; `session.break_started` / `session.break_ended` events
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
