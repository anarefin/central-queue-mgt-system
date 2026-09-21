package com.qms.reporting;

import com.qms.platform.Profiles;
import com.qms.platform.i18n.Messages;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.PermissionMatrix;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fires every due schedule (ticket 52, FR-RPT-005): generates that schedule's own report key over its own cadence
 * window and emails the rendered file to its recipients, logging every attempt.
 *
 * <p>Unlike {@code ReportExportJobWorker} (ticket 49), which bypasses the report catalogue's secured entry point
 * entirely and reads {@code DetailedTokenReportReads} directly because a background export only ever needs that
 * one report's own streaming read, this worker has to run <em>any</em> catalogue key — including {@code audit},
 * gated more strictly than the rest (System/Org Admin only, on top of {@code reports:run_export}). Re-implementing
 * all fifteen keys' own read/aggregate logic here — duplicating {@code DomainReportService}, {@code
 * OperationalReportService} and {@code PlanningViewService} — would drift from the on-screen answer over time.
 * Instead, for the duration of one schedule's own generation, this reconstructs the exact {@code Authentication}
 * its creator held when the schedule was made — the roles and the resolved site/group reach {@link
 * ReportScheduleService#create} already captured and baked into the schedule's own row, replayed here rather than
 * re-derived because a scheduled tick has no request of its own to derive them from (the same "baked in... never
 * recomputed by the worker" reasoning {@code reporting.export_job}'s own migration comment already gives for its
 * {@code allowed_sites}/{@code allowed_groups}) — and calls {@link ReportRunService#run} exactly as {@code
 * ReportController} would for a real request, so every permission gate the catalogue enforces is enforced
 * identically here, with zero duplicated business logic. The {@link Jwt} built below is never decoded from a real
 * token; it is only the shape {@link AuthenticatedUser#from} already knows how to read.
 */
@Component
@Profile(Profiles.SERVING)
class ReportScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleRunner.class);
    private static final int BATCH_SIZE = 10;
    static final int MAX_ROWS = 1000;

    private final ReportScheduleRepository repo;
    private final ReportRunService reportRunService;
    private final ScheduledReportRenderer renderer;
    private final ScheduledReportMailer mailer;
    private final Messages messages;
    private final JsonMapper mapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReportScheduleRunner(
            ReportScheduleRepository repo, ReportRunService reportRunService, ScheduledReportRenderer renderer, ScheduledReportMailer mailer,
            Messages messages, JsonMapper mapper, JdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.reportRunService = reportRunService;
        this.renderer = renderer;
        this.mailer = mailer;
        this.messages = messages;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One sweep: every due schedule, each in its own transaction so one failure never blocks the rest. */
    int tick() {
        Instant now = clock.instant();
        List<ReportScheduleRepository.Row> due = repo.due(now, BATCH_SIZE);
        for (ReportScheduleRepository.Row schedule : due) process(schedule, now);
        return due.size();
    }

    @Transactional
    void process(ReportScheduleRepository.Row schedule, Instant runAt) {
        var cadence = ReportScheduleCadence.fromWire(schedule.cadence());
        Instant nextRunAt = cadence.map(c -> c.next(runAt)).orElse(runAt);

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(principalContext(schedule));
            ReportScheduleCadence c = cadence.orElseThrow();
            deliver(schedule, c.windowStart(runAt), runAt, runAt);
        } catch (RuntimeException e) {
            log.warn("report schedule {} failed", schedule.id(), e);
            repo.insertDelivery(schedule.id(), runAt, null, "failed", null, String.valueOf(e.getMessage()), clock.instant());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
        repo.markRun(schedule.id(), runAt, nextRunAt);
    }

    private void deliver(ReportScheduleRepository.Row schedule, Instant from, Instant to, Instant runAt) {
        ReportScheduleFilter filter = mapper.readValue(schedule.filterJson(), ReportScheduleFilter.class);
        Object result = reportRunService.run(schedule.reportKey(), filter.asRunRequest(from, to, MAX_ROWS));

        ReportExportFormat format = ReportExportFormat.fromWire(schedule.format()).orElseThrow();
        String language = "en"; // a scheduled tick has no request/Accept-Language to resolve; the system default.
        List<String[]> headerLines = header(schedule.reportKey(), filter, from, to, runAt, schedule.createdBy(), language);

        ScheduledReportRenderer.Rendered rendered;
        try {
            rendered = renderer.render(format, headerLines, result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String filename = schedule.reportKey() + "." + format.extension();
        String subject = messages.text("reports.schedule.email.subject", language, schedule.reportKey());
        String body = messages.text("reports.schedule.email.body", language, schedule.reportKey(), from.toString(), to.toString());

        for (String recipient : schedule.recipients()) {
            ScheduledReportMailer.Outcome outcome = mailer.send(recipient, subject, body, rendered.content(), filename, format.contentType());
            if (outcome instanceof ScheduledReportMailer.Outcome.Sent) {
                repo.insertDelivery(schedule.id(), runAt, recipient, "sent", rendered.rowCount(), null, clock.instant());
            } else {
                ScheduledReportMailer.Outcome.Failed failed = (ScheduledReportMailer.Outcome.Failed) outcome;
                repo.insertDelivery(schedule.id(), runAt, recipient, "failed", rendered.rowCount(), failed.reason(), clock.instant());
            }
        }
    }

    private List<String[]> header(String key, ReportScheduleFilter filter, Instant from, Instant to, Instant generatedAt, UUID createdBy, String language) {
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {messages.text("reports.export.header.report", language), key});
        lines.add(new String[] {messages.text("reports.export.header.filters", language), filterSummary(filter, from, to)});
        lines.add(new String[] {
            messages.text("reports.export.header.generatedAt", language), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(generatedAt.atOffset(ZoneOffset.UTC))
        });
        lines.add(new String[] {messages.text("reports.export.header.generatedBy", language), generatedByName(createdBy)});
        return lines;
    }

    private String generatedByName(UUID userId) {
        String name = jdbc.query(
                "SELECT coalesce(display_name, username) AS name FROM users WHERE id = ?", rs -> rs.next() ? rs.getString("name") : null, userId);
        return name != null ? name : userId.toString();
    }

    private static String filterSummary(ReportScheduleFilter filter, Instant from, Instant to) {
        StringJoiner joiner = new StringJoiner("; ");
        joiner.add("from=" + from);
        joiner.add("to=" + to);
        add(joiner, "site_id", filter.siteId());
        add(joiner, "zone_id", filter.zoneId());
        add(joiner, "service_group_id", filter.serviceGroupId());
        add(joiner, "service_id", filter.serviceId());
        add(joiner, "agent_id", filter.agentId());
        add(joiner, "priority_class_id", filter.priorityClassId());
        add(joiner, "channel", filter.channel());
        add(joiner, "visitor_category", filter.visitorCategory());
        return joiner.toString();
    }

    private static void add(StringJoiner joiner, String field, Object value) {
        if (value != null) joiner.add(field + "=" + value);
    }

    /** Reconstructs, for this transaction alone, the {@code Authentication} the schedule's own creator held: their
     * captured roles (so every permission gate the catalogue enforces answers exactly as it would have for them)
     * and their captured site/group reach (so an unscoped filter still resolves the same reach it did at creation
     * time, via the exact same {@code reachSites}/{@code reachGroups} logic every report service already runs). */
    private SecurityContext principalContext(ReportScheduleRepository.Row schedule) {
        Instant now = clock.instant();
        Jwt jwt = Jwt.withTokenValue("schedule:" + schedule.id())
                .header("alg", "none")
                .subject(schedule.createdBy().toString())
                .claim("roles", schedule.creatorRoles())
                .claim("sites", strings(schedule.allowedSites()))
                .claim("groups", strings(schedule.allowedGroups()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        Set<GrantedAuthority> authorities = new HashSet<>();
        user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.wire().toUpperCase(Locale.ROOT))));
        PermissionMatrix.authoritiesFor(user.roles()).forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        return context;
    }

    private static List<String> strings(Set<UUID> ids) {
        return ids == null ? List.of() : ids.stream().map(UUID::toString).toList();
    }
}
