# 12 — Re-announce and Miss

**What to build:** An Agent can press F3 to Re-announce a called Ticket (no state change, capped repeats) and F6 to Miss an absent visitor, which puts the Ticket back in the queue at the configured re-entry position — or closes it as `no_show` once the miss limit is passed. The word "recall" appears nowhere.

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** ready-for-agent

- [x] Re-announce keeps the Ticket `called` and its Session binding, increments `announce_count`, capped by the configurable repeat limit, writes an event and publishes `ticket.reannounced` (ADR-0005, FR-DSP-028)
- [x] Miss increments `miss_count`, returns to waiting and frees the Counter; past the limit (default 2) Miss yields `no_show` instead (FR-QUE-050)
- [x] Re-entry position configurable: front, after N (default 3), back — applied as a Score adjustment; `queued_at` never rewritten (FR-QUE-051, ADR-0004)
- [x] Each positional move writes a ticket_event carrying the adjustment applied (ADR-0004)
- [x] `ticket.missed` / `ticket.no_show` events; Session binding cleared on return to waiting (Invariant 2)
- [x] F3 and F6 shortcuts in the console
- [x] Engine suite covers called→called, called→waiting (miss) and called→no_show (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
