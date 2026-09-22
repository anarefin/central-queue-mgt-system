-- Privacy controls (SRS §25.3-25.5, ticket 54): configurable visitor field sets, per-Site clinical sensitivity,
-- retention consent and visitor data export/deletion. Application-layer encryption (NFR-SEC-011) needs no schema
-- change: `visitor.name/phone/email` and `ticket.purpose_note` keep their existing `text` type and simply start
-- holding Base64 ciphertext instead of plaintext (com.qms.configuration.privacy.PiiCipher).

-- FR-SEC-021: a per-Site flag; when on, service and service-group names are replaced by a neutral label on public
-- displays, announcements and notifications (com.qms.device.DisplayStateReads, com.qms.notification.NotificationDispatcher).
ALTER TABLE site ADD COLUMN IF NOT EXISTS clinical_sensitivity boolean NOT NULL DEFAULT false;

-- FR-SEC-020/023: the two visitor-field surfaces this ticket adds Org Admin control over. The rest of §25.3's table
-- is already configurable through tickets already built on this same field set: `printed_token` through
-- configuration.branding.PrintTemplate/PrintField (ticket 27), `public_display`/`announcement` through Service's own
-- `announce_visitor_name` (ticket 29) plus DisplayStateReads showing only token/counter by default (ticket 28),
-- `agent_console` through session.ConsoleProperties (ticket 20), and `report_export` through the `visitor_pii:view`
-- gate ReportExportService/ReportScheduleService already carry (ticket 49/52). `capture` is FR-SEC-023's own
-- "captured at all" switch, replacing issuance.VisitorProperties' static config with one an Org Admin can change
-- at runtime; `kiosk_confirmation` is FR-SEC-020's "Name, category" row, not editable anywhere until now.
CREATE TABLE IF NOT EXISTS visitor_field_config (
    surface    text        NOT NULL,
    field      text        NOT NULL,
    visible    boolean     NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES users (id),
    PRIMARY KEY (surface, field)
);

INSERT INTO visitor_field_config (surface, field, visible) VALUES
    ('capture', 'email', true),
    ('capture', 'category', true),
    ('capture', 'purpose', true),
    ('kiosk_confirmation', 'name', true),
    ('kiosk_confirmation', 'category', true)
ON CONFLICT (surface, field) DO NOTHING;

-- FR-SEC-030: consent for retention, recorded per visitor with a timestamp and the version of the consent text they
-- saw — the same shape notification_consent (V33) already gives consent for notifications, kept as its own table
-- since the two are independent opt-ins a visitor may set separately.
CREATE TABLE IF NOT EXISTS visitor_retention_consent (
    visitor_id           uuid PRIMARY KEY REFERENCES visitor (id),
    granted              boolean     NOT NULL,
    consent_text_version text        NOT NULL,
    recorded_at          timestamptz NOT NULL
);

-- FR-SEC-031: a deletion request anonymises rather than removes, so operational statistics survive; this marks the
-- visitor row as done rather than needing a second table to know whether one already ran.
ALTER TABLE visitor ADD COLUMN IF NOT EXISTS anonymized_at timestamptz;
