-- Remote join (ticket 42, SRS §13.2, FR-MOB-010..012, FR-MOB-023). `ticket.state` already allows 'remote' (V6, forward
-- declared for this ticket) and the engine already treats it as queued but never callable (`ticket_queue_idx` only
-- covers 'waiting'/'paused', and `TicketTransition.CALL` only leaves 'waiting'); this only adds the per-Service policy
-- that gates joining remotely, and the Site's own coordinates the "max distance" leg of it is measured against.

-- Per-Service remote-join policy (FR-MOB-011). No row means the virtual-queue flag is off, so remote join is refused.
CREATE TABLE IF NOT EXISTS service_remote_rule (
    service_id               uuid PRIMARY KEY REFERENCES service (id),
    virtual_queue_enabled    boolean     NOT NULL DEFAULT false,
    -- Maximum distance from the Site a visitor may join from, in metres; null means the distance check is off.
    max_distance_m           integer CHECK (max_distance_m > 0),
    max_remote_share_pct     integer     NOT NULL DEFAULT 40 CHECK (max_remote_share_pct BETWEEN 1 AND 100),
    join_window_minutes      integer     NOT NULL DEFAULT 30 CHECK (join_window_minutes >= 0),
    arrival_deadline_minutes integer     NOT NULL DEFAULT 15 CHECK (arrival_deadline_minutes >= 1),
    updated_at                timestamptz NOT NULL DEFAULT now()
);

-- A Site's own coordinates, set once by an admin, for the max-distance leg of FR-MOB-011; a Site with no row here
-- cannot turn on a distance limit (`IssuanceGate` refuses `site_location_unset` first).
CREATE TABLE IF NOT EXISTS site_location (
    site_id    uuid PRIMARY KEY REFERENCES site (id),
    latitude   double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude  double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The queue read path now also ranks a Service's remote tickets (FR-MOB-012, FR-MOB-013): they accrue a place and an
-- estimate exactly as a waiting ticket does (`QueueReads`'s own "queued" definition widens to match). This index only
-- backs that read; a ticket is still only ever *called* from 'waiting' (`TicketTransition.CALL`), so a remote ticket
-- being rankable here does not make it callable — it still cannot be drawn until ticket 43 checks it in.
DROP INDEX IF EXISTS ticket_queue_idx;
CREATE INDEX IF NOT EXISTS ticket_queue_idx ON ticket (service_id, state, queued_at, id) WHERE state IN ('waiting', 'paused', 'remote');
