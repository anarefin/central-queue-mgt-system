# 24 — Device pairing and fleet management

**What to build:** An admin pairs a kiosk or display by entering a short-lived code on the device; the device gets its own credential, reports heartbeats, loads its bootstrap configuration, appears in a central health view, and can be revoked or told to reload remotely.

**Blocked by:** 05 — Site, Zone and Counter administration; 11 — Realtime hub

**Status:** ready-for-agent

- [x] Short-lived pairing code exchanged once for a device JWT carrying device role and site/zone (FR-OPS-011, §20.2)
- [x] Per-device credentials stored hashed, rotatable, individually revocable; revoke publishes `principal.changed` (NFR-SEC-005, FR-DSP-013)
- [x] Device refresh credential kept in an OS-permission-restricted file on kiosk/display shells (API-017)
- [x] `GET /config/bootstrap` returns branding, languages, layout and service tree (§20.4)
- [x] `POST /devices/{id}/heartbeat` with health and version; central device health view with last heartbeat, version, connectivity (FR-OPS-041)
- [x] Admin pushes reload or config update via the `device:` topic (FR-OPS-042)
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
