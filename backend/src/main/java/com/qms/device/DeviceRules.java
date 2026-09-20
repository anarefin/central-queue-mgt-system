package com.qms.device;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Field rules for device pairing and fleet management. Every failure is {@code validation_failed} naming the field. */
final class DeviceRules {

    /** Unambiguous alphabet for a pairing code read off a screen or typed on a device: no 0/O, 1/I/L. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ---- display board defaults and rules (ticket 28, FR-DSP-001..005, FR-DSP-007, FR-SEC-020) ---------------------

    /** The shipped layout set of FR-DSP-003: the now-serving table (ticket 28) plus split media, a single big counter
     * and a lobby summary board (ticket 30). */
    static final String DEFAULT_LAYOUT = "now_serving_table";
    static final String LAYOUT_SPLIT_MEDIA = "split_media";
    static final String LAYOUT_SINGLE_COUNTER = "single_counter";
    static final String LAYOUT_SUMMARY_BOARD = "summary_board";
    static final List<String> LAYOUTS = List.of(DEFAULT_LAYOUT, LAYOUT_SPLIT_MEDIA, LAYOUT_SINGLE_COUNTER, LAYOUT_SUMMARY_BOARD);
    /** FR-SEC-020's public-display default: token number and counter only. */
    static final List<String> DEFAULT_COLUMNS = List.of("token", "counter");
    static final List<String> COLUMN_OPTIONS = List.of("token", "counter", "service", "staff");
    static final int DEFAULT_NEXT_N = 4;
    static final int MAX_NEXT_N = 20;
    static final int DEFAULT_HIGHLIGHT_SECONDS = 10;
    static final int MAX_HIGHLIGHT_SECONDS = 300;
    static final List<String> ASSIGNMENT_SCOPES = List.of("zone", "counters", "queues");

    /** `split_media`'s serving/notice panel split, as a percentage given to the serving side. */
    static final int DEFAULT_SPLIT_PERCENT = 60;
    static final int MIN_SPLIT_PERCENT = 10;
    static final int MAX_SPLIT_PERCENT = 90;
    static final int DEFAULT_LANGUAGE_CYCLE_SECONDS = 10;
    static final int MAX_LANGUAGE_CYCLE_SECONDS = 300;

    static String layout(String wire) {
        if (wire == null) return DEFAULT_LAYOUT;
        if (!LAYOUTS.contains(wire)) throw invalid("layout", "unknown_layout");
        return wire;
    }

