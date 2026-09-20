package com.qms.configuration.branding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The printed token layout (FR-CFG-031): which of {@link PrintField}'s fixed fields show, in the order given, and
 * the free-text notice line's content (shown only when {@code notice_line} is also in {@code fields} and this text
 * is not blank). Single-tenant: there is exactly one of these, previewable and test-printable without issuing a
 * real Ticket (FR-CFG-032).
 */
public record PrintTemplate(
        List<String> fields,
        @JsonProperty("notice_line") String noticeLine,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {

    /** The FR-SEC-020 "printed token" row's default visible set: token, floor, service group, code, name, category, time. */
    static final List<String> DEFAULT_FIELDS =
            List.of("token_number", "floor", "service_group", "visitor_code", "visitor_name", "visitor_category", "issue_time");

    static final PrintTemplate DEFAULT = new PrintTemplate(DEFAULT_FIELDS, null, null, null);
}
