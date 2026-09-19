# 11 — Realtime hub and realtime client

**What to build:** Consoles see their queue and Counter update live without refreshing: the backend streams topic snapshots and ordered deltas over WebSocket, clients recover from reconnects without gaps or duplicates, and fall back to polling where WebSocket is blocked.

**Blocked by:** 10 — Counter session: call next, start service, complete

**Status:** done

- [x] `/api/v1/stream` authenticated with the REST bearer token; subscribe frame; snapshot per topic then deltas (§21.1)
- [x] Topic authorisation checked at subscribe time (FR-QUE-080)
- [x] Event envelope with topic, per-topic monotonic seq, type, occurred_at, data (§21.3); event types per §21.4
- [x] Heartbeat every 20 s each way; client reconnects after two missed (§21.1)
- [x] Replay from a buffer of ≥ 5 min / 1,000 events per topic, else fresh snapshot with `resync: true` (FR-QUE-081)
- [x] Shared realtime-client applies events idempotently by seq (FR-QUE-082) and falls back to polling at configurable intervals, flagging degraded mode in diagnostics only (FR-QUE-084)
- [x] Publishing sits behind one interface so cross-node fan-out can be added (ticket 59)
- [x] `queue:{service_id}` and `counter:{counter_id}` topics wired; console updates live
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
