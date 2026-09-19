package com.qms.configuration.site;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Field rules for the site hierarchy. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class SiteRules {

    private SiteRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    /** A required text field: trimmed, not blank, within {@code max}. */
    static String required(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw invalid(field, "NotBlank");
        if (trimmed.length() > max) throw invalid(field, "Size");
        return trimmed;
    }

    /** An optional text field: blank means "none" and is stored as null. */
    static String optional(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) throw invalid(field, "Size");
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A short identifier for a site: letters, digits, underscore and hyphen. */
    static String code(String value) {
        String trimmed = required("code", value, 32);
        if (!trimmed.matches("[A-Za-z0-9_-]+")) throw invalid("code", "Pattern");
        return trimmed;
    }

    static int displayOrder(Integer value) {
        if (value == null) return 0;
        if (value < 0 || value > 100_000) throw invalid("display_order", "Range");
        return value;
    }

    /** An IANA zone id such as {@code Asia/Dhaka}. */
    static String timezone(String value) {
        String trimmed = required("timezone", value, 64);
        if (!ZoneId.getAvailableZoneIds().contains(trimmed)) throw invalid("timezone", "unknown_timezone");
        return trimmed;
    }

    /**
     * The ordered enabled languages of a site: each one installed, no repeats, and the default among them. With none
     * given the site enables just its default language (FR-I18N-002).
     */
    static List<String> languages(String defaultLanguage, List<String> enabled, Collection<String> installed) {
        if (!installed.contains(defaultLanguage)) throw invalid("default_language", "unknown_language");
        if (enabled == null || enabled.isEmpty()) return List.of(defaultLanguage);
        if (enabled.stream().anyMatch(java.util.Objects::isNull)) throw invalid("enabled_languages", "unknown_language");
        LinkedHashSet<String> ordered = new LinkedHashSet<>(enabled);
        if (ordered.size() != enabled.size()) throw invalid("enabled_languages", "duplicate_language");
        if (!installed.containsAll(ordered)) throw invalid("enabled_languages", "unknown_language");
        if (!ordered.contains(defaultLanguage)) throw invalid("enabled_languages", "must_include_default_language");
        return List.copyOf(ordered);
    }
}
