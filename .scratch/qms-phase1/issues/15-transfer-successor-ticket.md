# 15 — Transfer to a Successor ticket

**What to build:** An Agent transfers the visitor being served (F7) to another Service, a specific Counter or a specific Agent, with a note. The current Ticket closes as `transferred` and a Successor ticket appears in the target queue with the same Token number and Visit, not sent to the back (UAT U5).

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** done

- [x] Transfer with mandatory note to Service, Counter or Agent (FR-QUE-052)
- [x] Predecessor closes `transferred` (terminal); Successor ticket created in the same transaction with same Visit and Token number, `predecessor_ticket_id` set, Priority class inherited (FR-QUE-053, ADR-0006, Invariant 4)
- [x] Predecessor wait stops at transfer; Successor wait starts at transfer with configurable transfer Head start (default = predecessor's accrued wait) (FR-QUE-053)
- [x] Ticket targeted at an Agent waits in that Agent's personal queue and is not drawn by other Counters unless an admin reassigns it (FR-QUE-003)
- [x] Target must be active; transfers are intra-site only (§19.1, ADR-0002)
- [x] `POST /tickets/{id}/transfer`; F7 shortcut; `ticket.transferred` event
- [x] Engine suite covers serving→transferred (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
