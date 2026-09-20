-- Automatic no-show sweep and repeat-no-show policy (SRS §9.5, §19.2; FR-APT-040..042, ticket 36). No new columns or
-- states: `no_show` and every column the sweep or the policy reads (`state`, `slot_date`, `slot_start`, `visitor_id`,
-- `updated_at`) already exist (V28). Two indexes, the same convention `appointment_hold_expiry_idx` already uses for
-- AppointmentHoldExpiryScheduler's own sweep:

-- What AppointmentNoShowScheduler's sweep scans (FR-APT-040): every still-`booked` row, joined out to its Site's
-- time zone by AppointmentBookingRepository#sweepOverdueNoShows.
CREATE INDEX IF NOT EXISTS appointment_no_show_sweep_idx ON appointment (state, slot_date, slot_start) WHERE state = 'booked';

-- What the optional repeat-no-show policy counts (FR-APT-042): one visitor's own `no_show` rows within the rolling window.
CREATE INDEX IF NOT EXISTS appointment_no_show_visitor_idx ON appointment (visitor_id, updated_at) WHERE state = 'no_show';
