package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Ticket 50 against real PostgreSQL (SRS §16.1, §15.2, §15.3): the visitor flow, counter, agent, service, department
 * and site reports under {@code POST /reports/{key}/run}, each with a period-over-period comparison (FR-RPT-010,
 * FR-MON-011), percentiles computed from raw {@code reporting.ticket_fact} rows (FR-MON-010), and the existing break
 * report (ticket 16) reachable under the same catalogue key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, OperationalReportsIT.Clocks.class})
class OperationalReportsIT {

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
            return Files.createTempDirectory("qms-keys-operational-reports");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired com.qms.reporting.ReportingRefreshScheduler scheduler;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = NULL");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures (mirrors ReportingIT's own, ticket 48) -----------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
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

    private void startBreak(Staff agent, UUID session, UUID breakType) throws Exception {
        assertThat(status(call(post("/api/v1/sessions/" + session + "/break"), agent.token(), "{\"break_type_id\":\"" + breakType + "\"}"))).isEqualTo(200);
    }

    private void endBreak(Staff agent, UUID session) throws Exception {
        assertThat(status(call(post("/api/v1/sessions/" + session + "/break"), agent.token(), null))).isEqualTo(200);
    }

    private UUID breakType(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO break_type (id, name_i18n, created_at, updated_at) VALUES (?, ?::jsonb, now(), now())", id, "{\"en\":\"" + name + "\"}");
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

    private static String range(UUID siteId, Instant from, Instant to) {
        return "{\"site_id\":\"" + siteId + "\", \"from\":\"" + from + "\", \"to\":\"" + to + "\"}";
    }

    // ---- visitor flow: counts per bucket and comparison against the previous equivalent period (FR-RPT-010) ------

    @Test
    void visitorFlowCountsIssuedServedCancelledAndComparesAgainstThePreviousDay() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counter = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        Instant from = BASE.minusSeconds(3600);
        Instant to = BASE.plusSeconds(3600);
        // The previous equivalent period is [from - (to - from), from) = [BASE - 10800, BASE - 3600): one ticket
        // issued there, forming the previous period's own "issued" count. Cancelled immediately so it never sits in
        // the queue "call next" would otherwise reach when the current period's own tickets are called below.
        clock.set(BASE.minusSeconds(7200));
        UUID previousTicket = issue(w.service(), Channels.RECEPTION);
        assertThat(status(call(post("/api/v1/tickets/" + previousTicket + "/cancel"), admin.token(), "{}"))).isEqualTo(200);

        // The current period: two issued, one served, one cancelled. Resolved one at a time (rather than both
        // issued before either is touched) so "call next" is never left to break a tie between two tickets issued
        // at the exact same instant.
        clock.set(BASE);
        UUID served = issue(w.service(), Channels.RECEPTION);
        UUID session = openSession(agent, counter);
        callAndServe(agent, session);
        complete(agent, session, outcome);

        UUID cancelled = issue(w.service(), Channels.RECEPTION);
        assertThat(status(call(post("/api/v1/tickets/" + cancelled + "/cancel"), admin.token(), "{}"))).isEqualTo(200);

        scheduler.tick();
        MvcResult result = run(admin, "visitor-flow", "{\"site_id\":\"" + w.site() + "\", \"from\":\"" + from + "\", \"to\":\"" + to + "\", \"grain\":\"day\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);

        assertThat(((Number) field(result, "$.totals.issued")).longValue()).isEqualTo(2);
        assertThat(((Number) field(result, "$.totals.served")).longValue()).as(body(result)).isEqualTo(1);
        assertThat(((Number) field(result, "$.totals.cancelled")).longValue()).isEqualTo(1);
        assertThat(((Number) field(result, "$.previous_totals.issued")).longValue()).isEqualTo(1);
        assertThat(((Number) field(result, "$.change.issued.absolute")).longValue()).isEqualTo(1);
        assertThat(((Number) field(result, "$.change.issued.percent")).doubleValue()).isEqualTo(100.0);
        // §15.3's abandonment rate (cancelled or no-show ÷ issued) and channel mix, folded into visitor flow.
        assertThat(((Number) field(result, "$.totals.abandonment_rate_pct")).doubleValue()).isEqualTo(50.0);
        List<Map<String, Object>> channelMix = field(result, "$.extra.channel_mix");
        assertThat(channelMix).extracting(m -> m.get("channel")).contains("reception");
        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).isNotEmpty();
        assertThat(served).isNotNull();
    }

