# 14 — Realtime re-auth, revocation and user disable

**What to build:** Realtime connections cannot outlive their credentials: sockets must re-authenticate before token expiry, and disabling a user or changing their roles drops their sockets at once, closes their Counter session and puts their bound Tickets back at the front of the queue.

**Blocked by:** 11 — Realtime hub; 13 — Hold, held-by-me and force-close

**Status:** ready-for-agent

- [x] Hub closes a socket at token `exp` unless a `reauth` frame with a fresh token arrives first (ADR-0009, §21.1)
- [x] Disabling a user, changing roles/scopes or revoking a device publishes `principal.changed`; the hub drops that subject's sockets immediately; resubscribe re-authorises topics within 30 s (FR-QUE-080) (device revocation: no device entity exists yet; it must call `PrincipalChangedPublisher.principalChanged` when it lands)
- [x] Disabling a user closes their open session, returns called/serving/held Tickets to waiting at the front via Score adjustment, invalidates refresh tokens (FR-CFG-104, ADR-0008)
- [x] Realtime-client sends reauth ahead of expiry and reconnects cleanly on drop
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
