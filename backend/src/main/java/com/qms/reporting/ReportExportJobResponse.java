package com.qms.reporting;

import java.util.UUID;

/** {@code POST /reports/{key}/export}'s answer when the export was queued instead of generated inline (FR-RPT-004):
 * {@code GET /reports/jobs/{id}} is where the caller polls it from here. */
record ReportExportJobResponse(UUID id, String status) {}
