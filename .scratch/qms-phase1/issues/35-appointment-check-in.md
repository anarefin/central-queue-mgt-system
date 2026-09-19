# 35 — Appointment check-in converts to a Ticket

**What to build:** A visitor with an appointment checks in at the kiosk (code or QR) or at reception within the check-in window and gets a Ticket that is ordered ahead of later walk-ins but never ahead of someone already being served (UAT U2). Arriving early offers a walk-in Ticket instead.

**Blocked by:** 33 — Staff appointment booking; 26 — Kiosk identification and full selection tree; 09 — Queue ordering engine

**Status:** ready-for-agent

- [ ] Check-in at kiosk by code or QR and at reception (FR-ISS-030)
- [ ] Allowed only within a configurable window (default 30 min before to 15 min after) (FR-ISS-031)
- [ ] Early check-in offers a walk-in Ticket without cancelling the appointment (FR-ISS-032)
- [ ] Late beyond grace follows the no-show policy (FR-ISS-033)
- [ ] Ticket gets the appointment's Priority class; slot vs actual check-in difference recorded (FR-APT-030)
- [ ] Effective wait from the later of slot time and check-in, plus appointment bonus (default 15) (FR-QUE-020, FR-APT-032)
- [ ] Never displaces a Ticket being served (FR-APT-031)
- [ ] Appointment moves checked_in → converted (§19.2)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
