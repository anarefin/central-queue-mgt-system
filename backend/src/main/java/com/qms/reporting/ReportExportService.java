package com.qms.reporting;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.i18n.Messages;
import com.qms.platform.i18n.RequestLanguage;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code POST /reports/{key}/export} (ticket 49, FR-RPT-003/004/006/007): generates the one report this catalogue
 * holds (see {@code ReportRunService}, ticket 48) as CSV, XLSX or PDF, inline when the filtered row count is under
 * the configured threshold, or as a background {@link ReportExportJobRepository} row otherwise (FR-RPT-004). Every
 * export of {@code detailed-token} carries visitor PII (visitor code and name, §16.1), so — unlike on-screen viewing,
 * which only {@code reports:run_export} gates — exporting it additionally needs {@code visitor_pii:view} (FR-RPT-007:
 * "permission-gated separately from ordinary report access") and is written to the audit log before generation
 * starts, whether the export ends up inline or queued.
 */
@Service
public class ReportExportService {

    static final String EXPORT =
            "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)"
                    + " and hasAuthority(T(com.qms.platform.security.Authorities).VISITOR_PII_VIEW)";

    static final String VIEW_JOB = "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)";

    private final DetailedTokenReportReads reads;
    private final ReportExportJobRepository jobs;
    private final DetailedTokenReportCsvWriter csv;
    private final DetailedTokenReportXlsxWriter xlsx;
    private final DetailedTokenReportPdfWriter pdf;
    private final ReportExportProperties properties;
    private final Messages messages;
    private final RequestLanguage requestLanguage;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReportExportService(
            DetailedTokenReportReads reads,
            ReportExportJobRepository jobs,
            DetailedTokenReportCsvWriter csv,
            DetailedTokenReportXlsxWriter xlsx,
            DetailedTokenReportPdfWriter pdf,
            ReportExportProperties properties,
            Messages messages,
            RequestLanguage requestLanguage,
            AuditWriter audit,
            CurrentUser currentUser,
            ScopeGuard scope,
            JdbcTemplate jdbc,
            Clock clock) {
        this.reads = reads;
        this.jobs = jobs;
        this.csv = csv;
        this.xlsx = xlsx;
        this.pdf = pdf;
        this.properties = properties;
        this.messages = messages;
        this.requestLanguage = requestLanguage;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    sealed interface Outcome {
        record Ready(ReportExportFormat format, byte[] content, String filename) implements Outcome {}

        record Queued(UUID jobId) implements Outcome {}
    }

    @PreAuthorize(EXPORT)
    @Transactional
    public Outcome export(String key, ReportExportRequest request) {
        if (!ReportRunService.DETAILED_TOKEN_KEY.equals(key)) throw new ApiException(ErrorCode.NOT_FOUND);
        ReportExportFormat format = ReportExportFormat.fromWire(request == null ? null : request.format())
                .orElseThrow(() -> fail("format", "unknown_value"));
        DetailedTokenReportFilter filter = request.filter();
        validateRange(filter);

        AuthenticatedUser user = currentUser.require();
        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);
        long rowCount = reads.totalRows(filter, allowedSites, allowedGroups);
        boolean async = rowCount > properties.asyncThresholdRows();
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();

        // FR-RPT-007 / FR-SEC-040: written to the audit log before generation starts, whether inline or queued —
        // every report this catalogue holds carries visitor PII today, so this always fires (see contains_pii below).
        audit.record(AuditEvent.of("report.exported", "report_export", jobId).withAfter(Map.of(
                "report_key", key, "format", format.wire(), "row_count", rowCount, "async", async, "contains_pii", true)));

        String language = language();
        List<String[]> headerLines = ReportExportHeader.lines(messages, language, key, filter, now, generatedBy(user.userId()));
        List<String> columnHeaders = DetailedTokenReportColumns.headers(messages, language);

        if (!async) {
            byte[] content = generate(format, headerLines, columnHeaders, rows -> reads.stream(filter, allowedSites, allowedGroups, rows), language);
            return new Outcome.Ready(format, content, filename(key, format));
        }

        jobs.insertQueued(jobId, key, format, filter, allowedSites, allowedGroups, true, user.userId(), now);
        return new Outcome.Queued(jobId);
    }

    @PreAuthorize(VIEW_JOB)
    @Transactional(readOnly = true)
    public ReportExportJobView job(UUID id) {
        ReportExportJobRepository.Row row = jobs.find(id)
                .filter(j -> j.requestedBy().equals(currentUser.require().userId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return toView(row);
    }

    /** The file behind a finished job's expiring link (FR-RPT-004); {@code not_found} once expired, missing, not
     * yet finished, or not the requester's own job — the same answer whether the reason is "never existed" or
     * "expired", so a link never distinguishes the two to a caller who is not its owner. */
    @PreAuthorize(VIEW_JOB)
    @Transactional(readOnly = true)
    public ReadyDownload download(UUID id) {
        ReportExportJobRepository.Row row = jobs.find(id)
                .filter(j -> j.requestedBy().equals(currentUser.require().userId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!"done".equals(row.status()) || row.expiresAt() == null || !row.expiresAt().isAfter(clock.instant()) || row.filePath() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        ReportExportFormat format = ReportExportFormat.fromWire(row.format()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new ReadyDownload(java.nio.file.Path.of(row.filePath()), format, filename(row.reportKey(), format));
    }

    record ReadyDownload(java.nio.file.Path file, ReportExportFormat format, String filename) {}

    private byte[] generate(ReportExportFormat format, List<String[]> headerLines, List<String> columnHeaders, ReportRowSource source, String language) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            switch (format) {
                case CSV -> csv.write(out, headerLines, columnHeaders, source, language);
                case XLSX -> xlsx.write(out, headerLines, columnHeaders, source, language);
                case PDF -> pdf.write(out, headerLines, columnHeaders, source, language);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private String language() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            return requestLanguage.current(request);
        }
        return "en";
    }

    private String generatedBy(UUID userId) {
        String name = jdbc.query(
                "SELECT coalesce(display_name, username) AS name FROM users WHERE id = ?", rs -> rs.next() ? rs.getString("name") : null, userId);
        return name != null ? name : userId.toString();
    }

    private static String filename(String key, ReportExportFormat format) {
        return key + "." + format.extension();
    }

    private ReportExportJobView toView(ReportExportJobRepository.Row row) {
        return new ReportExportJobView(row.id(), row.reportKey(), row.format(), row.status(), row.rowCount(), row.requestedAt(), row.completedAt(), row.expiresAt(), row.error());
    }

    private Set<UUID> reachSites(DetailedTokenReportFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.siteId() != null) {
            scope.requireSite(filter.siteId());
            return null;
        }
        return user.siteIds().isEmpty() ? null : Set.copyOf(user.siteIds());
    }

    private Set<UUID> reachGroups(DetailedTokenReportFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.serviceGroupId() != null) {
            scope.requireGroup(filter.serviceGroupId());
            return null;
        }
        return user.groupIds().isEmpty() ? null : Set.copyOf(user.groupIds());
    }

    private void validateRange(DetailedTokenReportFilter filter) {
        if (filter.from() != null && filter.to() != null && !filter.to().isAfter(filter.from())) {
            throw fail("to", "before_from");
        }
    }

    private static ApiException fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
