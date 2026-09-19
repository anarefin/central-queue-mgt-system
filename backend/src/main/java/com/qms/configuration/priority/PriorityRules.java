package com.qms.configuration.priority;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field rules for Priority classes. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class PriorityRules {

    static final int MAX_MINUTES = 1440;

    private PriorityRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    /**
     * Per-language names: every language must be installed and the system default language must have a text, because a
     * missing translation falls back to it (FR-I18N-011). Blank texts are dropped and the result follows the order of
     * the installed languages.
     */
    static Map<String, String> names(Map<String, String> given, String defaultLanguage, List<String> installed) {
        if (given == null || given.isEmpty()) throw invalid("name_i18n", "NotBlank");
        if (!installed.containsAll(given.keySet())) throw invalid("name_i18n", "unknown_language");
        Map<String, String> kept = new LinkedHashMap<>();
        for (String language : installed) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > 100) throw invalid("name_i18n", "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        if (!kept.containsKey(defaultLanguage)) throw invalid("name_i18n", "default_language_required");
        return kept;
    }

    static int headstart(Integer value) {
        if (value == null) return 0;
        if (value < 0 || value > MAX_MINUTES) throw invalid("headstart_minutes", "Range");
        return value;
    }

    static Integer maxWait(Integer value) {
        if (value == null) return null;
        if (value < 1 || value > MAX_MINUTES) throw invalid("max_wait_minutes", "Range");
        return value;
    }

    /** A short prefix printed at the start of a token number: letters and digits only; blank means none. */
    static String prefix(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 8) throw invalid("token_prefix_override", "Size");
        if (!trimmed.matches("[A-Za-z0-9]+")) throw invalid("token_prefix_override", "Pattern");
        return trimmed;
    }
}
