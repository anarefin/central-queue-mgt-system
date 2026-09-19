# 19 — Wait estimation

**What to build:** Every issued Ticket and queue view shows an honest estimated wait as a rounded range that updates as the queue moves.

**Blocked by:** 10 — Counter session: call next, start service, complete; 11 — Realtime hub

**Status:** ready-for-agent

- [ ] estimate = tickets ahead ÷ max(open counters, 1) × rolling average handling time (FR-QUE-040)
- [ ] Rolling average of trailing 20 completed Tickets for the Service at the Site, falling back to expected handling time below 5 samples (FR-QUE-041)
- [ ] Recomputed on every queue change and presented as a rounded range, never a promise (FR-QUE-042, FR-ISS-005)
- [ ] `queue.estimate_changed` and `ticket.position_changed` events; issuance response carries the estimate
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
