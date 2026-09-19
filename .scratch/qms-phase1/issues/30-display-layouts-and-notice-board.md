# 30 — More display layouts and the notice board

**What to build:** Displays can also run a split screen with scheduled notice content, a single large-token view for one room, or a lobby summary of waits per Service, and can cycle languages or show them side by side.

**Blocked by:** 28 — Display board: now-serving table

**Status:** ready-for-agent

- [ ] Layouts `split_media`, `single_counter`, `summary_board`, with zone proportions configurable without code (FR-DSP-003)
- [ ] Notice panel renders images, video or rich text uploaded by authorised users, in a scheduled playlist with per-item dates (FR-DSP-006, §5.2 notice-board permission)
- [ ] Language cycling at a configurable interval, or side by side where layout allows (FR-I18N-005)
- [ ] One image asset per language where images contain text (FR-I18N-032)
- [ ] Definition of done (SRS §27.5): user-facing strings in both en and bn packs; every protected action permission-checked server-side; specified events and audit entries emitted; each requirement ID above mapped to a passing test in the traceability matrix
