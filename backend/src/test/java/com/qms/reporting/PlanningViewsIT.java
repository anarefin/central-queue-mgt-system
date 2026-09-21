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
 * Ticket 51 against real PostgreSQL (SRS §16.2): the {@code peak-hours} (FR-RPT-011) and {@code staffing-gap}
 * (FR-RPT-012) planning views under {@code POST /reports/{key}/run}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, PlanningViewsIT.Clocks.class})
class PlanningViewsIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    // A Wednesday (ISO day-of-week 3), 08:00 UTC.
    static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

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
            return Files.createTempDirectory("qms-keys-planning-views");
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

    // ---- peak-hours: volume by hour-of-day x ISO day-of-week (FR-RPT-011) ------------------------------------------

    @Test
    void peakHoursShowsTicketVolumeByHourOfDayAndDayOfWeek() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        // BASE is Wednesday (ISO 3) 08:00 UTC: two tickets there, one on Thursday (ISO 4) 14:00 UTC.
        clock.set(BASE);
        issue(w.service(), Channels.RECEPTION);
        issue(w.service(), Channels.RECEPTION);
        clock.set(Instant.parse("2026-09-24T14:00:00Z"));
        issue(w.service(), Channels.RECEPTION);

        scheduler.tick();

        MvcResult result = run(admin, "peak-hours", range(w.site(), BASE.minusSeconds(3600), BASE.plusSeconds(3 * 24 * 3600)));
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> cells = field(result, "$.cells");

        Map<String, Object> wednesdayEight = cells.stream()
                .filter(c -> ((Number) c.get("day_of_week")).intValue() == 3 && ((Number) c.get("hour_of_day")).intValue() == 8)
                .findFirst().orElseThrow();
        assertThat(((Number) wednesdayEight.get("ticket_count")).longValue()).isEqualTo(2);

        Map<String, Object> thursdayFourteen = cells.stream()
                .filter(c -> ((Number) c.get("day_of_week")).intValue() == 4 && ((Number) c.get("hour_of_day")).intValue() == 14)
                .findFirst().orElseThrow();
        assertThat(((Number) thursdayFourteen.get("ticket_count")).longValue()).isEqualTo(1);
    }

    // ---- staffing-gap: tickets offered vs counter-hours available vs SLA attainment per hour band (FR-RPT-012) ------

    @Test
    void staffingGapShowsTicketsOfferedCounterHoursAndSlaAttainmentPerHourBand() throws Exception {
        World w = world();
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        UUID counterId = counter(w.zone(), w.service(), "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        clock.set(BASE);
        issue(w.service(), Channels.RECEPTION);
        UUID session = openSession(agent, counterId);
        clock.set(BASE.plusSeconds(100));
        callAndServe(agent, session);
        complete(agent, session, outcome);
        clock.set(BASE.plusSeconds(1800));
        assertThat(status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/sessions/" + session), agent.token(), null))).isEqualTo(200);

        scheduler.tick();

        MvcResult result = run(admin, "staffing-gap", range(w.site(), BASE.minusSeconds(60), BASE.plusSeconds(3600)));
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<Map<String, Object>> rows = field(result, "$.rows");
        assertThat(rows).hasSize(24);

        Map<String, Object> hourEight = rows.stream().filter(r -> ((Number) r.get("hour_of_day")).intValue() == 8).findFirst().orElseThrow();
        assertThat(((Number) hourEight.get("tickets_offered")).longValue()).isEqualTo(1);
        assertThat(((Number) hourEight.get("counter_hours_available")).doubleValue()).isEqualTo(0.5);
        assertThat(((Number) hourEight.get("sla_attainment_pct")).doubleValue()).isEqualTo(100.0);
    }

    // ---- validation and permission ------------------------------------------------------------------------------

    @Test
    void bothViewsRequireABoundedRangeAndFollowTheSamePermissionAndScopeRules() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff agent = staff(Role.AGENT, w.site(), w.group());

        for (String key : List.of("peak-hours", "staffing-gap")) {
            assertThat(status(run(orgAdmin, key, "{\"site_id\":\"" + w.site() + "\"}"))).as(key + ": no range").isEqualTo(400);
            assertThat(status(run(orgAdmin, key, range(w.site(), BASE, BASE.plusSeconds(3600))))).as(key).isEqualTo(200);
            assertThat(status(run(agent, key, range(w.site(), BASE, BASE.plusSeconds(3600))))).as(key + ": an Agent's own reach does not cover running reports").isEqualTo(403);
            assertThat(status(run(null, key, "{}"))).as(key + ": no token").isEqualTo(401);
        }
    }
}
