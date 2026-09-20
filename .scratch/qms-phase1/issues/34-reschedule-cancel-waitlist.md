# 34 — Appointment reschedule, cancellation and waitlist

**What to build:** Appointments can be moved or cancelled — by visitors up to a cut-off, by staff any time with a reason — keeping the same reference code and a change history. Cancelled capacity frees immediately and, where enabled, is offered to the first waitlisted visitor.

**Blocked by:** 33 — Staff appointment booking

**Status:** done

- [x] Visitor reschedule/cancel up to a configurable cut-off (default 2 h); staff any time with reason (FR-APT-020)
- [x] Reschedule keeps the reference code and records history (FR-APT-021)
- [x] Cancellation returns capacity immediately (FR-APT-022)
- [x] Optional per-Service waitlist: first waitlisted visitor offered freed slot for a hold period (FR-APT-023)
- [x] `PATCH /appointments/{id}`, `DELETE /appointments/{id}`
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
