# 57 — Outbound webhooks

**What to build:** An admin subscribes an external endpoint to any queue event type; deliveries are signed, retried, logged and replayable, and a failing endpoint never affects queuing.

**Blocked by:** 11 — Realtime hub

**Status:** ready-for-agent

- [ ] Every §21.4 event type subscribable per endpoint with a secret (FR-INT-020)
- [ ] HMAC-SHA256 over body + timestamp; retry with backoff; delivery log with replay (FR-INT-021)
- [ ] Failures never affect queue operation (FR-INT-022)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
