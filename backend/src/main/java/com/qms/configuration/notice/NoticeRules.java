package com.qms.configuration.notice;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field rules for notice-board content (FR-DSP-006). Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class NoticeRules {

    static final String TYPE_IMAGE = "image";
    static final String TYPE_VIDEO = "video";
    static final String TYPE_RICH_TEXT = "rich_text";
    private static final List<String> TYPES = List.of(TYPE_IMAGE, TYPE_VIDEO, TYPE_RICH_TEXT);
    /** Generous enough for an absolute URL, a small `data:` URI, or a short block of plain text. */
    private static final int MAX_CONTENT_LENGTH = 20_000;
    private static final int MAX_SORT_ORDER = 1_000;

    private NoticeRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String type(String wire) {
        if (wire == null || !TYPES.contains(wire)) throw invalid("type", "unknown_type");
        return wire;
    }

    /**
     * Per-language content: every language must be installed and the site's default language must have content,
     * because a missing translation falls back to it (FR-I18N-011); one entry per language lets an image with text
     * in it ship a different asset per language (FR-I18N-032). Blank entries are dropped.
     */
    static Map<String, String> content(Map<String, String> given, String defaultLanguage, List<String> installed) {
        if (given == null || given.isEmpty()) throw invalid("content_i18n", "NotBlank");
        if (!installed.containsAll(given.keySet())) throw invalid("content_i18n", "unknown_language");
        Map<String, String> kept = new LinkedHashMap<>();
        for (String language : installed) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > MAX_CONTENT_LENGTH) throw invalid("content_i18n", "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        if (!kept.containsKey(defaultLanguage)) throw invalid("content_i18n", "default_language_required");
        return kept;
    }

    /** The playlist window: both dates required, and it must not be empty (FR-DSP-006's "per-item start and end dates"). */
    static void dates(Instant startsAt, Instant endsAt) {
        if (startsAt == null) throw invalid("starts_at", "NotNull");
        if (endsAt == null) throw invalid("ends_at", "NotNull");
        if (!endsAt.isAfter(startsAt)) throw invalid("ends_at", "must_be_after_starts_at");
    }

    static int sortOrder(Integer wire) {
        int value = wire == null ? 0 : wire;
        if (value < 0 || value > MAX_SORT_ORDER) throw invalid("sort_order", "out_of_range");
        return value;
    }
}
