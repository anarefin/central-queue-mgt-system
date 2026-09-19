-- Breaks (SRS §11.3, §18.2, §18.3, §19.3, FR-AGT-020, FR-AGT-021, FR-AGT-022).

-- A kind of break an Agent may take (lunch, prayer, meeting, system issue), with an optional maximum duration in minutes
-- (FR-AGT-020). Break types are organisation-wide and are deactivated, never deleted, so a past break keeps its type.
CREATE TABLE IF NOT EXISTS break_type (
    id          uuid PRIMARY KEY,
    name_i18n   jsonb       NOT NULL,
    max_minutes integer CHECK (max_minutes IS NULL OR max_minutes >= 1),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

-- One break of one counter session, from start to end (FR-AGT-022). A session that is `on_break` has exactly one record
-- with no `ended_at`, and one that is not has none: enforced here and not only in code. `started_by` and `ended_by` are
-- the Agent, or the admin who forced the availability change (FR-AGT-024). The Agent a break belongs to is the session's.
CREATE TABLE IF NOT EXISTS break_record (
    id                 uuid PRIMARY KEY,
    counter_session_id uuid        NOT NULL REFERENCES counter_session (id),
    break_type_id      uuid        NOT NULL REFERENCES break_type (id),
    started_at         timestamptz NOT NULL,
    ended_at           timestamptz,
    started_by         uuid        NOT NULL REFERENCES users (id),
    ended_by           uuid REFERENCES users (id),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS break_record_open_uq ON break_record (counter_session_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS break_record_session_idx ON break_record (counter_session_id);
CREATE INDEX IF NOT EXISTS break_record_started_idx ON break_record (started_at);
