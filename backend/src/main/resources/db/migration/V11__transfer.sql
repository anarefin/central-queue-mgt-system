-- Transfer to a Successor ticket (SRS §10.6, §19.1, FR-QUE-003, FR-QUE-052, FR-QUE-053, ADR-0006). A successor is an ordinary
-- ticket row linked by `predecessor_ticket_id` (V6); what it adds is who it is meant for besides its Service.
--
-- A ticket targeted at a specific Agent waits in that Agent's personal queue and no other Counter draws it (FR-QUE-003); one
-- targeted at a specific Counter is drawn by that Counter alone. Both null means anyone who serves the Service. The targets are
-- kept while the ticket returns to waiting (a Miss, a force-close), and cleared only when an admin reassigns it.
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS target_counter_id uuid REFERENCES counter (id);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS target_agent_id uuid REFERENCES users (id);
CREATE INDEX IF NOT EXISTS ticket_target_agent_idx ON ticket (target_agent_id) WHERE target_agent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ticket_predecessor_idx ON ticket (predecessor_ticket_id) WHERE predecessor_ticket_id IS NOT NULL;
