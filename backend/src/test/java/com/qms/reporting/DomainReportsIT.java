package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 51 against real PostgreSQL (SRS §16.1): the appointment, journey, feedback, notification and audit report
 * keys under {@code POST /reports/{key}/run} — FR-APT-043 (no-show rate per Service/Agent/visitor category),
 * FR-QUE-064 (journey completion, duration, per-stop wait) and the two §15.3 KPIs (appointment adherence, journey
 * completion) ticket 50 explicitly left for this ticket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, DomainReportsIT.Clocks.class})
class DomainReportsIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.now();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
        registry.add("qms.queue.remote-arrival-check-cron", () -> "-");
        registry.add("qms.reporting.refresh-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-domain-reports");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired ReportingRefreshScheduler scheduler;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = NULL");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures (mirrors OperationalReportsIT's own, ticket 50) ------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'UTC', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "R-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        return new World(site, zone, group, newService(group, "A", "Consultation"));
    }

    private UUID newService(UUID group, String prefix, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, ?::jsonb, ?, 10, 5, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, "{\"en\":\"" + name + "\"}", prefix);
        return id;
    }

    private UUID newOutcome(UUID service, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outcome_code (id, service_id, code, label_i18n) VALUES (?, ?, ?, ?::jsonb)", id, service, code, "{\"en\":\"" + code + "\"}");
        return id;
    }

    private UUID counter(UUID zone, UUID service, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, zone, label);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, service);
        return id;
    }

    private Staff staff(Role role, UUID site, UUID teamOf) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = UUID.randomUUID();
            String username = role.wire() + "-" + user;
            jdbc.update(
                    "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                    user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
            UUID[] sites = site == null ? new UUID[0] : new UUID[] {site};
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, user);
                ps.setString(3, role.wire());
                ps.setArray(4, connection.createArrayOf("uuid", sites));
                ps.setArray(5, connection.createArrayOf("uuid", teamOf == null ? new UUID[0] : new UUID[] {teamOf}));
                return ps;
            });
            if (teamOf != null) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private UUID issue(UUID service, String channel) {
        return issuance.issue(new IssueCommand(service, channel, UUID.randomUUID(), ActorType.SYSTEM, null, null, null, false, null, null, null)).id();
    }

    private UUID openSession(Staff agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private void callAndServe(Staff agent, UUID session) throws Exception {
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null))).isEqualTo(200);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null))).isEqualTo(200);
    }

    private void complete(Staff agent, UUID session, UUID outcome) throws Exception {
        MvcResult result = call(post("/api/v1/sessions/" + session + "/complete"), agent.token(), "{\"outcome_code_id\":\"" + outcome + "\",\"note\":\"done\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private UUID visitor(String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, ?, ?, ?)", id, "V-" + id, "A visitor", category, ts(Instant.now()));
        return id;
    }

    private UUID appointment(UUID service, UUID visitor, UUID agent, String source, String state, Instant bookedAt, LocalDate slotDate, LocalTime slotStart, Instant checkedInAt, Integer varianceSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, preferred_agent_id, visitor_id, slot_date, slot_start, slot_end, state, source, created_at, updated_at, checked_in_at, checkin_variance_seconds)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "APT-" + id.toString().substring(0, 8), service, agent, visitor, slotDate, slotStart, slotStart.plusMinutes(30), state, source, ts(bookedAt), ts(bookedAt), ts(checkedInAt), varianceSeconds);
        return id;
    }

    private UUID visit(UUID site, Instant startedAt, Instant endedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at, ended_at) VALUES (?, ?, ?, ?)", id, site, ts(startedAt), ts(endedAt));
        return id;
    }

    private void journeyStop(UUID visit, UUID service, int seq, UUID ticket) {
        jdbc.update("INSERT INTO journey_stop (id, visit_id, service_id, seq, ticket_id) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), visit, service, seq, ticket);
    }

    private UUID feedback(UUID ticket, int rating, String comment, boolean approved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback (id, ticket_id, rating, comment, comment_approved_at, submitted_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, ticket, rating, comment, approved ? ts(Instant.now()) : null, ts(Instant.now()));
        return id;
    }

    private UUID notification(UUID site, UUID service, String triggerKey, String channel, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notification_message (id, trigger_key, channel, channel_order, language, site_id, service_id, status, created_at, sent_at)"
                        + " VALUES (?, ?, ?, '[\"" + channel + "\"]'::jsonb, 'en', ?, ?, ?, ?, ?)",
                id, triggerKey, channel, site, service, status, ts(Instant.now()), "sent".equals(status) ? ts(Instant.now()) : null);
        return id;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult run(Staff who, String key, String json) throws Exception {
        return call(post("/api/v1/reports/" + key + "/run"), who == null ? null : who.token(), json);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    // ---- appointment report: FR-APT-043 and the appointment-adherence KPI ticket 50 left for this ticket -----------

    @Test
    void appointmentReportListsBookingsWithPunctualityLeadTimeAndComputesNoShowRateAndAdherence() throws Exception {
        World w = world();
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID vip = visitor("vip");
        UUID regular = visitor("regular");

        LocalDate slotDate = LocalDate.of(2026, 9, 20);
        LocalTime slotStart = LocalTime.of(9, 0);
        Instant bookedAt = Instant.parse("2026-09-18T09:00:00Z");
        // Slot at UTC (site timezone is UTC): 2026-09-20T09:00:00Z. Booked two days earlier -> 172800s lead time.
        Instant checkedInAt = Instant.parse("2026-09-20T09:05:00Z");
        appointment(w.service(), vip, agent.id(), "phone", "converted", bookedAt, slotDate, slotStart, checkedInAt, 300);
        appointment(w.service(), regular, agent.id(), "walk_in", "no_show", bookedAt, slotDate, slotStart, null, null);

        Instant from = Instant.parse("2026-09-20T00:00:00Z");
        Instant to = Instant.parse("2026-09-21T00:00:00Z");
        MvcResult result = run(admin, "appointment", "{\"site_id\":\"" + w.site() + "\", \"from\":\"" + from + "\", \"to\":\"" + to + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);

        assertThat(((Number) field(result, "$.total_rows")).longValue()).isEqualTo(2);
        List<Map<String, Object>> rows = field(result, "$.rows");
        Map<String, Object> shown = rows.stream().filter(r -> "converted".equals(r.get("state"))).findFirst().orElseThrow();
        assertThat(((Number) shown.get("checkin_variance_seconds")).intValue()).isEqualTo(300);
        assertThat(((Number) shown.get("lead_time_seconds")).longValue()).isEqualTo(172800L);
        assertThat((Boolean) shown.get("no_show")).isFalse();
        Map<String, Object> noShow = rows.stream().filter(r -> "no_show".equals(r.get("state"))).findFirst().orElseThrow();
        assertThat((Boolean) noShow.get("no_show")).isTrue();

        List<Map<String, Object>> byService = field(result, "$.extra.no_show_rate_by_service");
        assertThat(byService).hasSize(1);
        assertThat(((Number) byService.get(0).get("total")).longValue()).isEqualTo(2);
        assertThat(((Number) byService.get(0).get("no_show")).longValue()).isEqualTo(1);
        assertThat(((Number) byService.get(0).get("no_show_rate_pct")).doubleValue()).isEqualTo(50.0);

        List<Map<String, Object>> byAgent = field(result, "$.extra.no_show_rate_by_agent");
        assertThat(((Number) byAgent.get(0).get("total")).longValue()).isEqualTo(2);

        List<Map<String, Object>> byCategory = field(result, "$.extra.no_show_rate_by_visitor_category");
        Map<String, Object> vipRow = byCategory.stream().filter(r -> "vip".equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) vipRow.get("no_show_rate_pct")).doubleValue()).isEqualTo(0.0);
        Map<String, Object> regularRow = byCategory.stream().filter(r -> "regular".equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) regularRow.get("no_show_rate_pct")).doubleValue()).isEqualTo(100.0);

        Map<String, Object> adherence = field(result, "$.extra.adherence");
        assertThat(((Number) adherence.get("resolved")).longValue()).isEqualTo(2);
        assertThat(((Number) adherence.get("shown")).longValue()).isEqualTo(1);
        assertThat(((Number) adherence.get("adherence_pct")).doubleValue()).isEqualTo(50.0);
    }

    // ---- journey report: FR-QUE-064 and the journey-completion KPI ticket 50 left for this ticket -------------------

    @Test
    void journeyReportComputesStopsPlannedCompletedTotalTimeOnSiteAndAggregatesCompletionAndPerStopWait() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID session = openSession(agent, counterId);

        UUID visitId = visit(w.site(), BASE, BASE.plusSeconds(900));

        clock.set(BASE);
        UUID ticket1 = issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(100));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        clock.set(BASE.plusSeconds(100));
        UUID ticket2 = issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(400));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        journeyStop(visitId, w.service(), 1, ticket1);
        journeyStop(visitId, w.service(), 2, ticket2);

        scheduler.tick();

        MvcResult result = run(admin, "journey", "{\"site_id\":\"" + w.site() + "\", \"from\":\"" + BASE.minusSeconds(60) + "\", \"to\":\"" + BASE.plusSeconds(3600) + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);

        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("stops_planned")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("stops_completed")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("total_time_on_site_seconds")).longValue()).isEqualTo(900);

        Map<String, Object> extra = field(result, "$.extra");
        assertThat(((Number) extra.get("completion_rate_pct")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) extra.get("avg_stop_wait_seconds")).doubleValue()).isEqualTo(200.0);
    }

    // ---- feedback report (§16.1) -------------------------------------------------------------------------------

    @Test
    void feedbackReportListsRatingCommentAgentServiceAndComputesAverageRating() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID session = openSession(agent, counterId);

        UUID ticket1 = issue(w.service(), Channels.RECEPTION);
        callAndServe(agent, session);
        complete(agent, session, outcome);
        UUID ticket2 = issue(w.service(), Channels.RECEPTION);
        callAndServe(agent, session);
        complete(agent, session, outcome);

        feedback(ticket1, 4, "Great service", false);
        feedback(ticket2, 2, null, false);

        MvcResult result = run(admin, "feedback", "{\"site_id\":\"" + w.site() + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(((Number) field(result, "$.total_rows")).longValue()).isEqualTo(2);
        List<Map<String, Object>> rows = field(result, "$.rows");
        Map<String, Object> withComment = rows.stream().filter(r -> "Great service".equals(r.get("comment"))).findFirst().orElseThrow();
        assertThat(((Number) withComment.get("rating")).intValue()).isEqualTo(4);
        assertThat((Boolean) withComment.get("comment_approved")).isFalse();
        assertThat(withComment.get("agent_name")).isNotNull();

        Map<String, Object> extra = field(result, "$.extra");
        assertThat(((Number) extra.get("avg_rating")).doubleValue()).isEqualTo(3.0);
        assertThat(((Number) extra.get("count")).longValue()).isEqualTo(2);
    }

    // ---- notification report (§16.1) ---------------------------------------------------------------------------

    @Test
    void notificationReportListsMessagesWithCostIndicator() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        notification(w.site(), w.service(), "ticket.called", "email", "sent");
        notification(w.site(), w.service(), "ticket.called", "in_app", "queued");

        MvcResult result = run(admin, "notification", "{\"site_id\":\"" + w.site() + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).hasSize(2);
        Map<String, Object> emailRow = rows.stream().filter(r -> "email".equals(r.get("channel"))).findFirst().orElseThrow();
        assertThat(emailRow.get("cost_indicator")).isEqualTo("low");
        assertThat(emailRow.get("status")).isEqualTo("sent");
        Map<String, Object> inAppRow = rows.stream().filter(r -> "in_app".equals(r.get("channel"))).findFirst().orElseThrow();
        assertThat(inAppRow.get("cost_indicator")).isEqualTo("free");
    }

    // ---- audit report: reuses AuditQueryService, so a stricter AUDIT_READ gate applies on top of REPORTS_RUN_EXPORT -

    @Test
    void auditReportRequiresAuditReadInAdditionToReportsRunExport() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff teamAdmin = staff(Role.TEAM_ADMIN, w.site(), w.group());

        MvcResult asOrgAdmin = run(orgAdmin, "audit", "{}");
        assertThat(status(asOrgAdmin)).as(body(asOrgAdmin)).isEqualTo(200);
        assertThat((String) field(asOrgAdmin, "$.key")).isEqualTo("audit");

        MvcResult asTeamAdmin = run(teamAdmin, "audit", "{}");
        assertThat(status(asTeamAdmin)).as("Team Admin holds reports:run_export but not audit:read").isEqualTo(403);
    }

    // ---- unknown key and no-token/out-of-scope behaviour match the rest of the catalogue --------------------------

    @Test
    void permissionScopeAndUnknownKeyMatchTheRestOfTheCatalogue() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff agent = staff(Role.AGENT, w.site(), w.group());

        for (String key : List.of("appointment", "journey", "feedback", "notification")) {
            assertThat(status(run(orgAdmin, key, "{\"site_id\":\"" + w.site() + "\"}"))).as(key).isEqualTo(200);
            assertThat(status(run(agent, key, "{\"site_id\":\"" + w.site() + "\"}"))).as(key + ": an Agent's own reach does not cover running reports").isEqualTo(403);
            assertThat(status(run(null, key, "{}"))).as(key + ": no token").isEqualTo(401);
        }

        World other = world();
        assertThat(status(run(orgAdmin, "appointment", "{\"site_id\":\"" + other.site() + "\"}")))
                .as("another Site outside the caller's own scope").isEqualTo(403);
        assertThat(status(run(orgAdmin, "no-such-report", "{}"))).as("an unknown report key").isEqualTo(404);
    }
}
