-- Re-announce and Miss (SRS §10.6, §19.1, FR-QUE-050, FR-DSP-028, ADR-0005). "Recall" is retired: F3 replays a call and is
-- counted in `announce_count`; F6 declares the visitor absent and is counted in `miss_count`. Neither counts towards the
-- other's limit.
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS announce_count integer NOT NULL DEFAULT 0 CHECK (announce_count >= 0);
ALTER TABLE ticket ADD COLUMN IF NOT EXISTS miss_count integer NOT NULL DEFAULT 0 CHECK (miss_count >= 0);
