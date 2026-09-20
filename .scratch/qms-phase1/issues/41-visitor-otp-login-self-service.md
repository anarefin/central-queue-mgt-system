# 41 — Visitor email-OTP login and self-service appointments

**What to build:** A visitor signs in to the mobile web app with an emailed one-time code, sees their active Tickets, appointment history and saved Sites, and books, reschedules or cancels their own appointments within the allowed cut-offs.

**Blocked by:** 37 — Visitor ticket page (PWA); 40 — Email channel and appointment messages; 34 — Appointment reschedule, cancellation and waitlist

**Status:** ready-for-agent

- [x] Email + OTP login issuing a visitor-role JWT; anonymous reference + secret still supported (FR-MOB-001, §20.2)
- [x] OTPs never logged (API-018); refresh token handled per API-017
- [x] Active Tickets, appointment history, saved Sites (FR-MOB-002)
- [x] Self-service booking, reschedule and cancel with visitor permissions "own only" (§5.2, FR-APT-020)
- [x] Visitor issuance rate limit (API-090)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
