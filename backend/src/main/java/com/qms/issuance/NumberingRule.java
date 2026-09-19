package com.qms.issuance;

import java.time.Instant;
import java.util.UUID;

/** A numbering rule as stored: one per Service or Service group (FR-CFG-018). */
record NumberingRule(UUID id, UUID siteId, String scopeType, UUID scopeId, NumberingSpec spec, Instant createdAt, Instant updatedAt) {

    static final String SERVICE = "service";
    static final String SERVICE_GROUP = "service_group";
}
