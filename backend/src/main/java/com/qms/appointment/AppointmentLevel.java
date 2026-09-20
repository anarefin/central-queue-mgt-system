package com.qms.appointment;

import java.util.Arrays;
import java.util.Optional;

/** The three levels availability can be defined at, most specific first (FR-APT-001). */
public enum AppointmentLevel {
    AGENT("agent"),
    TEAM("team"),
    SERVICE("service");

    private final String wire;

    AppointmentLevel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<AppointmentLevel> tryFromWire(String wire) {
        return Arrays.stream(values()).filter(l -> l.wire.equals(wire)).findFirst();
    }

    public static AppointmentLevel fromWire(String wire) {
        return tryFromWire(wire).orElseThrow(() -> new IllegalArgumentException("Unknown level: " + wire));
    }
}
