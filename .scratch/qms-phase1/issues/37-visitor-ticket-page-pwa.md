# 37 — Visitor ticket page (PWA)

**What to build:** A visitor who scans the QR on their token (or on the kiosk's printer-failure screen) opens a mobile web page showing their live position, estimated wait range and the Token now being served; it shows the last known position with a timestamp if the connection drops, tells them the floor and Zone, and lets them cancel. The app is installable as a PWA (UAT U8).

**Blocked by:** 11 — Realtime hub; 19 — Wait estimation

**Status:** ready-for-agent

- [x] Installable PWA with manifest and service worker (FR-MOB-003, ADR-0011, ADR-0012)
- [x] Anonymous access by ticket id + `X-Ticket-Secret`, read-only on that Ticket; token number alone reveals nothing (§20.2, FR-SEC-033)
- [x] Live position, estimate range and current serving token via `ticket:` topic (FR-MOB-013)
- [x] On connectivity loss shows last known position with its timestamp, never as current (FR-MOB-040); polling fallback at 15 s (FR-QUE-084)
- [x] Shows floor and Zone, optional static wayfinding image per Zone (FR-MOB-032)
- [x] Visitor cancels their own Ticket any time before being called (FR-MOB-030)
- [x] Kiosk printer-failure QR opens this page (FR-ISS-016, U8)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
