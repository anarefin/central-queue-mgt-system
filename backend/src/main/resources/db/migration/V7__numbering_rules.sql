-- Configurable token numbering and its scheduled resets (SRS §4.4, §18.2, FR-CFG-018, FR-CFG-019, FR-QUE-201, ADR-0010).

-- One rule per Service or per Service group (FR-CFG-018). A Service without its own rule uses its group's rule, and
-- one with neither uses the built-in default (service prefix, separator "-", padding 3, daily reset at 00:00). A rule
-- only ever shapes tickets issued after it changes; issued tickets keep their token number (FR-CFG-041).
CREATE TABLE IF NOT EXISTS numbering_rule (
    id             uuid PRIMARY KEY,
    site_id        uuid        NOT NULL REFERENCES site (id),
    scope_type     text        NOT NULL CHECK (scope_type IN ('service', 'service_group')),
    -- Refers to service.id or service_group.id according to scope_type, so it cannot be a plain foreign key.
    scope_id       uuid        NOT NULL,
    prefix_source  text        NOT NULL CHECK (prefix_source IN ('service', 'service_group', 'priority_class', 'fixed')),
    fixed_prefix   text,
    sequence_start bigint      NOT NULL DEFAULT 1 CHECK (sequence_start >= 0),
    padding        integer     NOT NULL DEFAULT 3 CHECK (padding BETWEEN 0 AND 6),
    reset_boundary text        NOT NULL DEFAULT 'daily' CHECK (reset_boundary IN ('daily', 'weekly', 'monthly', 'never')),
    -- Site-local time of day at which a period ends and the next begins.
    reset_time     time        NOT NULL DEFAULT '00:00',
    separator      text        NOT NULL DEFAULT '-',
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (scope_type, scope_id),
    CHECK ((prefix_source = 'fixed') = (fixed_prefix IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS numbering_rule_site_idx ON numbering_rule (site_id);

-- One row per numbering scope and reset period, written the first time that period is opened, either by the scheduled
-- reset at site-local reset time, by the replay of one that was missed, or by the first ticket issued in the period. It
-- is what makes the scheduled reset idempotent: a period already recorded is never opened twice (FR-CFG-019).
CREATE TABLE IF NOT EXISTS numbering_reset (
    id           uuid PRIMARY KEY,
    site_id      uuid        NOT NULL REFERENCES site (id),
    scope_key    text        NOT NULL,
    reset_key    text        NOT NULL,
    period_start timestamptz NOT NULL,
    triggered_by text        NOT NULL CHECK (triggered_by IN ('scheduled', 'replayed', 'issuance')),
    executed_at  timestamptz NOT NULL,
    UNIQUE (scope_key, reset_key)
);
CREATE INDEX IF NOT EXISTS numbering_reset_site_idx ON numbering_reset (site_id, period_start);
