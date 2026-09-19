# 61 — Performance and UAT acceptance suite

**What to build:** The release can be signed off: a load test at 3× the client's peak meets every §23.1 target for 30 minutes, and UAT scenarios U1–U12 plus the vertical-specific scenarios run clean against each shipped profile with only configuration changed.

**Blocked by:** 56 — Vertical profiles and first-run setup wizard; 44 — Internet-loss degradation; 51 — Appointment, journey, feedback, notification and audit reports; 60 — Installer, upgrades, backup and diagnostics

**Status:** ready-for-agent

- [ ] Load test at 3× expected peak for 30 min, §23.1 targets met, error rate ≤ 0.1% (§27.4)
- [ ] Capacity: 50 sites / 500 counters / 2,000 staff, 20,000 tickets/day with 60/min peak, 5,000 realtime subscribers (NFR-CAP-001..003)
- [ ] Five years of seeded history without breaching §23.1 (NFR-CAP-004)
- [ ] Playwright UAT scripts for U1–U12 per banking, healthcare, producer_services profiles (§27.2)
- [ ] Vertical-specific scenarios (§27.3)
- [ ] Engine suite covers every §19.1 transition and every §10.3 strategy (NFR-MNT-004)
- [ ] Traceability matrix complete: every Phase 1 MUST mapped to a passing test or UAT step (§27.1)
- [ ] OWASP Top 10 test pass with criticals fixed (NFR-SEC-050)
- [ ] 99.5% availability measured over a pilot month (NFR-AVL-001)
- [ ] Console training to competence in 30 minutes verified in UAT (NFR-USA-005)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
