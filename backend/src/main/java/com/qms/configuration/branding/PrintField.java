package com.qms.configuration.branding;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed set of fields a printed token layout may show (FR-CFG-031): nothing outside this set is ever offered,
 * and a template's {@code fields} carries only these wire values, in any order and any subset the admin chooses.
 */
enum PrintField {
    TOKEN_NUMBER("token_number"),
    BUILDING("building"),
    FLOOR("floor"),
    SERVICE_GROUP("service_group"),
    SERVICE("service"),
    VISITOR_CODE("visitor_code"),
    VISITOR_NAME("visitor_name"),
    VISITOR_CATEGORY("visitor_category"),
    COUNTER("counter"),
    ISSUE_TIME("issue_time"),
    ESTIMATED_WAIT("estimated_wait"),
    QR_CODE("qr_code"),
    NOTICE_LINE("notice_line");

    private final String wire;

    PrintField(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static Optional<PrintField> fromWire(String wire) {
        return Arrays.stream(values()).filter(f -> f.wire.equals(wire)).findFirst();
    }
}
