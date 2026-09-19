# 29 — Voice announcements

**What to build:** When a Ticket is called or re-announced, the Zone's display plays a chime and announces the Token number and Counter in each configured language in turn — correctly pronounced in Bangla — without overlapping, without replaying old calls after a reconnect, and silently during quiet periods.

**Blocked by:** 28 — Display board: now-serving table; 12 — Re-announce and Miss

**Status:** ready-for-agent

- [ ] Call event plays in the Zone containing the Counter (FR-DSP-020)
- [ ] Template per language from token number, counter label, service name, floor, optional visitor name (FR-DSP-021)
- [ ] Visitor name announcement is a per-Service flag, default off (FR-DSP-022)
- [ ] Languages played in sequence in configurable order (FR-DSP-023)
- [ ] Clip assembly (offline) and TTS with fallback to clips; voice selectable per language (FR-DSP-024, FR-DSP-031)
- [ ] Chime selectable and volume-controllable per Zone (FR-DSP-025)
- [ ] Announcements queue without overlap; configurable max depth keeps the most recent per Counter (FR-DSP-026)
- [ ] Quiet periods per Zone suppress audio but not display (FR-DSP-027)
- [ ] Re-announce fires a repeat up to the repeat limit (FR-DSP-028)
- [ ] Dedupe by ticket id + `announce_count` so replays never re-announce (FR-QUE-083)
- [ ] Per-language spoken prefixes; Bangla speaks numbers in Bangla (FR-DSP-030, FR-I18N-020)
- [ ] Each language pack carries a clip set or TTS mapping; new prefixes prompt for spoken forms before going live (FR-I18N-040, FR-I18N-041)
- [ ] Clinical-sensitivity neutral labels respected once ticket 54 lands (FR-SEC-021 hook)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
