package com.qms.appointment;

import java.util.Set;

/** How a staff booking reached the system (FR-APT-013): a call, a walk-in booked on the spot, or any other staff action. */
public final class AppointmentSource {

    public static final String PHONE = "phone";
    public static final String WALK_IN = "walk_in";
    public static final String STAFF = "staff";
    public static final Set<String> ALL = Set.of(PHONE, WALK_IN, STAFF);

    private AppointmentSource() {}
}
