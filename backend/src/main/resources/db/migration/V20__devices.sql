-- Device pairing and fleet management (SRS §20.2, §20.4, §21.2, FR-OPS-011, FR-OPS-041, FR-OPS-042, FR-DSP-013,
-- NFR-SEC-005, API-017, ticket 24). Devices are never deleted, only deactivated ("revoked"), like every other
-- physical asset in this schema (FR-CFG-001 precedent). A kiosk is scoped to a site; a display is scoped to a zone
-- within a site (SRS §5.1 actors table).
CREATE TABLE IF NOT EXISTS device (
    id                uuid PRIMARY KEY,
    kind              text        NOT NULL CHECK (kind IN ('kiosk', 'display')),
    site_id           uuid        NOT NULL REFERENCES site (id),
    zone_id           uuid REFERENCES zone (id),
    label             text        NOT NULL,
    active            boolean     NOT NULL DEFAULT true,
    paired_at         timestamptz NOT NULL DEFAULT now(),
    last_heartbeat_at timestamptz,
    last_app_version  text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_zone_matches_kind
        CHECK ((kind = 'display' AND zone_id IS NOT NULL) OR (kind = 'kiosk' AND zone_id IS NULL))
);
CREATE INDEX IF NOT EXISTS device_site_idx ON device (site_id);
CREATE INDEX IF NOT EXISTS device_zone_idx ON device (zone_id);

-- A short-lived, single-use pairing code an administrator hands to a device (FR-OPS-011), exchanged once for a
-- device credential. Stored hashed, never the raw code, the same as a refresh token (API-014 precedent).
CREATE TABLE IF NOT EXISTS device_pairing_code (
    id         uuid PRIMARY KEY,
    code_hash  text        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('kiosk', 'display')),
    site_id    uuid        NOT NULL REFERENCES site (id),
    zone_id    uuid REFERENCES zone (id),
    label      text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS device_pairing_code_hash_uq ON device_pairing_code (code_hash);

-- The device's own persisted authentication state (NFR-SEC-005): the same rotate-on-use, single-use, hashed,
-- revocable-by-family shape as staff refresh_tokens (V2), but scoped to a device and returned to the client in the
-- response body rather than a cookie, since a kiosk/display shell is not a browser (API-017).
CREATE TABLE IF NOT EXISTS device_refresh_tokens (
    id         uuid PRIMARY KEY,
    family_id  uuid        NOT NULL,
    device_id  uuid        NOT NULL REFERENCES device (id),
    token_hash text        NOT NULL,
    issued_at  timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS device_refresh_tokens_hash_uq ON device_refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS device_refresh_tokens_family_idx ON device_refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS device_refresh_tokens_device_idx ON device_refresh_tokens (device_id);
