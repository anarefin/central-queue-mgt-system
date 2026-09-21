package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code POST /reports/detailed-token/run}'s answer (§16.1, FR-RPT-002): one page of the detailed token report. */
record DetailedTokenReportPage(
        String key,
        @JsonProperty("generated_at") Instant generatedAt,
        int page,
        int size,
        @JsonProperty("total_rows") long totalRows,
        @JsonProperty("total_pages") long totalPages,
        /** "Tickets issued" (§18.5, ADR-0006): chain heads only, among the rows this filter reaches — never the
         * count of rows on this page, and never every row regardless of predecessor/successor. */
        @JsonProperty("tickets_issued") long ticketsIssued,
        List<Row> rows) {

    record Row(
            @JsonProperty("ticket_id") UUID ticketId,
            String token,
            @JsonProperty("visitor_code") String visitorCode,
            @JsonProperty("visitor_name") String visitorName,
            @JsonProperty("visitor_category") String visitorCategory,
            @JsonProperty("service_group") Map<String, String> serviceGroup,
            @JsonProperty("service") Map<String, String> service,
            String channel,
            @JsonProperty("priority_class") Map<String, String> priorityClass,
            @JsonProperty("issue_time") Instant issueTime,
            @JsonProperty("call_time") Instant callTime,
            @JsonProperty("start_time") Instant startTime,
            @JsonProperty("end_time") Instant endTime,
            @JsonProperty("wait_seconds") Integer waitSeconds,
            @JsonProperty("service_seconds") Integer serviceSeconds,
            String counter,
            String agent,
            Map<String, String> outcome,
            int transfers) {}
}
