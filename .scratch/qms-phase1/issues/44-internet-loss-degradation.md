# 44 — Internet-loss degradation

**What to build:** When a Site loses internet but its LAN stays up, tokens keep issuing and serving with no duplicate numbers, while remote join and Web Push are clearly shown as unavailable and existing Remote tickets can still check in on site (UAT U7).

**Blocked by:** 43 — Remote arrival, check-in and forfeit

**Status:** ready-for-agent

- [ ] Backend detects loss of internet reachability for push services and the public origin
- [ ] Remote join and Web Push shown as unavailable with explanation, not failing silently (FR-QUE-202, FR-MOB-041)
- [ ] Existing Remote tickets remain valid for in-person check-in (FR-MOB-041)
- [ ] Queuing on the LAN continues unaffected (ADR-0001)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
