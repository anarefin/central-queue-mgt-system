package com.qms.platform;

import java.util.regex.Pattern;

/**
 * Masks credentials in log text (API-018): Authorization header values, JWT-shaped strings, the ticket secret, refresh
 * tokens, OTPs and passwords. It is a safety net; the code should not log these in the first place.
 */
public final class LogRedaction {

    static final String MASK = "[redacted]";

    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(?:(?:bearer|basic)\\s+)?[^\\s,;\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern TICKET_SECRET = Pattern.compile("(?i)(x-ticket-secret\\s*[:=]\\s*)[^\\s,;\"']+");
    private static final Pattern CREDENTIAL_PAIR =
            Pattern.compile("(?i)\\b(qms_refresh|refresh_token|access_token|otp|password|passcode)(\\s*[:=]\\s*)[^\\s,;\"'&]+");

    private LogRedaction() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = AUTHORIZATION.matcher(text).replaceAll("$1" + MASK);
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = JWT.matcher(out).replaceAll(MASK);
        out = TICKET_SECRET.matcher(out).replaceAll("$1" + MASK);
        out = CREDENTIAL_PAIR.matcher(out).replaceAll("$1$2" + MASK);
        return out;
    }
}
