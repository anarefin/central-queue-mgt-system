package com.qms.issuance;

import com.qms.issuance.TokenNumbering.ResetBoundary;
import java.time.LocalTime;
import java.util.List;

/**
 * What a numbering rule says (FR-CFG-018), whether it was configured for a Service, for a Service group or is the
 * built-in default.
 *
 * <p>The {@code priority_class} prefix source takes the prefix override of the ticket's Priority class (FR-QUE-010).
 * A class without an override, and the default class, leave the Service's own prefix, so a ticket of the normal class
 * looks as it always did.
 */
public record NumberingSpec(String prefixSource, String fixedPrefix, long start, int padding, ResetBoundary boundary, LocalTime resetTime, String separator) {

    public static final String SERVICE = "service";
    public static final String SERVICE_GROUP = "service_group";
    public static final String PRIORITY_CLASS = "priority_class";
    public static final String FIXED = "fixed";
    public static final List<String> PREFIX_SOURCES = List.of(SERVICE, SERVICE_GROUP, PRIORITY_CLASS, FIXED);

    /** Used where no rule is configured: what tickets have always been numbered with (SRS §4.4). */
    public static final NumberingSpec DEFAULT =
            new NumberingSpec(SERVICE, null, 1, TokenNumbering.PADDING, ResetBoundary.DAILY, LocalTime.MIDNIGHT, TokenNumbering.SEPARATOR);

    /** The prefix a ticket for a Service carries under this rule, whatever its Priority class. */
    public String prefix(String servicePrefix, String groupPrefix) {
        return prefix(servicePrefix, groupPrefix, null);
    }

    /** The prefix a ticket of a Priority class with the given prefix override ({@code null} for none) carries. */
    public String prefix(String servicePrefix, String groupPrefix, String priorityClassPrefix) {
        return switch (prefixSource) {
            case SERVICE_GROUP -> groupPrefix;
            case FIXED -> fixedPrefix;
            case PRIORITY_CLASS -> priorityClassPrefix == null ? servicePrefix : priorityClassPrefix;
            default -> servicePrefix;
        };
    }

    public String format(String prefix, long sequence) {
        return TokenNumbering.format(prefix, separator, padding, sequence);
    }
}
