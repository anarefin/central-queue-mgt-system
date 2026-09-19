package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Body of {@code POST /devices/pairing-codes}: {@code kind} is {@code kiosk} or {@code display}. */
record CreatePairingCodeRequest(
        String kind, @JsonProperty("site_id") UUID siteId, @JsonProperty("zone_id") UUID zoneId, String label) {}
