package com.qms.issuance;

import java.util.Set;

/** The issuing channels of SRS §8 (FR-CFG-010). Names match the values a Service lists in its {@code channels}. */
public final class Channels {

    public static final String KIOSK = "kiosk";
    public static final String RECEPTION = "reception";
    public static final String MOBILE = "mobile";
    public static final String APPOINTMENT_CHECKIN = "appointment_checkin";
    public static final Set<String> ALL = Set.of(KIOSK, RECEPTION, MOBILE, APPOINTMENT_CHECKIN);

    private Channels() {}
}
