package com.qms.issuance;

/** Who caused a ticket transition; recorded on every {@code ticket_event}. */
public enum ActorType {
    STAFF("staff"),
    DEVICE("device"),
    VISITOR("visitor"),
    SYSTEM("system");

    private final String wire;

    ActorType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
