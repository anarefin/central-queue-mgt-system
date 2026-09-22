package com.qms.configuration.versioning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a stored {@link ConfigVersion#payload()} back into the typed value a revert needs. The payload came from
 * JSONB through the JSON mapper as plain {@code Map}/{@code List}/{@code Number}/{@code String}/{@code Boolean}
 * values (no records), so a revert reads it back the same untyped way rather than assuming a particular numeric type.
 */
public final class ConfigVersionCodec {

    private ConfigVersionCodec() {}

    public static Integer asInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    public static boolean asBoolean(Object value) {
        return Boolean.TRUE.equals(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> asStringMap(Object value) {
        if (value == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<String, Object>) value).forEach((k, v) -> result.put(k, v == null ? null : v.toString()));
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asListOfMaps(Object value) {
        if (value == null) return List.of();
        return (List<Map<String, Object>>) (List<?>) value;
    }
}
