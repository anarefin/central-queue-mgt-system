package com.qms.dashboard;

/** Body of {@code POST /alerts/{id}/acknowledge} (FR-MON-022): an optional free-text note, recorded with the
 * acknowledgement. */
public record AlertAcknowledgeRequest(String note) {}
