-- Kiosk identification and selection tree (ticket 26, SRS §8.2, FR-ISS-010, FR-ISS-011, FR-ISS-013, FR-ISS-014).
--
-- The team level is always exactly one option (CONTEXT.md: one team per service group), so it is never its own
-- screen (§8.2's "steps that resolve to a single option are skipped automatically"); `team_selectable` instead gates
-- whether the individual-agent level is offered at all, since picking an individual only makes sense once the team
-- dimension is in play. `individual_selectable` gates the agent level on its own. The custom level is enabled by
-- having at least one option; `custom_level_options` carries `[{"id": "...", "name_i18n": {...}}, ...]`.
ALTER TABLE service_group
    ADD COLUMN IF NOT EXISTS team_selectable boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS individual_selectable boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS custom_level_name_i18n jsonb,
    ADD COLUMN IF NOT EXISTS custom_level_options jsonb;

-- What the visitor picked and resolved to at the kiosk, denormalised onto the ticket like every other issuance
-- context (V6's header): a later reconfiguration of the team, roster or custom level must never rewrite history.
-- `target_agent_id` already exists (V11, transfer) and is reused here as the same "waits in that Agent's personal
-- queue" mechanism (FR-ISS-012); only `custom_level_id` is new.
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS custom_level_id text;
