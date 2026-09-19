# 0011 — Phase 1 channels: in-app realtime, Web Push, email, staff alert; SMS and native apps in Phase 2

Status: Accepted · 2026-09-18

## Context
SRS v1.2 Phase 1 relied on SMS for approaching-turn alerts, OTP login, printer-failure recovery and appointment
messages, with native Android/iOS apps and FCM/APNs push deferred. The decision is to ship push in Phase 1 for
the web only, and to move SMS entirely to Phase 2.

## Decision
- Phase 1 notification channels: in-app realtime, **Web Push** (VAPID + service worker), email, staff alert.
- SMS and native push (FCM/APNs) are Phase 2 adapters behind the `NotificationChannel` interface.
- The visitor surface is a mobile-web PWA only; native apps are Phase 2.
- Visitor login: email OTP (registered) or anonymous with ticket/appointment reference plus secret.
- Printer failure (FR-ISS-016): on-screen token number plus a QR that opens the ticket's PWA page, where the
  visitor can opt into Web Push.
- Token numbers use Western Arabic digits on every surface; Bangla audio speaks the number in Bangla.
- Post-service feedback (FR-MOB-033) is in Phase 1.

## Consequences
- iOS visitors receive Web Push only if they add the site to their home screen (iOS 16.4+); others must keep
  the page open. There is no Phase 1 fallback channel for them.
- Web Push requires internet reachability to browser vendor push services and a publicly reachable HTTPS origin
  for the visitor PWA.
- Visitors with no email and no smartphone have no remote-notification path in Phase 1.

SRS refs: §1.1, FR-ISS-016, FR-MOB-001, FR-MOB-003, FR-MOB-020, §14, FR-I18N-020, FR-MOB-033, §27.2 U8, §28.4.
