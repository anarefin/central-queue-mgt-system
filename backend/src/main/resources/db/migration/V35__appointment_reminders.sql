-- Appointment reminders (ticket 40, FR-APT-050): one row per (appointment, offset) reminder actually sent, so the
-- background sweep never re-sends the same reminder even with several nodes racing the same tick (ADR-0010) --
-- whichever node's insert wins the unique constraint below fires the notification; the rest find nothing to send.
-- The email channel itself (ticket 40, FR-INT-040) needs no table of its own: it sends to the visitor's existing
-- `email` column (V18) and is otherwise stateless, the same way the in-app and staff-alert channels (ticket 38) are.
CREATE TABLE IF NOT EXISTS appointment_reminder_sent (
    appointment_id uuid        NOT NULL REFERENCES appointment (id),
    offset_minutes integer     NOT NULL,
    sent_at        timestamptz NOT NULL,
    PRIMARY KEY (appointment_id, offset_minutes)
);
