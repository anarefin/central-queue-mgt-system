# 39 — Web Push channel

**What to build:** A visitor on the ticket page opts into browser notifications and gets a push on their phone when they are called, missed, transferred or marked no-show — even with the page closed. iOS visitors are told to add the app to their home screen first.

**Blocked by:** 38 — Notification pipeline and in-app channel; 37 — Visitor ticket page (PWA)

**Status:** ready-for-agent

- [ ] VAPID key pair generated per installation, secret kept outside source control (§14.1, NFR-SEC-013)
- [ ] Service worker handles push and notification click back to the ticket page (ADR-0011)
- [ ] `POST /tickets/{id}/push-subscription` stores subscriptions; revoked on expiry/410 (§18.3)
- [ ] Web Push adapter registered behind `NotificationChannel` (FR-INT-040)
- [ ] Your-turn, missed, no-show and transferred triggers delivered (§14.2)
- [ ] iOS guidance about home-screen install shown on the ticket and join screens (FR-MOB-020)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
