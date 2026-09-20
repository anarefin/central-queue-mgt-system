-- Display board: now-serving table configuration (SRS §12.1, FR-DSP-001..005, FR-DSP-007, ticket 28). A display is
-- registered (paired, ticket 24) with a name and a zone already; this adds the rest of FR-DSP-001's list -- layout
-- and language cycle -- plus the column set, the next-token strip depth, the highlight period and what the display
-- is assigned to within its zone (FR-DSP-002). These columns are meaningless for a kiosk row and simply keep their
-- defaults there, the same way `zone_id` is meaningless (and null) for a kiosk.
ALTER TABLE device ADD COLUMN IF NOT EXISTS layout text NOT NULL DEFAULT 'now_serving_table';
-- Ordered list of language codes to cycle through, e.g. ["bn","en"] (FR-DSP-001), the same shape as site.enabled_languages.
ALTER TABLE device ADD COLUMN IF NOT EXISTS language_cycle jsonb NOT NULL DEFAULT '["en"]'::jsonb;
-- The next-token strip's depth per queue; default 4 (FR-DSP-005).
ALTER TABLE device ADD COLUMN IF NOT EXISTS next_n integer NOT NULL DEFAULT 4 CHECK (next_n > 0);
-- How long a newly called token stays highlighted; default 10 seconds (FR-DSP-007).
ALTER TABLE device ADD COLUMN IF NOT EXISTS highlight_seconds integer NOT NULL DEFAULT 10 CHECK (highlight_seconds > 0);
-- The serving table's configurable column set (FR-DSP-004); default token+counter only (FR-SEC-020's public-display default).
ALTER TABLE device ADD COLUMN IF NOT EXISTS columns jsonb NOT NULL DEFAULT '["token","counter"]'::jsonb;
-- What within the zone this display is assigned to (FR-DSP-002): the whole zone, specific counters, or specific
-- queues (Services). `assignment_ids` is empty for `zone` and a list of Counter or Service ids otherwise.
ALTER TABLE device ADD COLUMN IF NOT EXISTS assignment_scope text NOT NULL DEFAULT 'zone'
    CHECK (assignment_scope IN ('zone', 'counters', 'queues'));
ALTER TABLE device ADD COLUMN IF NOT EXISTS assignment_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
