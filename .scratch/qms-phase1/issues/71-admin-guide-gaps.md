# 71 — Admin guide covers every configuration area

**What to build:** An implementation consultant can configure every admin area from `docs/admin-guide.md` alone, including the setup wizard, vertical profiles, terminology, feature flags, theme and branding, which the guide does not cover today. This guide is also the base the Phase B testing guide links to.

**Blocked by:** 67 — Profile seeds starter catalogue; 68 — Feature flags enforced; 69 — Label overrides rendered

**Status:** done

- [x] New sections, in the style of the existing 20 (purpose, where in the UI, API endpoints, rules and bounds, refusal reasons, audit events):
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
- [x] Section 18: remove the duplicated line (around line 638).
- [x] Table of contents updated; cross-links to `docs/api/error-codes.md` and `docs/adr/*`.
- [x] Every endpoint, bound and reason named in the new sections is checked against the code (a quick grep per item, noted in the ticket notes).
- [x] Definition of done (SRS §27.5): docs only, no user-facing strings; traceability rows for any FR-OPS documentation requirement updated

## Notes (ticket 71)

- New sections 21–30 appended after the existing section 20 (numbers 1–20 left untouched, since
  `docs/traceability-matrix.md` cites several of them by number, e.g. "`docs/admin-guide.md` §19/§20"); a
  "## Contents" block with anchor links to all 30 sections was added right after the intro, plus a
  "See also" line linking `docs/api/error-codes.md` and `docs/adr/`. The stray duplicated
  `` `visitor.import.mapping_updated`. `` line before old section 19 (line 638) was deleted.
- Every endpoint path, permission constant, refusal `reason`/`code` and audit event name named in the ten new
  sections was grepped against the actual controller/service source before being written (not merely
  recalled), e.g.: `SetupController`/`SetupWizardService`/`VerticalProfileService`/`CatalogueSeedingService`
  (setup wizard, profiles, seeding), `LabelController`/`LabelOverrideRepository`/`LabelRules` (terminology),
  `FeatureFlagController`/`FeatureFlagKey`/`FeatureFlags` plus each gate that calls
  `featureFlags.require(...)`/`isEnabled(...)` (`AppointmentBookingService`, `JourneyService`,
  `RemoteJoinService`, `VisitorService`, `HierarchyService`, `DisplayStateReads`) (feature flags),
  `BrandingController`/`BrandingService`/`BrandingRules`/`apply-brand.ts`/`color-contrast.ts`/`theme-provider.tsx`
  (branding and theme, incl. the WCAG contrast derivation and the dark-mode toggle living only in the app
  chrome), `NoticeController`/`NoticeService`/`NoticeRules` (notice board),
  `NotificationAdminController`/`NotificationTriggerKey`/`NotificationConsentService`/`NotificationDispatcher`/
  `AlertThresholdController`/`AlertThreshold` (notifications — quiet hours were verified to read
  `site.quiet_hours_start`/`_end` with no admin write path yet, documented as such rather than overstated),
  `FeedbackController`/`FeedbackService` (feedback moderation), `VisitorFieldConfigController`/
  `VisitorFieldConfigService`/`VisitorFieldSurface`/`VisitorPrivacyController`/`VisitorPrivacyService`
  (privacy), `WebhookEndpointController`/`WebhookDeliveryController`/`WebhookEventType`/`WebhookSigner`/
  `WebhookProperties`/`WebhookEndpointRules` (webhooks), and `ConfigBundleController`/`ConfigBundleService`/
  `ConfigBundleProperties`/`ConfigVersions`/`PriorityController`/`NumberingController`/
  `IssuanceRulesController` (config bundle and per-area version history/revert).
- No FR-OPS-prefixed row in `docs/traceability-matrix.md` cites a documentation gap this ticket's areas would
  close (the setup wizard's own FR-OPS-010 row was already `passing` on its test evidence, not gated on
  `docs/admin-guide.md`; the only "partial" FR-OPS rows are FR-OPS-041 printer-paper-status and FR-OPS-040
  diagnostics log lines, both unrelated to documentation coverage) — nothing to update there. Several
  non-FR-OPS "Definition of done §27.5 item 6" rows for other tickets (e.g. ticket 30's notice board, line
  697) note `docs/admin-guide.md` as a pre-existing gap; those rows belong to their own tickets and were left
  alone per this ticket's scope (FR-OPS documentation rows only).
