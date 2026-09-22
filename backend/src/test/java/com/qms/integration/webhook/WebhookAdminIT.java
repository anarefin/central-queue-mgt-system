package com.qms.integration.webhook;

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
import java.util.List;
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
 * The admin surface against real PostgreSQL (ticket 57): creating an endpoint with a secret shown back exactly once
 * (FR-INT-020), updating it, activating/deactivating it, and the delivery log filterable by endpoint, event type and
 * status with a working replay (FR-INT-021). Every handler is permission-checked server-side (FR-CFG-103).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, WebhookAdminIT.Clocks.class})
class WebhookAdminIT {

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
        registry.add("qms.webhook.send-poll-cron", () -> "-");
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-webhook-admin");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDeliveryRepository deliveries;

    @BeforeEach
    void startAtBaseWithNoEndpointsLeftFromAnEarlierTest() {
        clock.set(BASE);
        // Endpoints and deliveries are organisation-wide (no Site to scope a fresh fixture by) and this class's
        // tests share one Postgres container with no rollback between them: an unfiltered listing (e.g. this suite's
        // own "?status=queued") would otherwise also see another test method's own leftover rows.
        jdbc.update("DELETE FROM webhook_delivery_attempt");
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_event");
        jdbc.update("DELETE FROM webhook_endpoint");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private String token(Role role) throws Exception {
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
                        ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
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

    /** {@code JsonPath.parse(...).read(...)} rather than the static {@code JsonPath.read(String, String)}
     * convenience overload, which has proven unreliable against this suite's own response bodies. */
    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.parse(body(result)).read(path);
    }

    // ---- FR-CFG-103: every handler is permission-checked server-side ---------------------------------------------

    @Test
    void anAgentMayNotCreateAnEndpoint() throws Exception {
        String agent = token(Role.AGENT);

        MvcResult result = call(
                post("/api/v1/webhook-endpoints"), agent, "{\"description\":\"x\",\"url\":\"https://203.0.113.10/hooks\",\"event_types\":[\"ticket.called\"]}");

        assertThat(status(result)).isEqualTo(403);
    }

    @Test
    void anAgentMayNotReadTheDeliveryLog() throws Exception {
        String agent = token(Role.AGENT);

        MvcResult result = call(get("/api/v1/webhook-deliveries"), agent, null);

        assertThat(status(result)).isEqualTo(403);
    }

    // ---- FR-INT-020: subscribing an endpoint to any §21.4 event type, with a secret --------------------------------

    @Test
    void anOrgAdminCreatesAnEndpointAndSeesTheSecretExactlyOnce() throws Exception {
        String admin = token(Role.ORG_ADMIN);

        MvcResult created = call(
                post("/api/v1/webhook-endpoints"), admin,
                "{\"description\":\"Billing system\",\"url\":\"https://203.0.113.10/hooks\",\"event_types\":[\"ticket.called\",\"ticket.completed\"]}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        String secret = field(created, "$.secret");
        assertThat(secret).isNotBlank();
        String id = field(created, "$.id");
        List<String> eventTypes = field(created, "$.event_types");
        assertThat(eventTypes).containsExactly("ticket.called", "ticket.completed");

        MvcResult read = call(get("/api/v1/webhook-endpoints/" + id), admin, null);
        assertThat(status(read)).as(body(read)).isEqualTo(200);
        // @JsonInclude(NON_NULL) drops the field entirely once it is null, so it is absent, not null (the same
        // convention issuance.TicketResponse.secret already uses and issuance.IssuanceIT already asserts this way).
        assertThat(body(read)).as("the secret cannot be read back").doesNotContain("\"secret\"").doesNotContain(secret);
    }

    @Test
    void anUnknownEventTypeIsRejected() throws Exception {
        String admin = token(Role.ORG_ADMIN);

        MvcResult result = call(
                post("/api/v1/webhook-endpoints"), admin, "{\"description\":\"x\",\"url\":\"https://203.0.113.10/hooks\",\"event_types\":[\"not.a.real.type\"]}");

        assertThat(status(result)).isEqualTo(400);
        assertThat(body(result)).contains("validation_failed");
    }

    @Test
    void anUnsafeUrlIsRejected() throws Exception {
        String admin = token(Role.ORG_ADMIN);

        MvcResult result = call(
                post("/api/v1/webhook-endpoints"), admin, "{\"description\":\"x\",\"url\":\"http://127.0.0.1/hooks\",\"event_types\":[\"ticket.called\"]}");

        assertThat(status(result)).isEqualTo(400);
    }

    @Test
    void anOrgAdminUpdatesAndThenDeactivatesAndReactivatesAnEndpoint() throws Exception {
        String admin = token(Role.ORG_ADMIN);
        UUID id = endpoints.insert("Old", "https://203.0.113.10/hooks", List.of("ticket.called"), "secret", UUID.randomUUID(), clock.instant());

        MvcResult updated = call(
                put("/api/v1/webhook-endpoints/" + id), admin, "{\"description\":\"New\",\"url\":\"https://203.0.113.20/hooks\",\"event_types\":[\"session.opened\"]}");
        assertThat(status(updated)).as(body(updated)).isEqualTo(200);
        String description = field(updated, "$.description");
        assertThat(description).isEqualTo("New");

        MvcResult deactivated = call(post("/api/v1/webhook-endpoints/" + id + "/deactivate"), admin, null);
        Boolean active = field(deactivated, "$.active");
        assertThat(active).isFalse();

        MvcResult activated = call(post("/api/v1/webhook-endpoints/" + id + "/activate"), admin, null);
        Boolean activeAgain = field(activated, "$.active");
        assertThat(activeAgain).isTrue();
    }

    @Test
    void rotatingTheSecretChangesItAndShowsItExactlyOnceAgain() throws Exception {
        String admin = token(Role.ORG_ADMIN);
        UUID id = endpoints.insert("X", "https://203.0.113.10/hooks", List.of("ticket.called"), "old-secret", UUID.randomUUID(), clock.instant());

        MvcResult rotated = call(post("/api/v1/webhook-endpoints/" + id + "/rotate-secret"), admin, null);

        assertThat(status(rotated)).as(body(rotated)).isEqualTo(200);
        String newSecret = field(rotated, "$.secret");
        assertThat(newSecret).isNotBlank().isNotEqualTo("old-secret");
    }

    // ---- FR-INT-021: the delivery log, filterable, and replay ------------------------------------------------------

    @Test
    void theDeliveryLogIsFilterableByEndpointEventTypeAndStatus() throws Exception {
        UUID endpointId = endpoints.insert("X", "https://203.0.113.10/hooks", List.of("ticket.called"), "secret", UUID.randomUUID(), clock.instant());
        UUID eventId = deliveries.recordEvent("k1", "ticket.called", clock.instant(), java.util.Map.of("ticket_id", "t1"), clock.instant()).orElseThrow();
        UUID deliveryId = deliveries.queueDelivery(eventId, endpointId, "ticket.called", clock.instant());

        String admin = token(Role.ORG_ADMIN);
        MvcResult byEndpoint = call(get("/api/v1/webhook-deliveries?endpoint_id=" + endpointId), admin, null);
        MvcResult byType = call(get("/api/v1/webhook-deliveries?event_type=ticket.completed"), admin, null);
        MvcResult byStatus = call(get("/api/v1/webhook-deliveries?status=queued"), admin, null);

        List<?> byEndpointItems = field(byEndpoint, "$.items");
        List<?> byTypeItems = field(byType, "$.items");
        List<?> byStatusItems = field(byStatus, "$.items");
        assertThat(byEndpointItems).hasSize(1);
        assertThat(byTypeItems).isEmpty();
        assertThat(byStatusItems).hasSize(1);
        String deliveredId = field(byEndpoint, "$.items[0].delivery.id");
        assertThat(deliveredId).isEqualTo(deliveryId.toString());
    }

    @Test
    void replayingAFailedDeliveryQueuesItAgainAndIsAudited() throws Exception {
        UUID endpointId = endpoints.insert("X", "https://203.0.113.10/hooks", List.of("ticket.called"), "secret", UUID.randomUUID(), clock.instant());
        UUID eventId = deliveries.recordEvent("k2", "ticket.called", clock.instant(), java.util.Map.of("ticket_id", "t1"), clock.instant()).orElseThrow();
        UUID deliveryId = deliveries.queueDelivery(eventId, endpointId, "ticket.called", clock.instant());
        deliveries.markFailed(deliveryId, 6, "http_500");

        String admin = token(Role.ORG_ADMIN);
        MvcResult replayed = call(post("/api/v1/webhook-deliveries/" + deliveryId + "/replay"), admin, null);

        assertThat(status(replayed)).as(body(replayed)).isEqualTo(200);
        String replayedStatus = field(replayed, "$.delivery.status");
        assertThat(replayedStatus).isEqualTo("queued");
        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'webhook_delivery.replayed' AND entity_id = ?", Integer.class, deliveryId);
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void anAgentMayNotReplayADelivery() throws Exception {
        UUID endpointId = endpoints.insert("X", "https://203.0.113.10/hooks", List.of("ticket.called"), "secret", UUID.randomUUID(), clock.instant());
        UUID eventId = deliveries.recordEvent("k3", "ticket.called", clock.instant(), java.util.Map.of("ticket_id", "t1"), clock.instant()).orElseThrow();
        UUID deliveryId = deliveries.queueDelivery(eventId, endpointId, "ticket.called", clock.instant());

        String agent = token(Role.AGENT);
        MvcResult result = call(post("/api/v1/webhook-deliveries/" + deliveryId + "/replay"), agent, null);

        assertThat(status(result)).isEqualTo(403);
    }
}
