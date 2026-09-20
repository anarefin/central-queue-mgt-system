-- Visitor email + OTP login and self-service (ticket 41, FR-MOB-001, FR-MOB-002, §20.2, API-090). A registered
-- visitor's own persisted auth state is kept apart from staff's `refresh_tokens` (which has a hard FK to `users`,
-- V2) and from device fleet's `device_refresh_tokens` (ticket 24): each principal kind owns its own credential
-- table, the same shape those two already are.
ALTER TABLE visitor ADD COLUMN IF NOT EXISTS email_verified_at timestamptz;

-- An email identifies at most one registered visitor, so OTP login can resolve it to exactly one account; a walk-in
-- or CSV-imported visitor with no email is unaffected (the constraint is partial, `WHERE email IS NOT NULL`, same
-- shape V18's own `external_code` index already is).
CREATE UNIQUE INDEX IF NOT EXISTS visitor_email_lower_uq ON visitor (lower(email)) WHERE email IS NOT NULL;

-- One-time codes: only a SHA-256 hash of the code is ever stored (API-018 — never the raw code at rest, and never
-- logged either). Single-use (`consumed_at`), short-lived (`expires_at`), and `attempts` counts wrong guesses so a
-- code can be locked out well before it would otherwise expire.
CREATE TABLE IF NOT EXISTS visitor_otp (
    id           uuid PRIMARY KEY,
    email        text        NOT NULL,
    code_hash    text        NOT NULL,
    attempts     integer     NOT NULL DEFAULT 0,
    requested_at timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz
);
CREATE INDEX IF NOT EXISTS visitor_otp_email_requested_idx ON visitor_otp (lower(email), requested_at DESC);

-- The only persisted authentication state for a registered visitor (API-014's pattern): opaque, hashed, single-use
-- with rotation on every refresh, and revocable as a whole family, exactly like staff's own `refresh_tokens`.
CREATE TABLE IF NOT EXISTS visitor_refresh_tokens (
    id         uuid PRIMARY KEY,
    family_id  uuid        NOT NULL,
    visitor_id uuid        NOT NULL REFERENCES visitor (id),
    token_hash text        NOT NULL,
    issued_at  timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS visitor_refresh_tokens_hash_uq ON visitor_refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS visitor_refresh_tokens_family_idx ON visitor_refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS visitor_refresh_tokens_visitor_idx ON visitor_refresh_tokens (visitor_id);

-- A registered visitor's own saved sites (FR-MOB-002); a site deactivated later still stays in the list (no cascade),
-- the same "deactivated, not deleted" shape every other entity in this schema already keeps.
CREATE TABLE IF NOT EXISTS visitor_saved_site (
    visitor_id uuid        NOT NULL REFERENCES visitor (id),
    site_id    uuid        NOT NULL REFERENCES site (id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (visitor_id, site_id)
);

-- A registered visitor may now book/reschedule/cancel their own appointment (FR-APT-020, §5.2 "S" for Visitor); its
-- `source` is `visitor`, never client-chosen, added to both places V28/V29 fixed the closed set (`appointment` and
-- `appointment_waitlist`, whose waitlist offer can also now originate from a visitor's own booking attempt).
ALTER TABLE appointment DROP CONSTRAINT IF EXISTS appointment_source_check;
ALTER TABLE appointment ADD CONSTRAINT appointment_source_check CHECK (source IN ('phone', 'walk_in', 'staff', 'visitor'));

ALTER TABLE appointment_waitlist DROP CONSTRAINT IF EXISTS appointment_waitlist_source_check;
ALTER TABLE appointment_waitlist ADD CONSTRAINT appointment_waitlist_source_check CHECK (source IN ('phone', 'walk_in', 'staff', 'visitor'));
