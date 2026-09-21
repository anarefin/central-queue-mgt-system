package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.audit.AuditEntry;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /reports/audit/run}'s answer (ticket 51, §16.1's "Audit report", FR-SEC-042): the catalogue's own
 * {@code key}/{@code generated_at} envelope wrapped around {@code com.qms.audit.AuditQueryService#search}'s own
 * page, reused unchanged rather than re-implemented (the same "wire an existing report in under this catalogue's
 * own path" shape ticket 50 already gave the pre-existing break report). Cursor-paged, not {@code page}/{@code
 * size}, because that read path already is.
 */
record AuditReportPage(String key, @JsonProperty("generated_at") Instant generatedAt, List<AuditEntry> items, @JsonProperty("next_cursor") String nextCursor) {}
