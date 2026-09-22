-- Ticket 56 correction (FR-OPS-010): "announced" used to be read straight off the same ticket_event row that
-- already makes "called" true (any to_state = 'called' event), so the two could never diverge -- calling a ticket
-- always made both true at once, with no independent confirmation a chime/voice announcement actually played.
-- "Printed" already has its own explicit confirmation (printed_at/printed_by) because printing is a client render
-- with no server-side signal of its own; "announced" needs the identical treatment, since an audio/chime playing on
-- a Zone's speaker is exactly as invisible to the server as a physical printout coming out of a printer.
ALTER TABLE setup_test_ticket
    ADD COLUMN IF NOT EXISTS announced_at timestamptz,
    ADD COLUMN IF NOT EXISTS announced_by uuid;
