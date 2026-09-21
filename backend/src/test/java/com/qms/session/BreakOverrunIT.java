package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * A break exceeding its type's own maximum duration raises a dashboard alert to the Team Admin (SRS §11.3,
 * FR-AGT-023, ticket 47), against real PostgreSQL. The alert row itself belongs to {@code com.qms.dashboard} (the
 * threshold-alert domain); this test reads it back over plain SQL rather than that package's own classes, the same
 * arm's-length way {@link BreakOverrunScheduler} itself only ever reaches it through {@code ThresholdAlertRaiser}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, BreakOverrunIT.Clocks.class})
class BreakOverrunIT {

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
        registry.add("qms.session.break-overrun-check-cron", () -> "-");
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-break-overrun");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired BreakOverrunScheduler scheduler;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private record World(UUID site, UUID zone, UUID group, UUID service, UUID counter) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "O-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                service, group);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Desk 1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new World(site, zone, group, service, counter);
    }

    private Staff staff(Role role, UUID site, UUID teamOf) throws Exception {
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
        // Tokens are verified against the real wall clock, not this test's own; mint it there, then restore.
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            MvcResult result = call(post("/api/v1/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private UUID newBreakType(int maxMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO break_type (id, name_i18n, max_minutes, active, created_at, updated_at) VALUES (?, '{\"en\":\"Lunch\"}'::jsonb, ?, true, now(), now())",
                id, maxMinutes);
        return id;
    }

    private UUID openSession(Staff agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
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

    @Test
    void aBreakPastItsTypesMaximumRaisesAnAlertGroupedOnRepeatSweeps() throws Exception {
        World w = world();
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        UUID session = openSession(agent, w.counter());
        UUID breakType = newBreakType(1);

        assertThat(status(call(post("/api/v1/sessions/" + session + "/break"), agent.token(), "{\"break_type_id\":\"" + breakType + "\"}"))).isEqualTo(200);

        scheduler.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert WHERE threshold_type = 'break_overrun'", Integer.class))
                .as("still within its 1-minute maximum").isZero();

        clock.set(BASE.plus(Duration.ofMinutes(2)));
        scheduler.tick();

        Integer alerts = jdbc.queryForObject("SELECT count(*) FROM alert WHERE threshold_type = 'break_overrun' AND subject_id = ?", Integer.class, session);
        assertThat(alerts).as("one alert for this session's own overrunning break").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT site_id FROM alert WHERE threshold_type = 'break_overrun' AND subject_id = ?", UUID.class, session))
                .isEqualTo(w.site());
        assertThat(jdbc.queryForObject("SELECT service_id FROM alert WHERE threshold_type = 'break_overrun' AND subject_id = ?", UUID.class, session))
                .as("a break belongs to an Agent's session, not a Service").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE trigger_key = 'agent_break_overrun'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'alert.raised' AND entity = 'alert'", Integer.class)).isEqualTo(1);

        // Still overrunning on the next sweep: grouped into the same alert (FR-MON-023), not a second one.
        clock.set(BASE.plus(Duration.ofMinutes(3)));
        scheduler.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert WHERE threshold_type = 'break_overrun'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT breach_count FROM alert WHERE threshold_type = 'break_overrun' AND subject_id = ?", Integer.class, session))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE trigger_key = 'agent_break_overrun'", Integer.class)).isEqualTo(1);
    }
}
