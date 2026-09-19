package com.qms.issuance;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The numbering rule (SRS §4.4): {@code {prefix}{separator}{zero-padded sequence}}, with a sequence that restarts every
 * day at the site's local midnight. The default is prefix from the service, separator {@code -}, padding 3, reset
 * daily. Digits are always Western Arabic (FR-I18N-020), whatever the language, so this uses plain formatting.
 * Configurable rules arrive with their own ticket; this is the rule they will start from.
 */
public final class TokenNumbering {

    public static final String SEPARATOR = "-";
    public static final int PADDING = 3;

    private TokenNumbering() {}

    /** The reset period an instant belongs to: the site-local calendar day, for example {@code 2026-09-19}. */
    public static String resetKey(Instant at, ZoneId siteZone) {
        return at.atZone(siteZone).toLocalDate().toString();
    }

    public static String format(String prefix, long sequence) {
        return prefix + SEPARATOR + String.format("%0" + PADDING + "d", sequence);
    }
}
