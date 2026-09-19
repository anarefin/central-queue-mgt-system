-- Physical hierarchy: Site > Zone > Counter (SRS §18.2, FR-CFG-001..004, ADR-0002).
-- Rows are deactivated, never deleted (FR-CFG-001), so historical tickets keep resolving to them; the foreign keys
-- below also make a hard delete of a referenced row fail. All timestamps are UTC (`timestamptz`); the site's
-- `timezone` is only used to render them (FR-CFG-002).
CREATE TABLE IF NOT EXISTS site (
    id                uuid PRIMARY KEY,
    name              text        NOT NULL,
    code              text        NOT NULL,
    timezone          text        NOT NULL,
    address           text        NOT NULL,
    default_language  text        NOT NULL,
    -- ordered list of language codes, e.g. ["bn","en"] (FR-I18N-002)
    enabled_languages jsonb       NOT NULL,
    active            boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS site_code_lower_uq ON site (lower(code));

CREATE TABLE IF NOT EXISTS zone (
    id             uuid PRIMARY KEY,
    site_id        uuid        NOT NULL REFERENCES site (id),
    name           text        NOT NULL,
    building_label text,
    floor_label    text        NOT NULL,
    display_order  integer     NOT NULL DEFAULT 0,
    active         boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS zone_site_idx ON zone (site_id, display_order);

CREATE TABLE IF NOT EXISTS counter (
    id            uuid PRIMARY KEY,
    zone_id       uuid        NOT NULL REFERENCES zone (id),
    label         text        NOT NULL,
    location_note text,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS counter_zone_idx ON counter (zone_id);
