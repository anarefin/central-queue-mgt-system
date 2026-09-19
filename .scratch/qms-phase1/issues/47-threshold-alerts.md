# 47 — Threshold alerts

**What to build:** Team Admins are alerted in-app when a Service's queue, waits, idle counters, no-show rate, agent break overruns or offline devices cross configured thresholds; alerts escalate to Org Admin if ignored, can be acknowledged with a note, and repeated breaches group into one alert.

**Blocked by:** 46 — Live dashboard; 24 — Device pairing and fleet management; 38 — Notification pipeline and in-app channel

**Status:** ready-for-agent

- [ ] Thresholds per Service: queue length, longest wait, idle counters with waiting queue, no-show rate, device offline duration (FR-MON-020)
- [ ] Breach raises an in-app alert to the relevant Team Admin, optional escalation to Org Admin after a delay (FR-MON-021)
- [ ] Acknowledge with optional note, recorded (FR-MON-022)
- [ ] Repeated breaches within a window grouped (FR-MON-023)
- [ ] Break overrun alert (FR-AGT-023); SLA breach, counter unattended, device offline staff alerts (§14.2)
- [ ] `site:{id}:alerts` topic; `alert.raised` / `alert.acknowledged` events
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
