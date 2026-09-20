package com.qms.configuration.notice;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The whole editable content of a notice, for create and for replace (ticket 30, FR-DSP-006). */
public record NoticeRequest(
        @JsonProperty("zone_id") UUID zoneId,
        String type,
        @JsonProperty("content_i18n") Map<String, String> contentI18n,
        @JsonProperty("starts_at") Instant startsAt,
        @JsonProperty("ends_at") Instant endsAt,
        @JsonProperty("sort_order") Integer sortOrder) {}
