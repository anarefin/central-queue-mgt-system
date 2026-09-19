-- Identity: staff users, their role assignments, password history and refresh tokens (tickets 03 and 04).
-- Forward-only and idempotent (FR-OPS-020). Users are disabled, never deleted (FR-CFG-104).
CREATE TABLE IF NOT EXISTS users (
    id                  uuid PRIMARY KEY,
    username            text        NOT NULL,
    password_hash       text        NOT NULL,
    display_name        text,
    preferred_language  text,
    active              boolean     NOT NULL DEFAULT true,
    failed_attempts     integer     NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS users_username_lower_uq ON users (lower(username));

CREATE TABLE IF NOT EXISTS user_password_history (
    id            uuid PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES users (id),
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS user_password_history_user_idx ON user_password_history (user_id, created_at DESC);

-- A role assignment names the sites / service groups it covers; both empty means organisation-wide (SRS §5).
-- `source` lets a later external-group mapping (FR-INT-002) own its assignments without touching manual ones.
CREATE TABLE IF NOT EXISTS role_assignments (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    role       text        NOT NULL,
    site_ids   uuid[]      NOT NULL DEFAULT '{}',
    group_ids  uuid[]      NOT NULL DEFAULT '{}',
    source     text        NOT NULL DEFAULT 'manual',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS role_assignments_user_idx ON role_assignments (user_id);

-- The only persisted authentication state (API-014). Tokens are opaque; only a SHA-256 hash is stored.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         uuid PRIMARY KEY,
    family_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL REFERENCES users (id),
    token_hash text        NOT NULL,
    issued_at  timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS refresh_tokens_hash_uq ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx ON refresh_tokens (user_id);
