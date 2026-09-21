package com.qms.reporting;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Role;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /reports/schedules} (ticket 52, SRS §16, FR-RPT-005): an admin schedules any report already in {@code
 * ReportRunService}'s own catalogue (ticket 48/50/51) to be emailed to a named list, daily, weekly or monthly, in a
 * chosen format. Creating or changing a schedule is gated the same way {@link ReportExportService#EXPORT} already
 * gates an export — {@code reports:run_export} plus {@code visitor_pii:view} — because a schedule leaves the
 * system unattended on every future run, the same reasoning FR-RPT-007 already applies to an export a caller
 * triggers by hand; viewing a schedule or its delivery log only needs {@code reports:run_export}, the same split
 * {@link ReportExportService#VIEW_JOB} already draws.
 *
 * <p>{@code create}/{@code update} run the target report key once, as a dry run, under the real caller's own
 * security context, before writing anything — the exact same {@link ReportRunService#run} call {@link
 * ReportScheduleRunner} will later replay on a schedule. This catches an unknown key ({@code not_found}), a filter
 * the caller cannot reach ({@code forbidden}/{@code validation_failed}) or a key the caller's own role cannot run
 * (such as {@code audit}, System/Org Admin only) at creation time, server-side, rather than silently at the next
 * tick — with no validation logic of its own to drift from the catalogue's.
 */
@Service
@Profile(Profiles.SERVING)
class ReportScheduleService {

    static final String MANAGE =
            "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)"
                    + " and hasAuthority(T(com.qms.platform.security.Authorities).VISITOR_PII_VIEW)";

    static final String VIEW = "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)";

    private static final int MAX_RECIPIENTS = 50;
    private static final int DRY_RUN_SIZE = 1;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ReportScheduleRepository repo;
    private final ReportRunService reportRunService;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final JsonMapper mapper;
    private final Clock clock;

    ReportScheduleService(
            ReportScheduleRepository repo, ReportRunService reportRunService, AuditWriter audit, CurrentUser currentUser, ScopeGuard scope,
            JsonMapper mapper, Clock clock) {
        this.repo = repo;
        this.reportRunService = reportRunService;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional
    ReportScheduleView create(ReportScheduleRequest request) {
        String key = requireKey(request.reportKey());
        ReportScheduleCadence cadence = validateCadence(request.cadence());
        ReportExportFormat format = validateFormat(request.format());
        List<String> recipients = validateRecipients(request.recipients());
        ReportScheduleFilter filter = request.filter() == null ? ReportScheduleFilter.EMPTY : request.filter();

        AuthenticatedUser user = currentUser.require();
        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);
        List<String> creatorRoles = user.roles().stream().map(Role::wire).sorted().toList();

        Instant now = clock.instant();
        dryRun(key, cadence, filter, now);

        Instant nextRunAt = cadence.next(now);
        UUID id = repo.insert(key, cadence.wire(), format.wire(), recipients, filter, allowedSites, allowedGroups, creatorRoles, user.userId(), now, nextRunAt);
        audit.record(AuditEvent.of("report_schedule.created", "report_schedule", id).withAfter(
                Map.of("report_key", key, "cadence", cadence.wire(), "format", format.wire(), "recipients", recipients.size())));
        return view(mustFind(id));
    }

    @PreAuthorize(VIEW)
    @Transactional(readOnly = true)
    List<ReportScheduleView> list() {
        return repo.list().stream().map(this::view).toList();
    }

    @PreAuthorize(VIEW)
    @Transactional(readOnly = true)
    ReportScheduleView get(UUID id) {
        return view(mustFind(id));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    ReportScheduleView update(UUID id, ReportScheduleRequest request) {
        ReportScheduleRepository.Row existing = mustFind(id);
        ReportScheduleCadence cadence = validateCadence(request.cadence());
        ReportExportFormat format = validateFormat(request.format());
        List<String> recipients = validateRecipients(request.recipients());
        ReportScheduleFilter filter = request.filter() == null ? ReportScheduleFilter.EMPTY : request.filter();
        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();

        Instant now = clock.instant();
        dryRun(existing.reportKey(), cadence, filter, now);

        boolean cadenceChanged = !cadence.wire().equals(existing.cadence());
        boolean resuming = enabled && !existing.enabled();
        Instant nextRunAt = existing.nextRunAt();
        if (cadenceChanged || (resuming && !existing.nextRunAt().isAfter(now))) {
            nextRunAt = cadence.next(now);
        }

        repo.update(id, cadence.wire(), format.wire(), recipients, filter, enabled, now, nextRunAt);
        audit.record(AuditEvent.of("report_schedule.updated", "report_schedule", id).withAfter(
                Map.of("cadence", cadence.wire(), "format", format.wire(), "enabled", enabled)));
        return view(mustFind(id));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    void delete(UUID id) {
        mustFind(id);
        repo.delete(id);
        audit.record(AuditEvent.of("report_schedule.deleted", "report_schedule", id));
    }

    @PreAuthorize(VIEW)
    @Transactional(readOnly = true)
    List<ReportScheduleDeliveryView> deliveries(UUID id) {
        mustFind(id);
        return repo.deliveries(id, 200).stream()
                .map(row -> new ReportScheduleDeliveryView(row.id(), row.runAt(), row.recipient(), row.status(), row.rowCount(), row.error(), row.attemptedAt()))
                .toList();
    }

    /** The same {@link ReportRunService#run} call {@link ReportScheduleRunner} will later replay on a schedule,
     * run once here under the real caller's own security context so a problem with the key, the filter or the
     * caller's own permission for this specific key surfaces at creation time, not at the next tick. */
    private void dryRun(String key, ReportScheduleCadence cadence, ReportScheduleFilter filter, Instant now) {
        Instant windowStart = cadence.windowStart(now);
        reportRunService.run(key, filter.asRunRequest(windowStart, now, DRY_RUN_SIZE));
    }

    private ReportScheduleRepository.Row mustFind(UUID id) {
        return repo.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ReportScheduleView view(ReportScheduleRepository.Row row) {
        ReportScheduleFilter filter = mapper.readValue(row.filterJson(), ReportScheduleFilter.class);
        return new ReportScheduleView(
                row.id(), row.reportKey(), row.cadence(), row.format(), row.recipients(), filter, row.enabled(), row.createdBy(),
                row.createdAt(), row.updatedAt(), row.nextRunAt(), row.lastRunAt());
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw fail("report_key", "required");
        return key;
    }

    private static ReportScheduleCadence validateCadence(String cadence) {
        return ReportScheduleCadence.fromWire(cadence).orElseThrow(() -> fail("cadence", "unknown_value"));
    }

    private static ReportExportFormat validateFormat(String format) {
        return ReportExportFormat.fromWire(format).orElseThrow(() -> fail("format", "unknown_value"));
    }

    private static List<String> validateRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) throw fail("recipients", "required");
        if (recipients.size() > MAX_RECIPIENTS) throw fail("recipients", "too_many");
        Set<String> normalised = new LinkedHashSet<>();
        for (String recipient : recipients) {
            String trimmed = recipient == null ? "" : recipient.trim();
            if (!EMAIL.matcher(trimmed).matches()) throw fail("recipients", "invalid_email");
            normalised.add(trimmed);
        }
        return List.copyOf(normalised);
    }

    private Set<UUID> reachSites(ReportScheduleFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.siteId() != null) {
            scope.requireSite(filter.siteId());
            return null;
        }
        return user.siteIds().isEmpty() ? null : Set.copyOf(user.siteIds());
    }

    private Set<UUID> reachGroups(ReportScheduleFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.serviceGroupId() != null) {
            scope.requireGroup(filter.serviceGroupId());
            return null;
        }
        return user.groupIds().isEmpty() ? null : Set.copyOf(user.groupIds());
    }

    private static ApiException fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
