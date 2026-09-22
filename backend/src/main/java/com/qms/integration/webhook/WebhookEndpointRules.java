package com.qms.integration.webhook;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field rules for a webhook endpoint. Every failure is {@code validation_failed} naming the field (SRS §20.3),
 * the same convention every other admin CRUD in this codebase (e.g. {@code configuration.breaks.BreakTypeRules})
 * follows. */
final class WebhookEndpointRules {

    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_URL_LENGTH = 2000;

    private WebhookEndpointRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String description(String given) {
        if (given == null || given.isBlank()) throw invalid("description", "NotBlank");
        String trimmed = given.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) throw invalid("description", "Size");
        return trimmed;
    }

    /** {@link WebhookEndpointSecurity#requireSafe} raises its own {@code UnsafeEndpointException} for any address
     * that would be an SSRF vector; that is caught here and re-raised as this closed set's own {@code validation_failed}. */
    static String url(String given, boolean allowInsecureForTests) {
        if (given == null || given.isBlank()) throw invalid("url", "NotBlank");
        String trimmed = given.trim();
        if (trimmed.length() > MAX_URL_LENGTH) throw invalid("url", "Size");
        try {
            WebhookEndpointSecurity.requireSafe(trimmed, allowInsecureForTests);
        } catch (WebhookEndpointSecurity.UnsafeEndpointException unsafe) {
            throw invalid("url", "unsafe_endpoint:" + unsafe.getMessage());
        }
        return trimmed;
    }

    /** FR-INT-020: every subscribed type must be one of §21.4's closed set ({@link WebhookEventType}), at least one,
     * no duplicates, kept in the order given. */
    static List<String> eventTypes(List<String> given) {
        if (given == null || given.isEmpty()) throw invalid("event_types", "NotEmpty");
        Set<String> kept = new LinkedHashSet<>();
        for (String type : given) {
            if (!WebhookEventType.isKnown(type)) throw invalid("event_types", "unknown_event_type:" + type);
            kept.add(type);
        }
        return List.copyOf(kept);
    }
}
