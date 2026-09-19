package com.qms.configuration.catalogue;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Field rules for the service catalogue. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class CatalogueRules {

    /** Issuing channels a service can be enabled for (SRS §8): kiosk, reception, mobile app and appointment check-in. */
    static final List<String> CHANNELS = List.of("kiosk", "reception", "mobile", "appointment_checkin");
    static final List<String> VISITOR_IDENTIFIER = List.of("not_required", "optional", "mandatory");
    static final List<String> BOOKING_MODE = List.of("appointment_only", "walk_in_only", "both");

    private CatalogueRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String required(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw invalid(field, "NotBlank");
        if (trimmed.length() > max) throw invalid(field, "Size");
        return trimmed;
    }

    static String optional(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) throw invalid(field, "Size");
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A short prefix printed at the start of a token number: letters and digits only. */
    static String tokenPrefix(String value) {
        String trimmed = required("token_prefix", value, 8);
        if (!trimmed.matches("[A-Za-z0-9]+")) throw invalid("token_prefix", "Pattern");
        return trimmed;
    }

    /** A machine-readable outcome code, stable for reports: lower-case letters, digits and underscores. */
    static String outcomeCode(String value) {
        String trimmed = required("code", value, 50);
        if (!trimmed.matches("[a-z0-9_]+")) throw invalid("code", "Pattern");
        return trimmed;
    }

    static int displayOrder(Integer value) {
        if (value == null) return 0;
        if (value < 0 || value > 100_000) throw invalid("display_order", "Range");
        return value;
    }

    static int minutes(String field, Integer value) {
        if (value == null) throw invalid(field, "NotNull");
        if (value < 1 || value > 1440) throw invalid(field, "Range");
        return value;
    }

    static int preferenceWeight(Integer value) {
        if (value == null) return 1;
        if (value < 1 || value > 99) throw invalid("preference_weight", "Range");
        return value;
    }

    /** Enabled channels, without repeats, in the order given; none given enables every channel. */
    static List<String> channels(List<String> value) {
        if (value == null) return CHANNELS;
        if (value.stream().anyMatch(c -> c == null || !CHANNELS.contains(c))) throw invalid("channels", "unknown_channel");
        LinkedHashSet<String> ordered = new LinkedHashSet<>(value);
        if (ordered.size() != value.size()) throw invalid("channels", "duplicate_channel");
        return List.copyOf(ordered);
    }

    static String choice(String field, String value, List<String> allowed) {
        if (value == null || !allowed.contains(value)) throw invalid(field, "Pattern");
        return value;
    }

    /**
     * Per-language names for a site: every language must be enabled at the site, and the site's default language must
     * have a text, because a missing translation falls back to it (FR-I18N-011). Other enabled languages may be blank;
     * that is a warning for the admin, never an error (FR-I18N-010). Blank texts are dropped, and the result follows
     * the site's language order.
     */
    static Map<String, String> names(String field, Map<String, String> given, String defaultLanguage, List<String> enabled) {
        if (given == null || given.isEmpty()) throw invalid(field, "NotBlank");
        if (!enabled.containsAll(given.keySet())) throw invalid(field, "unknown_language");
        Map<String, String> kept = new LinkedHashMap<>();
        for (String language : enabled) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > 200) throw invalid(field, "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        if (!kept.containsKey(defaultLanguage)) throw invalid(field, "default_language_required");
        return kept;
    }

    /** The enabled languages that have no text yet, in the site's order. */
    static List<String> missing(Map<String, String> names, List<String> enabled) {
        return enabled.stream().filter(language -> !names.containsKey(language)).toList();
    }
}
