-- Visitor directory and walk-in registration (SRS §8.3, §22.2, FR-ISS-020, FR-ISS-021, FR-INT-010, FR-INT-012).
-- phone and email extend the visitor record V16 began. A walk-in registered with no prior external_code is given one
-- (its "visitor pass reference", FR-ISS-021) so a returning walk-in is a known visitor on their next visit; the same
-- column is what a future remote directory adapter would upsert by (FR-INT-011).
ALTER TABLE visitor ADD COLUMN IF NOT EXISTS phone text;
ALTER TABLE visitor ADD COLUMN IF NOT EXISTS email text;

CREATE UNIQUE INDEX IF NOT EXISTS visitor_external_code_uq ON visitor (external_code) WHERE external_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS visitor_phone_idx ON visitor (phone) WHERE phone IS NOT NULL;
