-- Ticket 30 (FR-DSP-003, FR-DSP-006, FR-I18N-005): the shipped display layout set grows from `now_serving_table`
-- (ticket 28) to also offer `split_media`, `single_counter` and `summary_board`. `layout_config` carries the one
-- zone-proportion setting each of those needs without a code change (FR-DSP-003): `split_media`'s serving/notice
-- panel split percentage, or `single_counter`'s chosen Counter; empty for a layout that needs neither.
-- `language_cycle_seconds` is the interval FR-I18N-005's language cycling runs at; 0 disables cycling and instead
-- renders the cycle side by side where the layout allows, rather than one language at a time.
ALTER TABLE device ADD COLUMN IF NOT EXISTS layout_config jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE device ADD COLUMN IF NOT EXISTS language_cycle_seconds integer NOT NULL DEFAULT 10 CHECK (language_cycle_seconds >= 0);

-- Notice-board content (FR-DSP-006, SRS §5.2 "Manage notice-board content", permission `notice_board:manage`):
-- images, video or rich text, scheduled with a start and end date, scoped to the Zone whose display(s) show it.
-- `content_i18n` maps a language code to the content for that language -- an absolute URL or a `data:` URI for
-- `image`/`video` (the same convention as `org_branding.logo_url`), or plain text for `rich_text` -- with one entry
-- per enabled language so an image containing text can ship one asset per language (FR-I18N-032). Rows are
-- deactivated, never deleted, the same convention as every other physical/configuration record in this schema.
CREATE TABLE IF NOT EXISTS notice (
    id           uuid PRIMARY KEY,
    zone_id      uuid        NOT NULL REFERENCES zone (id),
    type         text        NOT NULL CHECK (type IN ('image', 'video', 'rich_text')),
    content_i18n jsonb       NOT NULL,
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL,
    sort_order   integer     NOT NULL DEFAULT 0,
    active       boolean     NOT NULL DEFAULT true,
    created_by   uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notice_dates_chk CHECK (ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS notice_zone_active_idx ON notice (zone_id, active, starts_at, ends_at);
