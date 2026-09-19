package com.qms.queue;

import java.util.UUID;

/**
 * Where a new ticket's Priority class comes from (SRS §10.2, FR-QUE-011): the first of these that names a class wins,
 * manual assignment by staff, the class of the appointment, the class mapped to the visitor's category, the default of the
 * issuing channel, the default of the service. When none does, the ticket belongs to the default (normal) class, which is
 * {@code null} here and stored as no class at all.
 *
 * <p>Pure: the caller resolves each source to a class that can be given to a new ticket (one that exists and is active) or
 * to null, so a source that names a class that has since been switched off is passed over like one that names nothing.
 * The class is read once, at issue; nothing here ever runs again for a ticket that has been issued (FR-CFG-041).
 */
public final class PriorityPrecedence {

    /** The sources, in order of precedence. */
    public enum Source {
        MANUAL,
        APPOINTMENT,
        VISITOR_CATEGORY,
        CHANNEL_DEFAULT,
        SERVICE_DEFAULT,
        /** None of the above named a class: the default (normal) class. */
        NONE;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The class chosen ({@code null} for the default class) and the source that chose it. */
    public record Choice(UUID classId, Source source) {}

    private PriorityPrecedence() {}

    public static Choice choose(UUID manual, UUID appointment, UUID visitorCategory, UUID channelDefault, UUID serviceDefault) {
        if (manual != null) return new Choice(manual, Source.MANUAL);
        if (appointment != null) return new Choice(appointment, Source.APPOINTMENT);
        if (visitorCategory != null) return new Choice(visitorCategory, Source.VISITOR_CATEGORY);
        if (channelDefault != null) return new Choice(channelDefault, Source.CHANNEL_DEFAULT);
        if (serviceDefault != null) return new Choice(serviceDefault, Source.SERVICE_DEFAULT);
        return new Choice(null, Source.NONE);
    }
}
