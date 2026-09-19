package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code GET /kiosk/visitors/identify} (FR-ISS-013, FR-ISS-014): once a visitor code resolves, the kiosk shows only
 * their name and category for confirmation before printing, and must not expose anything else stored about them —
 * unlike {@link VisitorLookupResponse} (Reception's own lookup), this carries no phone number, external code or flags.
 */
public record KioskVisitorIdentifyResponse(@JsonProperty("visitor_id") UUID visitorId, String name, String category) {}
