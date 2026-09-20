-- Visitor ticket page (ticket 37, FR-MOB-032): an optional static wayfinding image per Zone, shown on the visitor's
-- mobile ticket page alongside its floor and Zone. Null means "no image", the same convention as org_branding's
-- logo_url (V22): an absolute URL or a data: URI both render fine as an <img src>, so the column does not care which.
ALTER TABLE zone ADD COLUMN IF NOT EXISTS wayfinding_image_url text;
