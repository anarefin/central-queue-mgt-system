package com.qms.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Scrubs secrets from audit before/after payloads so the log never becomes a second place credentials leak. */
final class AuditRedaction {

    static final String MASK = "[redacted]";
    private static final Set<String> EXACT = Set.of("token", "otp", "authorization", "cookie", "api_key", "secret", "passcode");

    private AuditRedaction() {}

    static Map<String, Object> scrub(Map<String, Object> input) {
        if (input == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((key, value) -> out.put(key, sensitive(key) ? MASK : scrubValue(value)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object scrubValue(Object value) {
        if (value instanceof Map<?, ?> map) return scrub((Map<String, Object>) map);
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(scrubValue(item)));
            return out;
        }
        return value;
    }

    private static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return EXACT.contains(k) || k.contains("password") || k.endsWith("_token") || k.endsWith("secret") || k.startsWith("secret");
    }
}
