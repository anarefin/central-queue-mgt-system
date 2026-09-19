# 36 — Appointment no-shows

**What to build:** Appointments not checked in by slot time plus grace become no-shows automatically and free their capacity; optionally, repeat no-shows block further online booking without ever blocking walk-ins.

**Blocked by:** 33 — Staff appointment booking

**Status:** ready-for-agent

- [ ] Automatic `no_show` after slot + grace (FR-APT-040)
- [ ] Capacity freed immediately (FR-APT-041)
- [ ] Optional policy: N no-shows in a rolling window blocks online booking, never walk-in (default off; 3 in 90 days when on) (FR-APT-042)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
