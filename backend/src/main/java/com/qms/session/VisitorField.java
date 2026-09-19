package com.qms.session;

import java.util.Arrays;
import java.util.Optional;

/** The visitor data a called ticket can carry to the console (FR-AGT-030); which of them a role sees is configuration (FR-AGT-034, §25.3). */
enum VisitorField {
    CODE("code"),
    NAME("name"),
    CATEGORY("category"),
    PURPOSE_NOTE("purpose_note");

    private final String wire;

    VisitorField(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<VisitorField> fromWire(String wire) {
        return Arrays.stream(values()).filter(field -> field.wire.equals(wire)).findFirst();
    }
}
