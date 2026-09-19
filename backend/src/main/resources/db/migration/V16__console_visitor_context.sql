-- What the Agent console shows about the visitor of a called ticket (SRS §11.5, §18.3, FR-AGT-030, FR-AGT-034): the visitor
-- (external code, name, category) the ticket belongs to and the purpose note the visitor or reception gave. Both are optional:
-- a ticket may belong to an anonymous visitor. Who fills them (the visitor directory, walk-in registration, appointment
-- booking) is later tickets' work; the console reads them from here. The rest of a visitor's record (phone, email,
-- preferred language, consent) joins this table with the ticket that first captures it.
CREATE TABLE IF NOT EXISTS visitor (
    id            uuid PRIMARY KEY,
    external_code text,
    name          text,
    category      text,
    created_at    timestamptz NOT NULL
);

ALTER TABLE ticket ADD COLUMN IF NOT EXISTS visitor_id uuid REFERENCES visitor (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS purpose_note text;
CREATE INDEX IF NOT EXISTS ticket_visitor_idx ON ticket (visitor_id) WHERE visitor_id IS NOT NULL;

-- The Agent's own day (FR-AGT-040) reads their completed tickets and their breaks by agent and closing time.
CREATE INDEX IF NOT EXISTS ticket_agent_closed_idx ON ticket (agent_id, closed_at) WHERE agent_id IS NOT NULL AND closed_at IS NOT NULL;
