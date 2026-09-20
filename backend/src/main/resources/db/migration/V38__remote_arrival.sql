-- Remote arrival, check-in and forfeit (ticket 43, SRS §13.3, §19.1, FR-MOB-020..024, FR-MOB-031, ADR-0004).
-- `ticket.state` already allows 'remote' and 'forfeited' (V6, forward declared); this only adds what tracking the
-- arrival deadline and a visitor's own delay needs.

-- When a remote ticket first reaches the front of its queue, the arrival-deadline clock starts (FR-MOB-022): cleared
-- once it is checked in, forfeited, or falls back off the front before the deadline (a higher-scoring ticket
-- overtaking it). `approaching_turn_notified_at` guards the FR-MOB-020 threshold notification to once per ticket.
-- `delay_used` guards the visitor's own "not ready yet" to once per ticket (FR-MOB-031).
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS remote_hold_started_at timestamptz;
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS approaching_turn_notified_at timestamptz;
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS delay_used boolean NOT NULL DEFAULT false;

-- Geofence radius is configurable per Site (FR-MOB-024); null means the geofence leg is off and only a QR scan or
-- reception can check a remote ticket in at that Site. Deliberately not yet wired into the admin config API/UI
-- (`IssuanceRulesController`'s `site-location` endpoints, ticket 42) — that is a later ticket's job, the same way
-- ticket 39 deferred its own iOS install-hint wiring to ticket 42; a test sets it directly, as `RemoteJoinQueueIT`
-- already does for `service_remote_rule`.
ALTER TABLE site_location ADD COLUMN IF NOT EXISTS geofence_radius_m integer CHECK (geofence_radius_m > 0);

-- The forfeit policy once the arrival deadline elapses (FR-MOB-022): move the ticket back (it stays remote, gets
-- another chance) or cancel it outright (closes as `forfeited`). Whether a Service allows its visitors one delay at
-- all (FR-MOB-031's "if the service allows it"). Both per Service, alongside the rest of `service_remote_rule`'s
-- remote-join policy (ticket 42); like the geofence radius above, not yet wired into an admin endpoint.
ALTER TABLE service_remote_rule ADD COLUMN IF NOT EXISTS forfeit_policy text NOT NULL DEFAULT 'move_back' CHECK (forfeit_policy IN ('move_back', 'cancel'));
ALTER TABLE service_remote_rule ADD COLUMN IF NOT EXISTS delay_allowed boolean NOT NULL DEFAULT false;
