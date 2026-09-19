-- Where a new ticket's Priority class comes from when staff do not choose one, and staff cancel (SRS §10.2, FR-QUE-011,
-- FR-QUE-012, FR-CFG-041). Precedence at issue: manual assignment, appointment class, visitor category mapping, channel
-- default, service default. Only the last two are configured here; the appointment and visitor category sources arrive with
-- the tickets that build appointments and visitor categories.
--
-- A default is read once, when the ticket is issued, and the class it resolved to is what the ticket keeps: changing a
-- default later never touches a ticket already issued (FR-CFG-041).

-- The class a Service gives its tickets when nothing more specific applies; null means the default (normal) class.
ALTER TABLE service ADD COLUMN IF NOT EXISTS default_priority_class_id uuid REFERENCES priority_class (id);

-- The class the tickets of one issuing channel get when nothing more specific applies. Organisation-wide, like the classes.
CREATE TABLE IF NOT EXISTS channel_priority_default (
    channel           text        PRIMARY KEY CHECK (channel IN ('kiosk', 'reception', 'mobile', 'appointment_checkin')),
    priority_class_id uuid        NOT NULL REFERENCES priority_class (id),
    updated_at        timestamptz NOT NULL
);
