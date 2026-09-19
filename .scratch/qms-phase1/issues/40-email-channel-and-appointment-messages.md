# 40 — Email channel and appointment messages

**What to build:** Visitors receive appointment confirmations, change and cancellation notices, waitlist offers and reminders by email (and Web Push where subscribed) in their preferred language, through the client's own SMTP relay.

**Blocked by:** 38 — Notification pipeline and in-app channel; 34 — Appointment reschedule, cancellation and waitlist

**Status:** ready-for-agent

- [ ] SMTP adapter behind `NotificationChannel`, configured per installation (§14.1, FR-INT-040)
- [ ] Appointment confirmed, rescheduled/cancelled and waitlist-offer triggers (§14.2)
- [ ] Reminders at configurable offsets (default 24 h and 1 h) in preferred language (FR-APT-050)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
