# 28 — Display board: now-serving table

**What to build:** A paired TV in a waiting Zone shows which Token is being served at which Counter within 2 seconds of a call, highlights new calls, lists the next tokens, flags stale data honestly, and comes back by itself after a power cut or network drop.

**Blocked by:** 24 — Device pairing and fleet management; 11 — Realtime hub

**Status:** ready-for-agent

- [ ] Display registered with name, Zone, layout and language cycle; assignable to queues, Counters or a whole Zone (FR-DSP-001, FR-DSP-002)
- [ ] `now_serving_table` layout with configurable columns (at minimum token, counter label, service or staff) and next-N strip (default 4) (FR-DSP-003, FR-DSP-004, FR-DSP-005)
- [ ] New calls highlighted for a configurable period (default 10 s) (FR-DSP-007)
- [ ] Update within 2 s of a call at P95 (FR-DSP-010, NFR-PERF-002)
- [ ] Auto-reconnect; discreet stale indicator after 30 s without update (FR-DSP-011)
- [ ] Resumes Zone and layout after power loss with no login; full state from `GET /devices/{id}/display-state` (FR-DSP-012, NFR-AVL-006)
- [ ] Polling fallback at 5 s (FR-QUE-084)
- [ ] Public display shows token and counter only by default (FR-SEC-020)
- [ ] Token text ≥ 60 px on 43-inch 1080p, configurable upward (NFR-USA-004)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
