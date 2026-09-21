package com.qms.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import java.time.Duration;
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
 * Ticket 47 against real PostgreSQL (SRS §15.4): per-Service thresholds (FR-MON-020), a breach raising one alert and
 * firing the mapped staff-alert trigger (FR-MON-021, §14.2), repeated breaches grouping into it rather than a new
 * one (FR-MON-023), acknowledging with a note (FR-MON-022), and escalating an unacknowledged one past its configured
 * delay (FR-MON-021).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ThresholdAlertIT.Clocks.class})
class ThresholdAlertIT {

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
        registry.add("qms.alerts.threshold-check-cron", () -> "-");
        registry.add("qms.alerts.escalation-check-cron", () -> "-");
        registry.add("qms.dashboard.refresh-cron", () -> "-");
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-alerts");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired ThresholdAlertScheduler thresholdScheduler;
    @Autowired AlertEscalationScheduler escalationScheduler;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "T-" + site.toString().substring(0, 8));
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
        return new World(site, zone, group, service);
    }

    /** Idempotent: DB state persists across test methods in this class (no per-test rollback), so a second test that
     * needs the same trigger's template must not collide with the first's own row. */
    private void seedTemplate(String triggerKey) {
        jdbc.update(
                "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body) VALUES (?, ?, 'staff_alert', 'en', NULL, 'Alert: {{service_group_name}}')"
                        + " ON CONFLICT (trigger_key, channel, language) DO NOTHING",
                UUID.randomUUID(), triggerKey);
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
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private void issue(UUID service) {
        issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
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

    // ---- FR-MON-020: thresholds ------------------------------------------------------------------------------

    @Test
    void thresholdsRoundTripAreScopedAndValidated() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff agent = staff(Role.AGENT, w.site(), w.group());

        MvcResult empty = call(get("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(), null);
        assertThat(status(empty)).as(body(empty)).isEqualTo(200);
        Integer unset = field(empty, "$.queue_length_max");
        assertThat(unset).as("unmonitored until configured").isNull();

        MvcResult set = call(
                put("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(),
                "{\"queue_length_max\":0,\"no_show_rate_percent_max\":20,\"escalation_delay_minutes\":15,\"group_window_minutes\":10}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        assertThat((Integer) field(set, "$.queue_length_max")).isEqualTo(0);

        MvcResult reread = call(get("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(), null);
        assertThat((Integer) field(reread, "$.queue_length_max")).isEqualTo(0);

        assertThat(status(call(put("/api/v1/services/" + w.service() + "/alert-thresholds"), agent.token(), "{\"queue_length_max\":5}")))
                .as("an Agent's own reach does not cover configuring thresholds").isEqualTo(403);
        assertThat(status(call(put("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(), "{\"queue_length_max\":-1}")))
                .as("negative threshold").isEqualTo(400);

        World other = world();
        Staff elsewhere = staff(Role.ORG_ADMIN, other.site(), null);
        assertThat(status(call(get("/api/v1/services/" + w.service() + "/alert-thresholds"), elsewhere.token(), null)))
                .as("another Site's Service").isEqualTo(403);
    }

    // ---- FR-MON-021, FR-MON-023: breach, notify, group ------------------------------------------------------

    @Test
    void aBreachRaisesOneAlertAndFiresTheStaffAlertTriggerAndRepeatsAreGrouped() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        seedTemplate("queue_sla_breach");
        assertThat(status(call(put("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(), "{\"queue_length_max\":0}"))).isEqualTo(200);

        issue(w.service());
        thresholdScheduler.tick();

        MvcResult list = call(get("/api/v1/sites/" + w.site() + "/alerts"), orgAdmin.token(), null);
        assertThat(status(list)).as(body(list)).isEqualTo(200);
        assertThat((List<String>) field(list, "$.items[*].threshold_type")).containsExactly("queue_length");
        assertThat((String) field(list, "$.items[0].state")).isEqualTo("open");
        assertThat((Integer) field(list, "$.items[0].breach_count")).isEqualTo(1);
        String alertId = field(list, "$.items[0].id");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE trigger_key = 'queue_sla_breach' AND site_id = ?", Integer.class, w.site())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'alert.raised' AND entity_id = ?::uuid", Integer.class, alertId)).isEqualTo(1);

        // A second breach while the first is still open and within its grouping window bumps the same alert
        // (FR-MON-023): no second alert, no second notification.
        issue(w.service());
        thresholdScheduler.tick();

        MvcResult regrouped = call(get("/api/v1/sites/" + w.site() + "/alerts"), orgAdmin.token(), null);
        assertThat((List<String>) field(regrouped, "$.items[*].id")).as("still one alert").containsExactly(alertId);
        assertThat((Integer) field(regrouped, "$.items[0].breach_count")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE trigger_key = 'queue_sla_breach' AND site_id = ?", Integer.class, w.site()))
                .as("grouped, not repeated").isEqualTo(1);

        // ---- FR-MON-022: acknowledge with a note --------------------------------------------------------------
        MvcResult acked = call(post("/api/v1/alerts/" + alertId + "/acknowledge"), orgAdmin.token(), "{\"note\":\"Opening a second counter\"}");
        assertThat(status(acked)).as(body(acked)).isEqualTo(200);
        assertThat((String) field(acked, "$.state")).isEqualTo("acknowledged");
        assertThat((String) field(acked, "$.acknowledgement_note")).isEqualTo("Opening a second counter");
        assertThat((String) field(acked, "$.acknowledged_by")).isEqualTo(orgAdmin.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'alert.acknowledged' AND entity_id = ?::uuid", Integer.class, alertId)).isEqualTo(1);

        // A fresh breach once acknowledged starts a new alert, not the same one (FR-MON-023 only groups repeats of
        // a still-open alert).
        issue(w.service());
        thresholdScheduler.tick();
        MvcResult afterAck = call(get("/api/v1/sites/" + w.site() + "/alerts?state=open"), orgAdmin.token(), null);
        assertThat((List<String>) field(afterAck, "$.items[*].id")).as("a new alert, distinct from the acknowledged one").doesNotContain(alertId);

        Staff agent = staff(Role.AGENT, w.site(), w.group());
        assertThat(status(call(post("/api/v1/alerts/" + alertId + "/acknowledge"), agent.token(), null))).as("an Agent may not acknowledge").isEqualTo(403);
    }

    // ---- FR-MON-021: escalation after a configurable delay --------------------------------------------------

    @Test
    void anUnacknowledgedAlertEscalatesAfterItsConfiguredDelay() throws Exception {
        World w = world();
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        seedTemplate("queue_sla_breach");
        assertThat(status(call(
                        put("/api/v1/services/" + w.service() + "/alert-thresholds"), orgAdmin.token(), "{\"queue_length_max\":0,\"escalation_delay_minutes\":15}")))
                .isEqualTo(200);

        issue(w.service());
        thresholdScheduler.tick();
        String alertId = field(call(get("/api/v1/sites/" + w.site() + "/alerts"), orgAdmin.token(), null), "$.items[0].id");

        escalationScheduler.tick();
        assertThat(jdbc.queryForObject("SELECT escalated_at FROM alert WHERE id = ?::uuid", java.sql.Timestamp.class, alertId))
                .as("too early").isNull();

        clock.set(BASE.plus(Duration.ofMinutes(16)));
        escalationScheduler.tick();
        assertThat(jdbc.queryForObject("SELECT escalated_at FROM alert WHERE id = ?::uuid", java.sql.Timestamp.class, alertId))
                .as("past its own 15-minute delay").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'alert.escalated' AND entity_id = ?::uuid", Integer.class, alertId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE trigger_key = 'queue_sla_breach' AND site_id = ?", Integer.class, w.site()))
                .as("escalation fires a second, concrete nudge").isEqualTo(2);

        // Escalating again does nothing further (escalated_at is set once).
        clock.set(BASE.plus(Duration.ofMinutes(45)));
        escalationScheduler.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'alert.escalated' AND entity_id = ?::uuid", Integer.class, alertId)).isEqualTo(1);
    }
}
