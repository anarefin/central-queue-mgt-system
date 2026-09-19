package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-INT-011's validation report for one CSV file, manual or scheduled: how many rows were seen, how many became a
 * fresh visitor, how many updated one already known by {@code external_code}, and every row that failed validation
 * and was skipped. Persisted as one {@code visitor_import_run} row so a scheduled pickup's report is still there to
 * be seen after the fact, when nobody was present for the run itself.
 */
public record VisitorImportReport(
        UUID id,
        /** {@code manual} (an admin's upload) or {@code scheduled} (the folder pickup). */
        String source,
        String filename,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("total_rows") int totalRows,
        @JsonProperty("inserted_count") int insertedCount,
        @JsonProperty("updated_count") int updatedCount,
        @JsonProperty("failed_count") int failedCount,
        String status,
        List<VisitorImportError> errors) {}