    @Test
    void visitorFlowRequiresABoundedRangeAndRejectsAnUnknownGrain() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        assertThat(status(run(admin, "visitor-flow", "{\"site_id\":\"" + w.site() + "\"}"))).as("no range").isEqualTo(400);
        assertThat(status(run(admin, "visitor-flow", "{\"site_id\":\"" + w.site() + "\", \"from\":\"" + BASE + "\", \"to\":\"" + BASE.plusSeconds(3600)
                + "\", \"grain\":\"fortnight\"}"))).as("unknown grain").isEqualTo(400);
    }

    // ---- counter report: sessions, open hours, served, idle, utilisation (§16.1) -----------------------------

    @Test
    void counterReportCountsSessionsOpenHoursServedAndUtilisation() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        issue(w.service(), Channels.RECEPTION);
        UUID session = openSession(agent, counterId);
        clock.set(BASE.plusSeconds(100));
        callAndServe(agent, session);
        clock.set(BASE.plusSeconds(400));
        complete(agent, session, outcome);
        clock.set(BASE.plusSeconds(1000));
        assertThat(status(call(delete("/api/v1/sessions/" + session), agent.token(), null))).isEqualTo(200);

        scheduler.tick();

        MvcResult result = run(admin, "counter", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("sessions")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("open_seconds")).longValue()).isEqualTo(1000);
        assertThat(((Number) row.get("served")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("serving_seconds")).longValue()).isEqualTo(300);
        assertThat(((Number) row.get("idle_seconds")).longValue()).isEqualTo(700);
        assertThat(((Number) row.get("utilisation_pct")).doubleValue()).isEqualTo(30.0);
    }

    // ---- agent report: §15.2's KPIs, including break time (FR-AGT-022) and successful token rate -----------------

    @Test
    void agentReportComputesServedCancelledWaitServiceTimeAndBreakTime() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        UUID lunch = breakType("Lunch");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        UUID served = issue(w.service(), Channels.RECEPTION);
        UUID session = openSession(agent, counterId);
        clock.set(BASE.plusSeconds(60));
        callAndServe(agent, session);
        clock.set(BASE.plusSeconds(160));
        complete(agent, session, outcome);

        UUID cancelled = issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(200));
        callAndServe(agent, session);
        assertThat(status(call(post("/api/v1/tickets/" + cancelled + "/cancel"), admin.token(), "{}"))).isEqualTo(200);

        clock.set(BASE.plusSeconds(300));
        startBreak(agent, session, lunch);
        clock.set(BASE.plusSeconds(600));
        endBreak(agent, session);

        scheduler.tick();

        MvcResult result = run(admin, "agent", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> rows = field(result, "$.rows");
        Map<String, Object> row = rows.stream().filter(r -> agent.id().toString().equals(r.get("agent_id"))).findFirst().orElseThrow();
        assertThat(((Number) row.get("services_served")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("avg_wait_seconds")).doubleValue()).isEqualTo(60.0);
        assertThat(((Number) row.get("avg_service_seconds")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) row.get("total_service_seconds")).longValue()).isEqualTo(100);
        assertThat(((Number) row.get("avg_break_seconds")).doubleValue()).isEqualTo(300.0);
        assertThat(served).isNotNull();
    }

    // ---- service report: volume, average and P90 wait, average handling time, SLA attainment (§16.1) ------------

    @Test
    void serviceReportComputesVolumeAverageAndP90WaitAndSlaAttainmentFromRawTicketsNotAveragedAverages() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID session = openSession(agent, counterId);

        // Three tickets with exact, distinct waits: 100s, 200s, 300s (sla_wait_minutes = 5 = 300s, so exactly the
        // third's wait sits right on the boundary and counts as within SLA).
        clock.set(BASE);
        issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(100));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        clock.set(BASE.plusSeconds(100));
        issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(300));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        clock.set(BASE.plusSeconds(300));
        issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(600));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        scheduler.tick();

        MvcResult result = run(admin, "service", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("volume")).longValue()).isEqualTo(3);
        assertThat(((Number) row.get("avg_wait_seconds")).doubleValue()).isEqualTo(200.0);
        // percentile_cont(0.9) over [100, 200, 300]: rank 0.9*(3-1)=1.8 -> 200 + 0.8*(300-200) = 280.
        assertThat(((Number) row.get("p90_wait_seconds")).doubleValue()).isCloseTo(280.0, within(0.01));
        assertThat(((Number) row.get("sla_attainment_pct")).doubleValue()).isEqualTo(100.0);

        List<Map<String, Object>> byHourBand = field(result, "$.extra.wait_by_hour_band");
        assertThat(byHourBand).isNotEmpty();
    }

    // ---- department and site reports roll the service report up one and two levels (§16.1) ----------------------

    @Test
    void departmentAndSiteReportsRollUpTheServiceReport() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID session = openSession(agent, counterId);

        issue(w.service(), Channels.RECEPTION);
        clock.set(BASE.plusSeconds(50));
        callAndServe(agent, session);
        complete(agent, session, outcome);

        scheduler.tick();

        MvcResult department = run(admin, "department", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(department)).as(body(department)).isEqualTo(200);
        assertThat(((Number) field(department, "$.rows[0].volume")).longValue()).isEqualTo(1);

        MvcResult site = run(admin, "site", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(site)).as(body(site)).isEqualTo(200);
        assertThat(((Number) field(site, "$.rows[0].volume")).longValue()).isEqualTo(1);
    }

    // ---- the pre-existing break report (ticket 16) is reachable under the same catalogue endpoint -----------------

    @Test
    void theBreakReportIsReachableUnderTheSameReportsRunEndpoint() throws Exception {
        World w = world();
        UUID counterId = counter(w.zone(), w.service(), "1");
        UUID lunch = breakType("Lunch");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        UUID session = openSession(agent, counterId);

        clock.set(BASE.plusSeconds(60));
        startBreak(agent, session, lunch);
        clock.set(BASE.plusSeconds(360));
        endBreak(agent, session);

        MvcResult result = run(admin, "break", "{\"from\":\"" + BASE + "\", \"to\":\"" + BASE.plusSeconds(3600) + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(((Number) field(result, "$.rows[0].total_seconds")).longValue()).isEqualTo(300);
        assertThat(field(result, "$.rows[0].agent_id").toString()).isEqualTo(agent.id().toString());
    }

    // ---- permission, scope and an unknown key --------------------------------------------------------------------

    @Test
    void permissionScopeAndUnknownKey() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff agent = staff(Role.AGENT, w.site(), w.group());

        String body = range(w.site(), BASE.minusSeconds(3600), BASE.plusSeconds(3600));
        for (String key : List.of("visitor-flow", "counter", "agent", "service", "department", "site")) {
            assertThat(status(run(orgAdmin, key, body))).as(key).isEqualTo(200);
            assertThat(status(run(agent, key, body))).as(key + ": an Agent's own reach does not cover running reports").isEqualTo(403);
            assertThat(status(run(null, key, body))).as(key + ": no token").isEqualTo(401);
        }

        World other = world();
        assertThat(status(run(orgAdmin, "service", range(other.site(), BASE.minusSeconds(3600), BASE.plusSeconds(3600)))))
                .as("another Site outside the caller's own scope").isEqualTo(403);

        assertThat(status(run(orgAdmin, "no-such-report", body))).as("an unknown report key").isEqualTo(404);
    }
}
