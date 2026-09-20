package com.qms.notification;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code {{variable}}} substitution for notification templates (FR-NTF-020, FR-NTF-021). */
final class NotificationRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");

    private NotificationRenderer() {}

    /** The variable names {@code text} references, for save-time validation against a trigger's fixed set (FR-NTF-021). */
    static Set<String> variablesIn(String text) {
        if (text == null) return Set.of();
        Matcher matcher = VARIABLE.matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    /** Substitutes every {@code {{variable}}} with its value, or the empty string when the value is absent. */
    static String render(String text, Map<String, String> values) {
        if (text == null) return null;
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
