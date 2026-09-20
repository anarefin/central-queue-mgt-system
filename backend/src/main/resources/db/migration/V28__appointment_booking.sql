-- Staff appointment booking (SRS §9.2, §19.2; FR-APT-011..016). A booking is transactional against the remaining
-- capacity of one exact slot (service_id, slot_date, slot_start, slot_end): capacity is what ticket 32's search
-- already resolves from templates/exceptions, and "remaining" now subtracts every appointment counted against a
-- slot per §19.2 ("capacity is consumed in held_slot, booked, checked_in and converted, and released on cancelled,
-- no_show and hold expiry"); `rescheduled` also still holds the slot it is mid-move from (ticket 34).
--
-- A booking starts in held_slot with a hold_expires_at (FR-APT-012, default 5 minutes, qms.appointment.hold-minutes)
-- and, when the caller already supplied every detail — true of every phone/walk-in/staff booking this ticket makes,
-- since nothing is left for the visitor to complete later — is confirmed to booked in the very same transaction.
-- AppointmentHoldExpiryScheduler deletes any row a future caller (ticket 41's visitor self-service) leaves in
-- held_slot past its hold, which is what returns that capacity (state machine's `held_slot -> [*]: hold expired`).
CREATE TABLE IF NOT EXISTS appointment (
    id                 uuid        PRIMARY KEY,
    reference_code     text        NOT NULL,
    service_id         uuid        NOT NULL REFERENCES service (id),
    preferred_agent_id uuid REFERENCES users (id),
    visitor_id         uuid        NOT NULL REFERENCES visitor (id),
    slot_date          date        NOT NULL,
    slot_start         time        NOT NULL,
    slot_end           time        NOT NULL,
    state              text        NOT NULL CHECK (state IN ('held_slot', 'booked', 'rescheduled', 'checked_in', 'cancelled', 'no_show', 'converted')),
    source             text        NOT NULL CHECK (source IN ('phone', 'walk_in', 'staff')),
    purpose_note       text,
    language           text,
    hold_expires_at    timestamptz,
    booked_by          uuid REFERENCES users (id),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CHECK (slot_start < slot_end)
);

CREATE UNIQUE INDEX IF NOT EXISTS appointment_reference_code_uq ON appointment (reference_code);

-- The exact-slot count a booking checks and search subtracts (FR-APT-011, FR-APT-010).
CREATE INDEX IF NOT EXISTS appointment_slot_idx ON appointment (service_id, slot_date, slot_start, slot_end)
    WHERE state IN ('held_slot', 'booked', 'rescheduled', 'checked_in');

-- FR-APT-016's per-visitor active count.
CREATE INDEX IF NOT EXISTS appointment_visitor_active_idx ON appointment (visitor_id)
    WHERE state IN ('held_slot', 'booked', 'rescheduled', 'checked_in');

-- What AppointmentHoldExpiryScheduler sweeps.
CREATE INDEX IF NOT EXISTS appointment_hold_expiry_idx ON appointment (hold_expires_at) WHERE state = 'held_slot';
