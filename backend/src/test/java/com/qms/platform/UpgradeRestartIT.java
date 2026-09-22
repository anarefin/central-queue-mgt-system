package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.QmsApplication;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-OPS-021, NFR-AVL-002 (§26.3): an upgrade is a migration step followed by a fresh application start (FR-OPS-020,
 * as {@code deploy/compose.yaml}'s own {@code migrate} then {@code backend} services already sequence it, and
 * {@code TwoNodeRealtimeIT} already proves for a second node). This test builds a waiting Ticket and an open Counter
 * Session against one backend node, stops that node entirely (simulating the old version going down for an
 * upgrade), starts a brand new node against the same database with migrations already applied (simulating the new
 * version starting), and shows both are intact and immediately usable: the Agent's open session survives the
 * restart, and calling "next" on it reaches the exact ticket that was waiting before the restart. Nothing here lived
 * only in the old process's memory (ADR-0010): every fact a restart must not lose is a row in PostgreSQL already.
 */
@Testcontainers
class UpgradeRestartIT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    private final HttpClient http = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext start(Path keyDir, boolean migrate) {
        return new SpringApplicationBuilder(QmsApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.flyway.enabled=" + migrate,
                        "--qms.security.key-dir=" + keyDir);
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private record World(UUID site, UUID group, UUID service, UUID counter) {}

    private World world(JdbcTemplate jdbc) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
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
        return new World(site, group, service, counter);
    }

    private record Person(String username) {}

    private Person user(JdbcTemplate jdbc, Role role, UUID site, UUID teamOf) {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        if (teamOf != null) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", id, teamOf);
        return new Person(username);
    }

    private HttpResponse<String> issue(int port, String token, UUID service) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tickets"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"service_id\":\"" + service + "\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String login(int port, String username) throws Exception {
        HttpResponse<String> response = send(port, "POST", "/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (String) json(response).get("access_token");
    }

    private HttpResponse<String> send(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return MAPPER.readValue(response.body(), Map.class);
    }

    @Test
    void aWaitingTicketAndAnOpenCounterSessionSurviveANodeStoppingAndARestartedNodeStarting() throws Exception {
        Path keyDir = Files.createTempDirectory("qms-keys-upgrade-restart");

        // The old version, already migrated: build the world and put it into the exact state an upgrade must not
        // lose (FR-OPS-021) - a ticket still waiting, and a counter session open with nothing called yet.
        ConfigurableApplicationContext before = start(keyDir, true);
        int portBefore = port(before);
        JdbcTemplate jdbc = before.getBean(JdbcTemplate.class);
        World w = world(jdbc);
        Person reception = user(jdbc, Role.RECEPTION_OPERATOR, w.site(), null);
        Person agent = user(jdbc, Role.AGENT, w.site(), w.group());

        String receptionToken = login(portBefore, reception.username());
        HttpResponse<String> issued = issue(portBefore, receptionToken, w.service());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        String issuedTicketId = (String) json(issued).get("id");

        String agentToken = login(portBefore, agent.username());
        HttpResponse<String> opened = send(portBefore, "POST", "/sessions", agentToken, "{\"counter_id\":\"" + w.counter() + "\"}");
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String sessionId = (String) json(opened).get("id");
        assertThat(json(opened).get("state")).isEqualTo("open");
        assertThat(json(opened).get("ticket")).as("nothing called yet").isNull();

        // FR-OPS-021: "where a restart is required, the system MUST restore both" - stop the old node entirely,
        // then start a brand new node (a fresh process, fresh in-memory state) exactly as `deploy/compose.yaml`'s
        // `backend` service starts after `migrate` has already run (FR-OPS-020): migrations already applied, so
        // this node starts with flyway disabled.
        before.close();
        ConfigurableApplicationContext after = start(keyDir, false);
        int portAfter = port(after);

        try {
            String agentTokenAfterRestart = login(portAfter, agent.username());

            HttpResponse<String> current = send(portAfter, "GET", "/sessions/current", agentTokenAfterRestart, null);
            assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
            assertThat(json(current).get("id")).as("the open session survived the restart").isEqualTo(sessionId);
            assertThat(json(current).get("state")).isEqualTo("open");

            HttpResponse<String> called = send(portAfter, "POST", "/sessions/" + sessionId + "/next", agentTokenAfterRestart, null);
            assertThat(called.statusCode()).as(called.body()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> ticket = (Map<String, Object>) json(called).get("ticket");
            assertThat(ticket.get("id")).as("the waiting ticket issued before the restart is exactly what got called").isEqualTo(issuedTicketId);
            assertThat(ticket.get("state")).isEqualTo("called");
        } finally {
            after.close();
        }
    }
}
