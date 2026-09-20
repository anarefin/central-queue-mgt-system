package com.qms.configuration.notice;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One scheduled notice-board item (ticket 30, FR-DSP-006, SRS §5.2 "Manage notice-board content"): an image, a video
 * or rich text, scoped to the Zone whose display(s) show it, shown only between {@code startsAt} and {@code endsAt}.
 * {@code contentI18n} maps a language code to the content for that language -- an absolute URL or a {@code data:}
 * URI for {@code image}/{@code video} (the same convention as {@code org_branding.logo_url}), or plain text for
 * {@code rich_text} -- so an image containing text can ship one asset per language (FR-I18N-032). Never deleted,
 * only deactivated, the same convention as every other configuration record in this schema.
 */
public record Notice(
        UUID id,
        @JsonProperty("zone_id") UUID zoneId,
        String type,
        @JsonProperty("content_i18n") Map<String, String> contentI18n,
        @JsonProperty("starts_at") Instant startsAt,
        @JsonProperty("ends_at") Instant endsAt,
        @JsonProperty("sort_order") int sortOrder,
        boolean active,
        @JsonProperty("created_by") UUID createdBy,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
