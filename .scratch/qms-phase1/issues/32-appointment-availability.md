# 32 — Appointment availability

**What to build:** An admin defines when appointments are available — per Service, team or individual Agent, with exceptions — and anyone can search a Service by date and see only slots with capacity left, automatically respecting business hours and holidays.

**Blocked by:** 21 — Issuance rules

**Status:** done

- [x] Availability at Service, team and Agent levels, most specific wins (FR-APT-001)
- [x] Slot templates with weekday pattern, start/end, slot minutes, concurrent capacity, validity range (FR-APT-002)
- [x] Exceptions: blocked dates, one-off extra availability, reduced capacity (FR-APT-003)
- [x] Business hours and holidays suppress slots; admin override per date (FR-APT-004)
- [x] Booking horizon (default 30 days) and minimum lead time (default 2 h) (FR-APT-005)
- [x] `GET /appointments/availability` by Service then date, showing only slots with remaining capacity (FR-APT-010)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
