# 31 — Journeys and multi-stop Visits

**What to build:** Reception issues a multi-stop Journey for one visitor in one action, from a template or ad hoc. Ordered Journeys issue the next stop's Ticket on completion; unordered ones issue all up front and show which stop is callable soonest. The visitor is never called at two Counters at once, and the Agent sees the Visit's other stops.

**Blocked by:** 22 — Visitor directory and walk-in registration; 10 — Counter session: call next, start service, complete

**Status:** ready-for-agent

- [x] Journey stored as journey stops on the Visit (ordered or unordered), from a template or ad hoc; Tickets realise stops (FR-QUE-060, ADR-0007)
- [x] Reception issues the linked set in one action; all share one Visit (FR-ISS-022)
- [x] Ordered: next stop's Ticket created on completion, inheriting Priority class (FR-QUE-061)
- [x] Unordered: all Tickets created up front; visitor shown the soonest-callable stop (FR-QUE-062)
- [x] When one Ticket is called, the Visit's other waiting Tickets become `paused` and accrue no wait until the visitor is free (FR-QUE-063, Invariant 1)
- [x] Console shows the Visit's other stops and status (FR-AGT-031)
- [x] Journey templates per Service group; feature flag per profile
- [x] Engine suite covers waiting↔paused (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
