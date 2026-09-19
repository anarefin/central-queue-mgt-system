package com.qms.device;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/** Field rules for device pairing and fleet management. Every failure is {@code validation_failed} naming the field. */
final class DeviceRules {

    /** Unambiguous alphabet for a pairing code read off a screen or typed on a device: no 0/O, 1/I/L. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

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
