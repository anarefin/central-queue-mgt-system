package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code GET /visitors/{id}/export} (FR-SEC-031): everything the local record holds about one visitor. */
public record VisitorExportResponse(
        UUID id,
        @JsonProperty("external_code") String externalCode,
        String name,
        String category,
        String phone,
        String email,
        @JsonProperty("preferred_language") String preferredLanguage,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("anonymized_at") Instant anonymizedAt,
        List<TicketEntry> tickets,
        @JsonProperty("notification_consent") ConsentEntry notificationConsent,
        @JsonProperty("retention_consent") ConsentEntry retentionConsent) {

    public record TicketEntry(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            @JsonProperty("service_names") Map<String, String> serviceNames,
            @JsonProperty("site_id") UUID siteId,
            @JsonProperty("purpose_note") String purposeNote,
            @JsonProperty("issued_at") Instant issuedAt,
            @JsonProperty("closed_at") Instant closedAt) {}

    public record ConsentEntry(boolean granted, @JsonProperty("consent_text_version") String consentTextVersion, @JsonProperty("recorded_at") Instant recordedAt) {}
}
