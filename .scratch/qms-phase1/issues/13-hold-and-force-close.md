# 13 — Hold, held-by-me and force-close

**What to build:** An Agent can Hold the Ticket being served (F8) to call the next visitor and later resume it from a "held by me" list; a session cannot close cleanly with held Tickets; and a Team or Org Admin can force-close a stale session, which puts its called, serving and held Tickets back at the front of their queues.

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** ready-for-agent

- [ ] Hold keeps the Session binding and removes the Ticket from the general queue; resume only by the same session (FR-AGT-013, ADR-0008)
- [ ] Held count per session capped by a configurable hold limit (default 3)
- [ ] "Held by me" list shown in the console and must be cleared before closing (session state `closing`) (FR-AGT-005, §19.3)
- [ ] Force-close by Org/Team Admin returns called/serving/held Tickets to waiting at the front via Score adjustment and writes an audit entry (FR-AGT-002, §19.3)
- [ ] F8 shortcut; `ticket.held` events
- [ ] Engine suite covers serving→held, held→serving, held→waiting (NFR-MNT-004)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
