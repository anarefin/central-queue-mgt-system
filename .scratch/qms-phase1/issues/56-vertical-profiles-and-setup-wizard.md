# 56 — Vertical profiles and first-run setup wizard

**What to build:** A consultant installs the system, picks a vertical profile (banking, healthcare, producer services, government, generic), and is walked through org, sites, zones, counters, services, users and devices — and cannot go live until a test token has been issued, printed, called and announced end to end.

**Blocked by:** 55 — Configuration versioning, revert and bundle; 29 — Voice announcements; 27 — Branding and printed token template

**Status:** done

- [~] Five shipped profiles carrying label overrides, starter catalogue, priority classes, numbering, report/KPI defaults, feature flags (§3.3, §3.4) — starter catalogue/numbering are Site-scoped and carried in the profile data, but the wizard's "services and numbering" step does not actually seed a new Site's catalogue/numbering from that data (correction, this pass): `SetupWizard.tsx` only links out to the existing catalogue/numbering admin screens as a manual starting point; there is no "seed from profile" action. Building that seeding (deciding how the profile's flat `starter_services` list maps to a service group/service, then looping `CatalogueService.createService`/`NumberingService.setRule`) needs real design choices the profile data itself does not settle, so it is left undone rather than guessed at here — see traceability matrix. Feature flags are now genuinely editable afterwards (fixed this pass): `GET`/`PUT /setup/feature-flags/{key}`, the endpoint `V47`'s own migration comment already promised but that did not exist until now
- [x] Terminology remapping via label keys resolved through pack and profile (§3.2)
- [x] No industry branches in code — variation only via config, labels, flags (CFG-001, NFR-MNT-005)
- [x] Profile applied only at first run or explicit reset; never on upgrade (CFG-002)
- [x] Setup wizard steps per §26.2; go-live blocked until an end-to-end test token passes (FR-OPS-010) — corrected this pass: "called" and "announced" used to be read off the identical `to_state = 'called'` event, so they could never diverge and "announced" was never actually independently confirmed; `setup_test_ticket` now carries its own `announced_at`/`announced_by` (mirroring `printed_at`/`printed_by`), confirmed through a new `POST /setup/test-token/{id}/confirm-announce`, refused with `409`/`test_token_not_called_yet` before the token has been called
- [x] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
