# 71 — Admin guide covers every configuration area

**What to build:** An implementation consultant can configure every admin area from `docs/admin-guide.md` alone, including the setup wizard, vertical profiles, terminology, feature flags, theme and branding, which the guide does not cover today. This guide is also the base the Phase B testing guide links to.

**Blocked by:** 67 — Profile seeds starter catalogue; 68 — Feature flags enforced; 69 — Label overrides rendered

**Status:** todo

- [ ] New sections, in the style of the existing 20 (purpose, where in the UI, API endpoints, rules and bounds, refusal reasons, audit events):
  - **Setup wizard and vertical profiles:**
    - the five profiles and what each seeds (labels, flags, priority classes, KPI defaults)
    - apply vs reset (`already_provisioned`)
    - seed starter catalogue (ticket 67)
    - the test token (issued, printed, called, announced) and go-live (`setup_incomplete`)
  - **Terminology (label overrides):** the keys, per-language editing, reset, and how devices and the visitor page pick them up (ticket 69)
  - **Feature flags:** the six flags, master-switch semantics vs finer settings, `feature_disabled` (ticket 68)
  - **Branding and theme:** org name, brand colour (including the contrast rule), logo, printed-token template, dark mode (ticket 62)
  - **Notice board**
  - **Notifications:** triggers, templates, channels, quiet hours, essential triggers, delivery log, alert thresholds
  - **Visitor feedback moderation**
  - **Privacy:** field config surfaces, clinical sensitivity, export and anonymise
  - **Webhooks:** events, signing, retries, replay, secret rotation
  - **Config bundle and versioning:** export, import, `QMS_CONFIG_BUNDLE_SECRET`, revert
- [ ] Section 18: remove the duplicated line (around line 638).
- [ ] Table of contents updated; cross-links to `docs/api/error-codes.md` and `docs/adr/*`.
- [ ] Every endpoint, bound and reason named in the new sections is checked against the code (a quick grep per item, noted in the ticket notes).
- [ ] Definition of done (SRS §27.5): docs only, no user-facing strings; traceability rows for any FR-OPS documentation requirement updated
