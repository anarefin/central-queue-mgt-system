package com.qms.reporting;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One refresh sweep of the reporting store (ticket 48, FR-RPT-020): every ticket touched since the watermark is
 * upserted into {@code reporting.ticket_fact}, then the watermark advances to the latest {@code ticket_event}
 * swept. Advancing only past what was actually read means a tick that never runs (or a test that drives it directly)
 * simply repeats the same window next time, rather than losing anything.
 */
@Service
class ReportingRefreshService {

    private final ReportingRefreshReads reads;
    private final Clock clock;

    ReportingRefreshService(ReportingRefreshReads reads, Clock clock) {
        this.reads = reads;
        this.clock = clock;
    }

    @Transactional
    int refresh() {
        Instant watermark = reads.watermark();
        Instant since = watermark == null ? Instant.EPOCH : watermark;
        int upserted = reads.upsertChangedSince(since, clock.instant());
        Instant maxRecordedAt = reads.maxEventRecordedAt();
        if (maxRecordedAt != null) reads.advanceWatermark(maxRecordedAt);
        return upserted;
    }
}
