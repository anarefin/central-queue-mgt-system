package com.qms.configuration.site;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Field rules for the site hierarchy. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class SiteRules {

    /** The chimes this build ships (ticket 29, FR-DSP-025); an admin picks one, never a free-text sound file. */
    static final List<String> CHIMES = List.of("chime_standard", "chime_soft", "chime_alert");
    static final String DEFAULT_CHIME = "chime_standard";
    static final int DEFAULT_CHIME_VOLUME = 80;
    static final List<String> DEFAULT_ANNOUNCEMENT_LANGUAGES = List.of("en");
    static final int DEFAULT_MAX_ANNOUNCE_QUEUE_DEPTH = 5;

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

    // ---- zone audio (ticket 29, FR-DSP-023, FR-DSP-025, FR-DSP-026, FR-DSP-027) ------------------------------------

    static String chime(String value) {
        if (value == null) return DEFAULT_CHIME;
        if (!CHIMES.contains(value)) throw invalid("chime", "unknown_chime");
        return value;
    }

    static int chimeVolume(Integer value) {
        if (value == null) return DEFAULT_CHIME_VOLUME;
        if (value < 0 || value > 100) throw invalid("chime_volume", "Range");
        return value;
    }

    /** {@code null} keeps the current value; {@code ""} clears it; anything else must parse as {@code HH:mm}. */
    static LocalTime quietTime(String field, String value, LocalTime current) {
        if (value == null) return current;
        if (value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalid(field, "Pattern");
        }
    }

    /** A quiet period needs both ends, or neither (FR-DSP-027); one without the other is refused rather than guessed. */
    static void quietPeriodComplete(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) throw invalid("quiet_start", "quiet_period_needs_both_ends");
    }

    /** A non-empty, ordered, duplicate-free list of installed language codes; defaults to English alone. */
    static List<String> announcementLanguages(List<String> value, Collection<String> installed) {
        if (value == null || value.isEmpty()) return DEFAULT_ANNOUNCEMENT_LANGUAGES;
        LinkedHashSet<String> ordered = new LinkedHashSet<>(value);
        if (ordered.size() != value.size()) throw invalid("announcement_languages", "duplicate_language");
        if (!installed.containsAll(ordered)) throw invalid("announcement_languages", "unknown_language");
        return List.copyOf(ordered);
    }

    static int maxAnnounceQueueDepth(Integer value) {
        if (value == null) return DEFAULT_MAX_ANNOUNCE_QUEUE_DEPTH;
        if (value < 1 || value > 20) throw invalid("max_announce_queue_depth", "Range");
        return value;
    }
}
