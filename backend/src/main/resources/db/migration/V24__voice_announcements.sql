-- Voice announcements (ticket 29, SRS §12.3-12.4): a Zone's audio settings, a Service's visitor-name-in-audio flag,
-- and the per-language spoken form of every token prefix (FR-DSP-020..031, FR-I18N-020, FR-I18N-040, FR-I18N-041).
-- Every column here is additive and defaulted, so it never rewrites a row a prior ticket already wrote.

ALTER TABLE zone ADD COLUMN IF NOT EXISTS chime text NOT NULL DEFAULT 'chime_standard';
ALTER TABLE zone ADD COLUMN IF NOT EXISTS chime_volume smallint NOT NULL DEFAULT 80 CHECK (chime_volume BETWEEN 0 AND 100);
-- Both null means no quiet period configured (audio always plays); both must be set together (ZoneAudioRules).
ALTER TABLE zone ADD COLUMN IF NOT EXISTS quiet_start time;
ALTER TABLE zone ADD COLUMN IF NOT EXISTS quiet_end time;
-- Ordered list of language codes announcements play in, e.g. ["bn","en"] (FR-DSP-023).
ALTER TABLE zone ADD COLUMN IF NOT EXISTS announcement_languages jsonb NOT NULL DEFAULT '["en"]'::jsonb;
-- FR-DSP-026: the most announcements ever pending for this zone at once; the queue keeps only the most recent per Counter.
ALTER TABLE zone ADD COLUMN IF NOT EXISTS max_announce_queue_depth smallint NOT NULL DEFAULT 5 CHECK (max_announce_queue_depth BETWEEN 1 AND 20);

-- FR-DSP-022: announcing the visitor's name is a per-Service flag, default off (inappropriate in medical settings).
ALTER TABLE service ADD COLUMN IF NOT EXISTS announce_visitor_name boolean NOT NULL DEFAULT false;

-- FR-DSP-030, FR-I18N-040, FR-I18N-041: the spoken form of a token prefix in one language, e.g. prefix "QC" spoken as
-- letters in English and as a configured Bangla phrase. A prefix cannot be put on a Service until every language
-- enabled at that Service's site has a row here (CatalogueService), so a new prefix is never silently mispronounced.
CREATE TABLE IF NOT EXISTS token_prefix_spoken_form (
    prefix      text        NOT NULL,
    language    text        NOT NULL,
    spoken_text text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (prefix, language)
);
