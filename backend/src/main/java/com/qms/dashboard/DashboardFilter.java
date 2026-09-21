package com.qms.dashboard;

import java.util.UUID;

/**
 * The filter a dashboard view is under (FR-MON-002): a Site is mandatory, the rest narrow it further. Every field of
 * a {@code GET /dashboard/live} query string round-trips here unchanged, so the same query string is what makes the
 * view shareable as a URL.
 */
public record DashboardFilter(UUID siteId, UUID zoneId, UUID serviceGroupId, UUID serviceId, UUID priorityClassId) {}
