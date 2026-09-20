package com.qms.appointment;

import java.util.Set;

/** How a booking reached the system (FR-APT-013): a call, a walk-in booked on the spot, any other staff action, or a
 * registered visitor's own self-service booking (ticket 41, §5.2 "Book an appointment" — S for Visitor). */
public final class AppointmentSource {

    public static final String PHONE = "phone";
    public static final String WALK_IN = "walk_in";
    public static final String STAFF = "staff";
    /** Never client-chosen: {@code AppointmentBookingService} sets this itself once the caller's own JWT carries
     * {@code Role.VISITOR}, the same way it derives the visitor id itself rather than trusting one in the request. */
    public static final String VISITOR = "visitor";
    public static final Set<String> ALL = Set.of(PHONE, WALK_IN, STAFF, VISITOR);

    private AppointmentSource() {}
}
