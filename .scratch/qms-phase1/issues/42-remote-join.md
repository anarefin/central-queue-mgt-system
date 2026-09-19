# 42 — Remote join

**What to build:** A visitor joins a Service's queue from their phone before arriving, where the Service allows it — within distance, share and time-window limits — after being shown the forfeit policy. Their Remote ticket accrues wait like anyone else's but cannot be called until they check in.

**Blocked by:** 37 — Visitor ticket page (PWA); 21 — Issuance rules

**Status:** ready-for-agent

- [ ] Remote join only where the Service's virtual-queue flag is on (FR-MOB-010)
- [ ] Policy per Service: max distance (10 km or off), max remote share (40%), join window before opening (30 min), arrival deadline (15 min) (FR-MOB-011)
- [ ] Remote ticket (`remote` state) accrues wait identically but is never callable (FR-MOB-012, §19.1)
- [ ] Forfeit policy and consequences shown before joining (FR-MOB-023)
- [ ] Mobile-origin Tickets differ to the engine only by `origin_channel` (§8.5)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
