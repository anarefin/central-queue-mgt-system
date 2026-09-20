-- Appointment check-in converts to a Ticket (SRS §8.4, §9.4, §19.2; FR-ISS-030..033, FR-APT-030..032, ticket 35).
--
-- The Priority class chosen for the appointment (FR-QUE-011's "appointment" source, ahead of visitor category and
-- every default): null means the default class, the same convention `ticket.priority_class_id` already uses.
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS priority_class_id uuid REFERENCES priority_class (id);

-- When check-in actually happened, the difference between slot time and that instant in seconds (positive late,
-- negative early, FR-APT-030), and the Ticket it created (§19.2 `checked_in -> converted`).
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS checked_in_at timestamptz;
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS checkin_variance_seconds integer;
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS ticket_id uuid REFERENCES ticket (id);

-- The bonus this Ticket's score carries because it is a checked-in appointment (FR-QUE-020, FR-APT-032). Read once,
-- at issue, and stored here the same way `priority_class_id` already is (FR-CFG-041): a later change to the
-- configured bonus never moves a Ticket already waiting. Every other Ticket keeps the default of 0.
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS appointment_bonus_minutes integer NOT NULL DEFAULT 0;
