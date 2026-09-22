-- Service accounts for host-system integration (ticket 58, SRS §20.2, §22.4, FR-INT-030): a client id and a
-- bcrypt-hashed secret exchanged for a scoped JWT carrying the host_system role, restricted to the sites named
-- here. Unlike a token claim's own empty-set-means-unrestricted convention (FR-CFG-106), site_ids is never empty in
-- this table: ServiceAccountService refuses to create an organisation-wide credential (least privilege for a new
-- machine identity).
CREATE TABLE IF NOT EXISTS service_account (
    id          uuid PRIMARY KEY,
    client_id   text        NOT NULL UNIQUE,
    secret_hash text        NOT NULL,
    label       text        NOT NULL,
    site_ids    uuid[]      NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_by  uuid,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
