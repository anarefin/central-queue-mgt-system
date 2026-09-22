-- Vertical profiles and the first-run setup wizard (ticket 56, SRS §3, §26.2, FR-OPS-010).

-- A small set of singleton/keyed settings: which profile is active (and when), and whether the installation has
-- gone live. One row per key, read and written through system_setting_service (issuance.setup.SystemSettingRepository).
CREATE TABLE IF NOT EXISTS system_setting (
    key        text PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_by uuid,
    updated_at timestamptz NOT NULL
);

-- Feature flags a vertical profile turns on or off (CFG-001, §3.3.6): a closed vocabulary of keys, never an
-- industry branch in code. CFG-003: editable afterwards through PUT /setup/feature-flags/{key}.
CREATE TABLE IF NOT EXISTS feature_flag (
    key        text PRIMARY KEY,
    enabled    boolean     NOT NULL,
    updated_by uuid,
    updated_at timestamptz NOT NULL
);

-- Terminology remapping (§3.2): every visitor-facing noun is a label key resolved through the active language pack
-- and profile. This table carries the currently-effective override for a (key, lang) pair, seeded in bulk when a
-- profile is applied or reset, and editable one key at a time afterwards (CFG-003).
CREATE TABLE IF NOT EXISTS label_override (
    key        text        NOT NULL,
    lang       text        NOT NULL,
    value      text        NOT NULL,
    updated_by uuid,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (key, lang)
);

-- The wizard's own test token (FR-OPS-010): a real Ticket, issued through the normal pipeline, that the go-live
-- gate tracks issued -> printed -> called -> announced. "Called" and "announced" are read directly off the ticket
-- row it points to (state, announce_count); "printed" has no server-side signal of its own (printing is a client
-- render, ticket 27), so it is confirmed explicitly here.
CREATE TABLE IF NOT EXISTS setup_test_ticket (
    id          uuid PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES ticket (id),
    issued_at   timestamptz NOT NULL,
    issued_by   uuid        NOT NULL,
    printed_at  timestamptz,
    printed_by  uuid
);
CREATE INDEX IF NOT EXISTS setup_test_ticket_issued_at_idx ON setup_test_ticket (issued_at DESC);
