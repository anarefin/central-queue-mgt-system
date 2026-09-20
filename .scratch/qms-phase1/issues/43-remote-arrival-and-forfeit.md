# 43 — Remote arrival, check-in and forfeit

**What to build:** A remote visitor is told to set off when their turn is near, marks themself present by scanning the site QR or tapping check-in inside the geofence (or reception does it), and is then called normally (UAT U3). If they don't arrive by the deadline, the disclosed forfeit policy is applied exactly (UAT U4). They may ask once to be moved back.

**Blocked by:** 42 — Remote join; 39 — Web Push channel; 12 — Re-announce and Miss

**Status:** done

- [x] Approaching-turn notification at a threshold (default 3 ahead or 15 min) via Web Push and in-app (FR-MOB-020)
- [x] Present by site QR, geofence check-in or reception; geofence radius per Site; QR accepted as drift fallback (FR-MOB-021, FR-MOB-024)
- [x] `POST /tickets/{id}/check-in` moves remote → waiting within the arrival window (§19.1)
- [x] Remote ticket at the front is held for the deadline then forfeited: back N (default 5) via Score adjustment, or cancel → `forfeited` (FR-MOB-022, ADR-0004)
- [x] One delay per Ticket moving back N (default 3) via Score adjustment, if the Service allows (FR-MOB-031)
- [x] `POST /tickets/{id}/delay`; engine suite covers remote→waiting and remote→forfeited (NFR-MNT-004)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
