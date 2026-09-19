package com.qms.configuration.breaks;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field rules for break types. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class BreakTypeRules {

    static final int MAX_MINUTES = 1440;
    private static final int MAX_NAME_LENGTH = 100;

    private BreakTypeRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    /**
     * Per-language names: every language must be installed and the system default language must have a text, because a
     * missing translation falls back to it (FR-I18N-011). Blank texts are dropped and the result follows the order of the
     * installed languages.
     */
    static Map<String, String> names(Map<String, String> given, String defaultLanguage, List<String> installed) {
        if (given == null || given.isEmpty()) throw invalid("name_i18n", "NotBlank");
        if (!installed.containsAll(given.keySet())) throw invalid("name_i18n", "unknown_language");
        Map<String, String> kept = new LinkedHashMap<>();
        for (String language : installed) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > MAX_NAME_LENGTH) throw invalid("name_i18n", "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        if (!kept.containsKey(defaultLanguage)) throw invalid("name_i18n", "default_language_required");
        return kept;
    }

    /** The longest the break may run in minutes, or null for no limit (FR-AGT-020). */
    static Integer maxMinutes(Integer value) {
        if (value == null) return null;
        if (value < 1 || value > MAX_MINUTES) throw invalid("max_minutes", "Range");
        return value;
    }
}
