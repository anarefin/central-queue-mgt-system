package com.qms.reporting;

import java.time.Instant;
import java.util.UUID;

/** FR-RPT-001's nine filters, applied against {@code reporting.ticket_fact} alone. */
record DetailedTokenReportFilter(
        Instant from,
        Instant to,
        UUID siteId,
        UUID zoneId,
        UUID serviceGroupId,
        UUID serviceId,
        UUID agentId,
        UUID priorityClassId,
        String channel,
        String visitorCategory) {}
