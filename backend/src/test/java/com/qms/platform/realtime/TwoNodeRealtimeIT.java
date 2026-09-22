package com.qms.platform.realtime;

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
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * Ticket 59 end to end (ADR-0010, NFR-SCL-001, NFR-AVL-005): two full, independent backend nodes — two
 * {@code QmsApplication} contexts, neither aware of the other in process — sharing nothing but the one PostgreSQL
 * every real Medium-tier deployment gives every node. A console (here, a display) subscribed through node B sees a
 * call an Agent makes through node A within 2 s, over {@link ClusterRealtimeFanout}'s PostgreSQL LISTEN/NOTIFY fan-out
 * (FR-QUE-080, §21). The token node A issues authenticates REST and the socket handshake on node B just as well: no
 * sticky session, nothing pinning a client to the node it first spoke to.
 */
@Testcontainers
class TwoNodeRealtimeIT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    private static Path keyDir;
    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;
    private static int portA;
    private static int portB;
    private static JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Socket> sockets = new ArrayList<>();

    @BeforeAll
    static void startCluster() throws IOException {
        keyDir = Files.createTempDirectory("qms-keys-two-node");
        // Node A migrates the schema; node B starts once it is already there, the same "migrate, then start every
        // node" sequencing FR-OPS-020 and deploy/compose.yaml's own migrate step already use.
        nodeA = start(true);
        portA = port(nodeA);
        nodeB = start(false);
        portB = port(nodeB);
        jdbc = nodeA.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopCluster() {
        if (nodeB != null) nodeB.close();
        if (nodeA != null) nodeA.close();
    }

    @AfterEach
    void hangUp() {
        sockets.forEach(Socket::hangUp);
    }

    private static ConfigurableApplicationContext start(boolean migrate) {
        // Command-line-style args, not properties()/defaultProperties: they outrank a JVM system property (Gradle's
        // own qms.security.key-dir for the unit/slice-test key dir, set process-wide for every *Test task), so each
        // node reliably gets this test's own shared key dir, not that one.
        return new SpringApplicationBuilder(QmsApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.flyway.enabled=" + migrate,
                        "--qms.security.key-dir=" + keyDir,
                        "--qms.realtime.heartbeat-interval=20s");
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    // ---- fixtures: direct JDBC against the one database both nodes share -------------------------------------------

    private record World(UUID site, UUID group, UUID service, UUID counter) {}

    private record Person(UUID id, String token) {}

    private World world() {
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

    /** A signed-in staff user scoped to {@code site}, authenticated through whichever node's {@code /auth/login} is asked. */
    private Person person(Role role, UUID site, UUID teamOf, int loginPort) throws Exception {
        UUID user = UUID.randomUUID();
        String username = role.wire() + "-" + user;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        if (teamOf != null) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
        HttpResponse<String> login = send(loginPort, "POST", "/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Person(user, (String) MAPPER.readValue(login.body(), Map.class).get("access_token"));
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

    private void issue(int port, String token, UUID service) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tickets"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"service_id\":\"" + service + "\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private UUID openSession(int port, Person agent, UUID counter) throws Exception {
        return UUID.fromString((String) json(send(port, "POST", "/sessions", agent.token(), "{\"counter_id\":\"" + counter + "\"}")).get("id"));
    }

    // ---- a WebSocket client, on whichever node's port is given ---------------------------------------------------

    private final class Socket implements WebSocket.Listener {
        final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        volatile WebSocket socket;
        volatile int closeCode = -1;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(MAPPER.readValue(partial.toString(), Map.class));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            return null;
        }

        void say(Object frame) {
            socket.sendText(MAPPER.writeValueAsString(frame), true).join();
        }

        void subscribe(Object... topics) {
            say(Map.of("frame", "subscribe", "topics", List.of(topics)));
        }

        Map<String, Object> next(Predicate<Map<String, Object>> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                Map<String, Object> frame = frames.poll(Math.max(deadline - System.nanoTime(), 1), TimeUnit.NANOSECONDS);
                if (frame == null) throw new AssertionError("no matching frame within 5 s");
                if (wanted.test(frame)) return frame;
            }
        }

        Map<String, Object> next(String kind) throws InterruptedException {
            return next(f -> kind.equals(f.get("frame")));
        }

        Map<String, Object> event(String type) throws InterruptedException {
            return next(f -> "event".equals(f.get("frame")) && type.equals(f.get("type")));
        }

        int awaitClose(long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (closeCode == -1 && System.nanoTime() < deadline) Thread.sleep(25);
            assertThat(closeCode).as("the server closed the socket").isNotEqualTo(-1);
            return closeCode;
        }

        void hangUp() {
            if (socket != null) socket.abort();
        }
    }

    private Socket connect(int port, String token) throws Exception {
        Socket socket = new Socket();
        socket.socket = http.newWebSocketBuilder()
                .subprotocols(RealtimeEndpoint.PROTOCOL, "bearer." + token)
                .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), socket)
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    // ---- the tests -----------------------------------------------------------------------------------------------

    @Test
    void aCallOnNodeAReachesASubscriberOnNodeBWithinTwoSecondsUsingATokenNodeAIssued() throws Exception {
        World w = world();
        Person reception = person(Role.RECEPTION_OPERATOR, w.site(), null, portA);
        Person agent = person(Role.AGENT, w.site(), w.group(), portA);

        // A display subscribes through node B, on a token node A issued: no sticky session (NFR-SCL-001, API-010).
        Socket display = connect(portB, agent.token());
        display.subscribe("queue:" + w.service());
        display.next("snapshot");

        issue(portA, reception.token(), w.service());
        UUID session = openSession(portA, agent, w.counter());

        long start = System.nanoTime();
        assertThat(send(portA, "POST", "/sessions/" + session + "/next", agent.token(), null).statusCode()).isEqualTo(200);

        Map<String, Object> called = display.event("ticket.called");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(called).containsEntry("topic", "queue:" + w.service());
        assertThat(elapsedMs).as("the call on node A reached node B's subscriber over cross-node fan-out within 2 s (was %d ms)", elapsedMs)
                .isLessThanOrEqualTo(2000);
    }

    @Test
    void aTokenNodeAIssuesAuthenticatesRestOnNodeBWithNoStickySession() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group(), portA);

        HttpResponse<String> onB = send(portB, "GET", "/queues/" + w.service(), agent.token(), null);

        assertThat(onB.statusCode()).as(onB.body()).isEqualTo(200);
    }

    @Test
    void disablingAUserOnNodeADropsTheirSocketOnNodeBOverTheSameFanOut() throws Exception {
        World w = world();
        Person orgAdmin = person(Role.ORG_ADMIN, w.site(), null, portA);
        Person agent = person(Role.AGENT, w.site(), w.group(), portA);

        Socket socket = connect(portB, agent.token());
        socket.subscribe("queue:" + w.service());
        socket.next("snapshot");

        assertThat(send(portA, "POST", "/users/" + agent.id() + "/disable", orgAdmin.token(), null).statusCode()).isEqualTo(200);

        assertThat(socket.awaitClose(5)).as("principal.changed reached node B over the same fan-out").isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
    }
}