    /**
     * The zone-proportion configuration FR-DSP-003 requires without a code change: `split_media` needs a
     * `split_percent` between {@link #MIN_SPLIT_PERCENT} and {@link #MAX_SPLIT_PERCENT} (default
     * {@link #DEFAULT_SPLIT_PERCENT}); `single_counter` needs a `counter_id`, checked against the display's own zone
     * by the caller (the same way `assignmentIds`'s Counter/Service ids are); every other layout ignores its config
     * and stores an empty object.
     */
    static Map<String, Object> layoutConfig(String layout, Map<String, Object> wire) {
        Map<String, Object> value = wire == null ? Map.of() : wire;
        if (LAYOUT_SPLIT_MEDIA.equals(layout)) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("split_percent", splitPercent(value.get("split_percent")));
            return config;
        }
        if (LAYOUT_SINGLE_COUNTER.equals(layout)) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("counter_id", singleCounterId(value.get("counter_id")).toString());
            return config;
        }
        return Map.of();
    }

    private static int splitPercent(Object wire) {
        if (wire == null) return DEFAULT_SPLIT_PERCENT;
        int value = ((Number) wire).intValue();
        if (value < MIN_SPLIT_PERCENT || value > MAX_SPLIT_PERCENT) throw invalid("layout_config.split_percent", "out_of_range");
        return value;
    }

    private static UUID singleCounterId(Object wire) {
        if (wire == null) throw invalid("layout_config.counter_id", "NotNull");
        try {
            return UUID.fromString(wire.toString());
        } catch (IllegalArgumentException e) {
            throw invalid("layout_config.counter_id", "invalid_uuid");
        }
    }

    /** FR-I18N-005: how often the display rotates through `language_cycle`; 0 renders the cycle side by side instead. */
    static int languageCycleSeconds(Integer wire) {
        int value = wire == null ? DEFAULT_LANGUAGE_CYCLE_SECONDS : wire;
        if (value < 0 || value > MAX_LANGUAGE_CYCLE_SECONDS) throw invalid("language_cycle_seconds", "out_of_range");
        return value;
    }

    /** A non-empty, ordered, duplicate-free list of codes the site has enabled; defaults to the site's own default language. */
    static List<String> languageCycle(List<String> wire, String siteDefaultLanguage, List<String> siteEnabledLanguages) {
        if (wire == null || wire.isEmpty()) return List.of(siteDefaultLanguage);
        LinkedHashSet<String> ordered = new LinkedHashSet<>(wire);
        if (ordered.size() != wire.size()) throw invalid("language_cycle", "duplicate_language");
        if (!siteEnabledLanguages.containsAll(ordered)) throw invalid("language_cycle", "unknown_language");
        return List.copyOf(ordered);
    }

    /** Always includes {@code token} and {@code counter} (FR-DSP-004's floor); defaults to FR-SEC-020's public default. */
    static List<String> columns(List<String> wire) {
        List<String> value = (wire == null || wire.isEmpty()) ? DEFAULT_COLUMNS : wire;
        LinkedHashSet<String> ordered = new LinkedHashSet<>(value);
        if (ordered.size() != value.size()) throw invalid("columns", "duplicate_column");
        if (!COLUMN_OPTIONS.containsAll(ordered)) throw invalid("columns", "unknown_column");
        if (!ordered.contains("token") || !ordered.contains("counter")) throw invalid("columns", "must_include_token_and_counter");
        return List.copyOf(ordered);
    }

    static int positiveInt(String field, Integer wire, int fallback, int max) {
        int value = wire == null ? fallback : wire;
        if (value <= 0 || value > max) throw invalid(field, "out_of_range");
        return value;
    }

    static String assignmentScope(String wire) {
        if (wire == null) return "zone";
        if (!ASSIGNMENT_SCOPES.contains(wire)) throw invalid("assignment.scope", "unknown_scope");
        return wire;
    }

    static List<UUID> assignmentIds(String scope, List<UUID> wire) {
        List<UUID> value = wire == null ? List.of() : wire;
        if ("zone".equals(scope)) {
            if (!value.isEmpty()) throw invalid("assignment.ids", "must_be_empty_for_zone");
            return List.of();
        }
        if (value.isEmpty()) throw invalid("assignment.ids", "NotEmpty");
        if (new LinkedHashSet<>(value).size() != value.size()) throw invalid("assignment.ids", "duplicate_id");
        return List.copyOf(value);
    }

    private DeviceRules() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static String required(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw invalid(field, "NotBlank");
        if (trimmed.length() > max) throw invalid(field, "Size");
        return trimmed;
    }

    /** {@code kind} is exactly {@code kiosk} or {@code display} (SRS §5.1); nothing else is a device role. */
    static Role kind(String wire) {
        if ("kiosk".equals(wire)) return Role.KIOSK;
        if ("display".equals(wire)) return Role.DISPLAY;
        throw invalid("kind", "unknown_kind");
    }

    /** A kiosk has no zone; a display must have one that belongs to the site the code is issued for. */
    static void zoneMatchesKind(Role kind, java.util.UUID zoneId) {
        if (kind == Role.DISPLAY && zoneId == null) throw invalid("zone_id", "NotNull");
        if (kind == Role.KIOSK && zoneId != null) throw invalid("zone_id", "must_be_null_for_kiosk");
    }

    /** {@code reload} tells a device to reload itself; {@code config_changed} tells it its configuration changed (FR-OPS-042). */
    static String commandEventType(String command) {
        if ("reload".equals(command)) return "device.command";
        if ("config_changed".equals(command)) return "config.changed";
        throw invalid("command", "unknown_command");
    }

    static String randomPairingCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}
