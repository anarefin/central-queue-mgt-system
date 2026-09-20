-- Reschedule, cancellation and waitlist (SRS §9.3, §19.2; FR-APT-020..023, ticket 34).
--
-- Reschedule and cancel act on the existing `appointment` row; no new states beyond what V28 already reserved for
-- this ticket (`rescheduled`, still held out of the active set's own CHECK, but already counted by
-- AppointmentBookingRepository#activeCountForSlot's IN-list per that migration's own comment). A reschedule moves
-- the row through `booked -> rescheduled -> booked` (§19.2) within the one transaction that serves the request, so
-- the old slot's capacity stays held (via `rescheduled`) until the new slot's capacity is confirmed; a cancel moves
-- `booked -> cancelled`, which AppointmentBookingRepository#activeCountForSlot already excludes, so capacity is free
-- the instant the row commits (FR-APT-022).

-- A Service's waitlist toggle (FR-APT-023); no row, same as the other two `appointment_service_settings` columns,
-- means the default (off).
ALTER TABLE appointment_service_settings ADD COLUMN IF NOT EXISTS waitlist_enabled boolean NOT NULL DEFAULT false;

-- A visitor waiting on a specific, currently full slot. Deliberately outside the appointment lifecycle (§19.2):
-- waiting here never consumes the slot's capacity; only once offered (state 'offered', `offered_appointment_id` set)
-- does a real `appointment` row exist for it, in `held_slot` for a configurable hold period, so the existing
-- AppointmentHoldExpiryScheduler sweep already governs an unclaimed offer with no changes of its own.
CREATE TABLE IF NOT EXISTS appointment_waitlist (
    id                      uuid        PRIMARY KEY,
    service_id              uuid        NOT NULL REFERENCES service (id),
    visitor_id              uuid        NOT NULL REFERENCES visitor (id),
    slot_date               date        NOT NULL,
    slot_start              time        NOT NULL,
    slot_end                time        NOT NULL,
    source                  text        NOT NULL CHECK (source IN ('phone', 'walk_in', 'staff')),
    purpose_note            text,
    language                text,
    state                   text        NOT NULL CHECK (state IN ('waiting', 'offered')),
    offered_appointment_id  uuid REFERENCES appointment (id),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CHECK (slot_start < slot_end)
);

-- The order an offer goes out in: earliest join first, only among entries still waiting on that exact slot.
CREATE INDEX IF NOT EXISTS appointment_waitlist_slot_idx ON appointment_waitlist (service_id, slot_date, slot_start, slot_end, created_at)
    WHERE state = 'waiting';
