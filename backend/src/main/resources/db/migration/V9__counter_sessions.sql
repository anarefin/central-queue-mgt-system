-- Counter sessions and the serving columns of a ticket (SRS §11.1, §18.3, §18.4, §19.1, §19.3, FR-AGT-001,
-- FR-AGT-003, FR-QUE-031, ADR-0008).

-- An Agent's occupancy of one Counter, from open to close. `services` is the subset of the Counter's Services the Agent
-- chose to serve for this session (FR-AGT-003). A session that is `open`, `on_break` or `closing` still occupies its
-- Counter, so those three states are "live": at most one live session per Counter (FR-AGT-001, enforced here and not only
-- in code, §18.4) and one per Agent, who cannot sit at two desks. `closed` and `force_closed` are the ends.
CREATE TABLE IF NOT EXISTS counter_session (
    id         uuid PRIMARY KEY,
    counter_id uuid        NOT NULL REFERENCES counter (id),
    agent_id   uuid        NOT NULL REFERENCES users (id),
    opened_at  timestamptz NOT NULL,
    closed_at  timestamptz,
    services   uuid[]      NOT NULL,
    state      text        NOT NULL CHECK (state IN ('open', 'on_break', 'closing', 'closed', 'force_closed'))
);
CREATE UNIQUE INDEX IF NOT EXISTS counter_session_live_counter_uq ON counter_session (counter_id) WHERE state IN ('open', 'on_break', 'closing');
CREATE UNIQUE INDEX IF NOT EXISTS counter_session_live_agent_uq ON counter_session (agent_id) WHERE state IN ('open', 'on_break', 'closing');

-- The Session binding (ADR-0008): set when a ticket is called, kept while it is called, serving or held, cleared on every
-- return to waiting and on every terminal state. The ticket keeps `counter_id` and `agent_id` as history after that.
-- `wait_seconds` and `service_seconds` are computed and stored at closure so reports never recompute them (§18.5).
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS counter_session_id uuid REFERENCES counter_session (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS counter_id uuid REFERENCES counter (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS agent_id uuid REFERENCES users (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS called_at timestamptz;
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS served_at timestamptz;
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS closed_at timestamptz;
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS wait_seconds integer CHECK (wait_seconds IS NULL OR wait_seconds >= 0);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS service_seconds integer CHECK (service_seconds IS NULL OR service_seconds >= 0);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS outcome_code_id uuid REFERENCES outcome_code (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS note text;
CREATE INDEX IF NOT EXISTS ticket_session_idx ON ticket (counter_session_id) WHERE counter_session_id IS NOT NULL;
