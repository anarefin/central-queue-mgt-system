package com.qms.reporting;

import com.qms.platform.Profiles;
import com.qms.platform.i18n.Messages;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates a background export's file (FR-RPT-004) and notifies its requester over their own {@code
 * report-export:{user_id}} realtime topic once it is ready — the "notified with an expiring link" the ticket asks
 * for, delivered the same way {@code com.qms.mobile.SmtpVisitorOtpMailer}'s doc comment reasons about a sign-in
 * code: this is not a per-Site/Service, opt-outable, consent-gated visitor notification (SRS §14's trigger
 * catalogue, ticket 38), it is a direct answer to the one user who just asked for their own export, so it goes
 * straight to the realtime hub rather than through that pipeline. {@code GET /reports/jobs/{id}} remains the
 * authoritative status a client not connected to the stream (or one that reconnected and missed the event) polls.
 */
@Component
@Profile(Profiles.SERVING)
class ReportExportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportExportJobWorker.class);
    private static final int BATCH_SIZE = 5;

    private final ReportExportJobRepository jobs;
    private final DetailedTokenReportReads reads;
    private final DetailedTokenReportCsvWriter csv;
    private final DetailedTokenReportXlsxWriter xlsx;
    private final DetailedTokenReportPdfWriter pdf;
    private final ReportExportProperties properties;
    private final Messages messages;
    private final RealtimePublisher realtime;
    private final JsonMapper mapper;
    private final Clock clock;

    ReportExportJobWorker(
            ReportExportJobRepository jobs,
            DetailedTokenReportReads reads,
            DetailedTokenReportCsvWriter csv,
            DetailedTokenReportXlsxWriter xlsx,
            DetailedTokenReportPdfWriter pdf,
            ReportExportProperties properties,
            Messages messages,
            RealtimePublisher realtime,
            JsonMapper mapper,
            Clock clock) {
        this.jobs = jobs;
        this.reads = reads;
        this.csv = csv;
        this.xlsx = xlsx;
        this.pdf = pdf;
        this.properties = properties;
        this.messages = messages;
        this.realtime = realtime;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** One sweep: every due job, each in its own transaction so one failure never blocks the rest. */
    int tick() {
        List<ReportExportJobRepository.Row> due = jobs.due(BATCH_SIZE);
        for (ReportExportJobRepository.Row job : due) process(job);
        return due.size();
    }

    @Transactional
    void process(ReportExportJobRepository.Row job) {
        Instant now = clock.instant();
        jobs.markRunning(job.id(), now);
        try {
            ReportExportFormat format = ReportExportFormat.fromWire(job.format()).orElseThrow();
            DetailedTokenReportFilter filter = mapper.readValue(job.filterJson(), DetailedTokenReportFilter.class);
            Set<java.util.UUID> allowedSites = job.allowedSites();
            Set<java.util.UUID> allowedGroups = job.allowedGroups();
            String language = "en"; // a background job has no request/Accept-Language to resolve; the system default.

            List<String[]> headerLines = ReportExportHeader.lines(messages, language, job.reportKey(), filter, now, job.requestedBy().toString());
            List<String> columnHeaders = DetailedTokenReportColumns.headers(messages, language);

            Path directory = Path.of(properties.storageDir());
            Files.createDirectories(directory);
            Path file = directory.resolve(job.id() + "." + format.extension());
            long rowCount;
            try (OutputStream out = Files.newOutputStream(file)) {
                rowCount = write(format, out, headerLines, columnHeaders, rows -> reads.stream(filter, allowedSites, allowedGroups, rows), language);
            }

            Instant completedAt = clock.instant();
            Instant expiresAt = completedAt.plus(Duration.ofHours(properties.linkTtlHours()));
            jobs.markDone(job.id(), file.toString(), rowCount, completedAt, expiresAt);

            Map<String, Object> data = Map.of(
                    "job_id", job.id().toString(), "report_key", job.reportKey(), "format", format.wire(), "expires_at", expiresAt.toString());
            realtime.publish(Topics.reportExport(job.requestedBy()), "report_export.ready", completedAt, data);
        } catch (IOException | RuntimeException e) {
            log.warn("report export job {} failed", job.id(), e);
            jobs.markFailed(job.id(), String.valueOf(e.getMessage()), clock.instant());
        }
    }

    private long write(ReportExportFormat format, OutputStream out, List<String[]> headerLines, List<String> columnHeaders, ReportRowSource source, String language)
            throws IOException {
        return switch (format) {
            case CSV -> csv.write(out, headerLines, columnHeaders, source, language);
            case XLSX -> xlsx.write(out, headerLines, columnHeaders, source, language);
            case PDF -> pdf.write(out, headerLines, columnHeaders, source, language);
        };
    }
}
