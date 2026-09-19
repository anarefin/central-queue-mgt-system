# 02 — Language packs and i18n foundation

**What to build:** Every surface can render in English or Bangla from language packs, picking the language by the most specific preference available, never showing a raw key, and always rendering Token numbers in Western Arabic digits. API errors carry localised messages. This is the shared i18n layer all later UI tickets use.

**Blocked by:** 01 — Walking skeleton

**Status:** ready-for-agent

- [ ] English and Bangla UI packs shipped complete; additional packs can be added without a code release (FR-I18N-001)
- [ ] Language resolved from user/visitor preference → device setting → site default → system default (FR-I18N-003)
- [ ] Missing translation falls back to the site default language, never a raw key or empty string (FR-I18N-011)
- [ ] Numerals render in Western Arabic or Bengali digits per pack; Token numbers always in Western Arabic digits on every surface (FR-I18N-020, ADR-0011)
- [ ] Dates, times and currency follow the rendering locale, with a 12/24-hour preference per site (FR-I18N-021)
- [ ] Error envelope includes `message_i18n`; the backend honours `Accept-Language` (§20.3)
- [ ] Shared UI kit uses logical CSS properties only, so RTL is not blocked, and layouts tolerate 40% string expansion (FR-I18N-030, FR-I18N-031)
- [ ] Bundled fonts cover Bengali script including conjuncts (FR-I18N-023)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
