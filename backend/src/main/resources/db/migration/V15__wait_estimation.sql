-- Wait estimation (SRS §10.5, FR-QUE-040..042, §21.4).

-- The place each queued ticket was last announced at, so `ticket.position_changed` is published only for a ticket whose place
-- really moved. It is a cache of what subscribers were told, not a source of truth: the live place is always computed from
-- the queue (FR-QUE-001), and a row exists only while its ticket is waiting or paused. There is deliberately no foreign key,
-- so writing it never takes a lock on a ticket row that another transition may hold.
CREATE TABLE IF NOT EXISTS ticket_position (
    ticket_id  uuid PRIMARY KEY,
    service_id uuid    NOT NULL,
    position   integer NOT NULL CHECK (position >= 1)
);
CREATE INDEX IF NOT EXISTS ticket_position_service_idx ON ticket_position (service_id);

-- The trailing completed tickets of a service, newest first: the sample of the rolling average handling time (FR-QUE-041).
CREATE INDEX IF NOT EXISTS ticket_completed_service_idx ON ticket (service_id, closed_at DESC) WHERE state = 'completed';
