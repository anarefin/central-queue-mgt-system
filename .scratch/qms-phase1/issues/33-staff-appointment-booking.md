# 33 — Staff appointment booking

**What to build:** Reception books an appointment for a visitor — including phone-call bookings — against live capacity, and the visitor gets a unique reference code and QR. Two people racing for the last seat get exactly one booking.

**Blocked by:** 32 — Appointment availability; 22 — Visitor directory and walk-in registration

**Status:** done

- [x] Transactional booking against remaining capacity; last-seat race yields exactly one success (FR-APT-011)
- [x] Slot held for a configurable period (default 5 min) during booking, then released by a job (FR-APT-012, §19.2)
- [x] Staff booking records source `phone`, `walk_in` or `staff` (FR-APT-013)
- [x] Unique reference code and QR per appointment (FR-APT-014)
- [x] Captures visitor identity or minimal contact, Service, slot, optional preferred Agent, purpose note, preferred language (FR-APT-015)
- [x] Max active appointments per visitor (default 3) (FR-APT-016)
- [x] Capacity consumed/released per the appointment lifecycle (§19.2)
- [x] `POST /appointments`; reception booking screen
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
