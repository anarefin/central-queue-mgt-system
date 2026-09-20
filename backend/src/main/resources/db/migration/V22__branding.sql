-- Branding and printed-token template (SRS §7.5, FR-CFG-030..032, ticket 27). Single-tenant: one row each, a fixed
-- boolean key so every read/write targets the same singleton without a lookup (same pattern as V19's
-- visitor_import_mapping). Both start with a row so a fresh install has sane defaults before any admin ever saves.

-- Organisation-wide branding applied to kiosk, display, printed token, mobile app and reports (FR-CFG-030).
CREATE TABLE IF NOT EXISTS org_branding (
    id            boolean PRIMARY KEY DEFAULT true,
    org_name      text        NOT NULL,
    primary_color text        NOT NULL,
    logo_url      text,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid REFERENCES users (id),
    CONSTRAINT org_branding_singleton CHECK (id)
);
INSERT INTO org_branding (id, org_name, primary_color, logo_url, updated_at)
VALUES (true, 'QMS', '#0b5fff', NULL, now())
ON CONFLICT (id) DO NOTHING;

-- The printed token layout (FR-CFG-031): which of the fixed fields show, and the free-text notice line's content.
-- `fields` defaults to the FR-SEC-020 "printed token" default visible set: token, floor, service group, code, name,
-- category, time.
CREATE TABLE IF NOT EXISTS print_template (
    id          boolean PRIMARY KEY DEFAULT true,
    fields      jsonb       NOT NULL,
    notice_line text,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid REFERENCES users (id),
    CONSTRAINT print_template_singleton CHECK (id)
);
INSERT INTO print_template (id, fields, notice_line, updated_at)
VALUES (
    true,
    '["token_number","floor","service_group","visitor_code","visitor_name","visitor_category","issue_time"]'::jsonb,
    NULL,
    now()
)
ON CONFLICT (id) DO NOTHING;
