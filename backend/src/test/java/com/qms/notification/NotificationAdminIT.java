package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The admin surface against real PostgreSQL (ticket 38): permission-checked trigger settings (FR-NTF-010),
 * templates with unknown-variable rejection at save (FR-NTF-020, FR-NTF-021) and the delivery log filterable by
 * ticket, visitor and status (FR-NTF-032). Every handler is permission-checked server-side (FR-CFG-103).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, NotificationAdminIT.Clocks.class})
class NotificationAdminIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-21T04:00:00Z");

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
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-notification-admin");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newTicketWithVisitor(UUID site) {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'GA')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group);
        UUID visitor = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, 'Karim', 'general', now())", visitor, "V-" + visitor.toString().substring(0, 8));
        UUID visit = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", visit, site);
        UUID ticket = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash, visitor_id)"
                        + " VALUES (?, 'A-001', 1, 'daily', ?, ?, ?, ?, 'reception', 'waiting', now(), now(), 'hash', ?)",
                ticket, service, group, site, visit, visitor);
        return ticket;
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private String token(Role role, UUID... sites) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = createUser(role.wire());
            jdbc.update(
                    connection -> {
                        var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, user);
                        ps.setString(3, role.wire());
                        ps.setArray(4, connection.createArrayOf("uuid", sites));
                        ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                        return ps;
                    });
            String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ---- FR-CFG-103: every handler is permission-checked server-side ---------------------------------------------

    @Test
    void anAgentMayNotReadTheTriggerCatalogue() throws Exception {
        UUID site = newSite();
        String agent = token(Role.AGENT, site);

        MvcResult result = call(get("/api/v1/notification-triggers?site_id=" + site), agent, null);

        assertThat(status(result)).isEqualTo(403);
    }

    @Test
    void anOrgAdminMayReadTheTriggerCatalogue() throws Exception {
        UUID site = newSite();
        String admin = token(Role.ORG_ADMIN, site);

        MvcResult result = call(get("/api/v1/notification-triggers?site_id=" + site), admin, null);

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        // Every catalogue trigger is listed, including ones with no caller wired yet (SRS §14.2 has 15).
        assertThat(((java.util.List<?>) JsonPath.read(body(result), "$.items"))).hasSize(NotificationTriggerKey.values().length);
    }

    // ---- FR-NTF-010: enabling/disabling a trigger per Site ---------------------------------------------------------

    @Test
    void anOrgAdminCanDisableATriggerForASite() throws Exception {
        UUID site = newSite();
        String admin = token(Role.ORG_ADMIN, site);

        MvcResult result = call(
                put("/api/v1/notification-triggers/your_turn?site_id=" + site), admin, "{\"enabled\":false,\"channel_order\":[\"in_app\"]}");

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(result), "$.enabled")).isFalse();
        Boolean enabled = jdbc.queryForObject("SELECT enabled FROM notification_trigger_setting WHERE site_id = ? AND trigger_key = 'your_turn'", Boolean.class, site);
        assertThat(enabled).isFalse();
    }

    // ---- FR-NTF-020, FR-NTF-021: templates, with unknown-variable rejection at save --------------------------------

    @Test
    void savingATemplateWithAnUnknownVariableIsRejected() throws Exception {
        String admin = token(Role.ORG_ADMIN);

        MvcResult result = call(
                put("/api/v1/notification-templates/your_turn/in_app/en"), admin, "{\"body\":\"Hello {{not_a_real_variable}}\"}");

        assertThat(status(result)).isEqualTo(400);
        assertThat(body(result)).contains("validation_failed");
    }

    @Test
    void savingAndPreviewingATemplateWithKnownVariables() throws Exception {
        String admin = token(Role.ORG_ADMIN);
        MvcResult save = call(
                put("/api/v1/notification-templates/your_turn/in_app/en"), admin, "{\"body\":\"Token {{token_number}} at {{counter_label}}\"}");
        assertThat(status(save)).as(body(save)).isEqualTo(200);

        MvcResult preview = call(get("/api/v1/notification-templates/your_turn/in_app/en/preview"), admin, null);

        assertThat(status(preview)).as(body(preview)).isEqualTo(200);
        String previewBody = JsonPath.read(body(preview), "$.body");
        assertThat(previewBody).doesNotContain("{{").contains("Token");
    }

    // ---- FR-NTF-032: the delivery log, filterable by ticket, visitor and status ------------------------------------

    @Test
    void theDeliveryLogIsFilterableByTicketVisitorAndStatus() throws Exception {
        UUID site = newSite();
        UUID ticket = newTicketWithVisitor(site);
        UUID visitorId = jdbc.queryForObject("SELECT visitor_id FROM ticket WHERE id = ?", UUID.class, ticket);
        jdbc.update(
                "INSERT INTO notification_message (id, trigger_key, channel, channel_order, channel_index, language, urgent, site_id, ticket_id, visitor_id,"
                        + " variables, rendered_body, status, created_at)"
                        + " VALUES (?, 'your_turn', 'in_app', '[\"in_app\"]'::jsonb, 0, 'en', true, ?, ?, ?, '{}'::jsonb, 'Body', 'sent', now())",
                UUID.randomUUID(), site, ticket, visitorId);

        String admin = token(Role.ORG_ADMIN, site);
        MvcResult byTicket = call(get("/api/v1/notification-messages?ticket_id=" + ticket), admin, null);
        MvcResult byVisitor = call(get("/api/v1/notification-messages?visitor_id=" + visitorId + "&status=sent"), admin, null);
        MvcResult wrongStatus = call(get("/api/v1/notification-messages?ticket_id=" + ticket + "&status=failed"), admin, null);

        assertThat(((java.util.List<?>) JsonPath.read(body(byTicket), "$.items"))).hasSize(1);
        assertThat(((java.util.List<?>) JsonPath.read(body(byVisitor), "$.items"))).hasSize(1);
        assertThat(((java.util.List<?>) JsonPath.read(body(wrongStatus), "$.items"))).isEmpty();
    }

    @Test
    void anAgentMayNotReadTheDeliveryLog() throws Exception {
        UUID site = newSite();
        String agent = token(Role.AGENT, site);

        MvcResult result = call(get("/api/v1/notification-messages"), agent, null);

        assertThat(status(result)).isEqualTo(403);
    }
}
