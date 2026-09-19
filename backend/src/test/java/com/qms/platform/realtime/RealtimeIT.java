package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 11 end to end: a real server on a real port, real PostgreSQL, and a real WebSocket client. A console subscribes
 * to its counter and queue and sees the session and the tickets move as an agent calls, serves and completes over REST.
 * Covers §21.1 (endpoint, bearer authentication, subscribe, snapshot, then deltas, heartbeat), §21.2 topics, §21.3 the
 * envelope, §21.4 event types, FR-QUE-080 (authorisation at subscribe time), FR-QUE-081 (replay after a reconnect) and
 * FR-QUE-084 (the polling snapshot).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"qms.realtime.heartbeat-interval=1s"})
@Import(PostgresContainerConfig.class)
class RealtimeIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void hangUp() {
        sockets.forEach(Socket::hangUp);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

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

    /** A signed-in staff user scoped to {@code site}; on the team of {@code teamOf} when given. */
    private Person person(Role role, UUID site, UUID teamOf) throws Exception {
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
        HttpResponse<String> login = send("POST", "/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Person(user, (String) MAPPER.readValue(login.body(), Map.class).get("access_token"));
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
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

    private UUID openSession(Person agent, UUID counter) throws Exception {
        return UUID.fromString((String) json(send("POST", "/sessions", agent.token(), "{\"counter_id\":\"" + counter + "\"}")).get("id"));
    }

    private void issue(UUID service) {
        issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
    }

    // ---- a WebSocket client ------------------------------------------------------------------------------------

    /** The frames the server sends, in the order they arrive. */
    final class Socket implements WebSocket.Listener {
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

        /** The next frame that satisfies {@code wanted}, skipping heartbeats and anything else; fails after five seconds. */
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

        void hangUp() {
            if (socket != null) socket.abort();
        }
    }

    /** Connects the way a browser does: the token rides in the subprotocol offer. */
    private Socket connect(Person who) throws Exception {
        Socket socket = new Socket();
        socket.socket = http.newWebSocketBuilder()
                .subprotocols(RealtimeEndpoint.PROTOCOL, "bearer." + who.token())
                .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), socket)
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    // ---- §21.1 the endpoint and its authentication -------------------------------------------------------------

    @Test
    void theEndpointIsAuthenticatedWithTheRestBearerTokenInAnAuthorizationHeaderOrTheBrowsersSubprotocol() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());

        Socket viaSubprotocol = connect(agent);
        assertThat(viaSubprotocol.socket.getSubprotocol()).as("the token is never echoed back").isEqualTo(RealtimeEndpoint.PROTOCOL);

        Socket viaHeader = new Socket();
        viaHeader.socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + agent.token())
                .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), viaHeader).get(5, TimeUnit.SECONDS);
        sockets.add(viaHeader);
        viaHeader.subscribe(Map.of("topic", "queue:" + w.service()));
        assertThat(viaHeader.next("snapshot")).containsEntry("topic", "queue:" + w.service());
    }

    @Test
    void aConnectionWithoutAValidTokenIsRefusedBeforeTheUpgrade() {
        assertThatThrownBy(() -> http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), new Socket()).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401));
        assertThatThrownBy(() -> http.newWebSocketBuilder().subprotocols(RealtimeEndpoint.PROTOCOL, "bearer.not-a-token")
                        .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), new Socket()).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401));
    }

    @Test
    void aTokenOfferedInTheSubprotocolIsHonouredOnTheHubAndNowhereElse() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());

        HttpResponse<String> rest = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sessions/current"))
                .header("Sec-WebSocket-Protocol", "qms.v1, bearer." + agent.token()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(401);
    }

    // ---- §21.1, §21.2, §21.4: a console updates live -----------------------------------------------------------

    @Test
    void aConsoleSeesItsSessionAndItsQueueMoveLiveAsTheAgentCallsServesAndCompletes() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        Socket console = connect(agent);
        console.subscribe("queue:" + w.service(), "counter:" + w.counter());

        Map<String, Object> queue = console.next(f -> "snapshot".equals(f.get("frame")) && ("queue:" + w.service()).equals(f.get("topic")));
        assertThat(queue).containsEntry("resync", false);
        assertThat(data(queue)).containsEntry("waiting_count", 1).containsEntry("service_id", w.service().toString());
        assertThat((List<?>) data(queue).get("next")).hasSize(1);
        Map<String, Object> counter = console.next(f -> "snapshot".equals(f.get("frame")) && ("counter:" + w.counter()).equals(f.get("topic")));
        assertThat(data(counter)).containsEntry("session", null).containsEntry("ticket", null).containsEntry("label", "Desk 1");

        UUID session = openSession(agent, w.counter());
        Map<String, Object> opened = console.event("session.opened");
        assertThat(opened).containsEntry("topic", "counter:" + w.counter()).containsEntry("seq", 1);
        assertThat(data(opened)).containsEntry("session_id", session.toString()).containsEntry("state", "open");
        assertThat((String) opened.get("occurred_at")).isNotBlank();

        issue(w.service());
        Map<String, Object> issued = console.event("ticket.issued");
        assertThat(issued).containsEntry("topic", "queue:" + w.service()).containsEntry("seq", 2);
        assertThat(data(issued)).containsEntry("waiting_count", 2).containsEntry("state", "waiting");

        Map<String, Object> called = json(send("POST", "/sessions/" + session + "/next", agent.token(), null));
        Map<String, Object> ticket = ticket(called);
        Map<String, Object> onQueue = console.event("ticket.called");
        assertThat(onQueue).containsEntry("topic", "queue:" + w.service()).containsEntry("seq", 3);
        assertThat(data(onQueue)).containsEntry("waiting_count", 1).containsEntry("token_number", ticket.get("token_number")).containsEntry("counter_id", w.counter().toString()).containsEntry("announce", true);
        Map<String, Object> onCounter = console.event("ticket.called");
        assertThat(onCounter).containsEntry("topic", "counter:" + w.counter()).containsEntry("seq", 2);

        send("POST", "/sessions/" + session + "/serve", agent.token(), null);
        assertThat(console.event("ticket.serving")).containsEntry("seq", 4).containsEntry("topic", "queue:" + w.service());
        assertThat(console.event("ticket.serving")).containsEntry("seq", 3).containsEntry("topic", "counter:" + w.counter());

        send("POST", "/sessions/" + session + "/complete", agent.token(), "{}");
        assertThat(console.event("ticket.completed")).containsEntry("seq", 5).containsEntry("topic", "queue:" + w.service());
        assertThat(console.event("ticket.completed")).containsEntry("seq", 4).containsEntry("topic", "counter:" + w.counter());

        send("DELETE", "/sessions/" + session, agent.token(), null);
        Map<String, Object> closed = console.event("session.closed");
        assertThat(closed).containsEntry("topic", "counter:" + w.counter()).containsEntry("seq", 5);
        assertThat(data(closed)).containsEntry("state", "closed");
    }

    @Test
    void reannouncingAndMissingAreAnnouncedOnTheQueueAndTheCounterDeduplicatedByAnnounceCount() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        Socket console = connect(agent);
        console.subscribe("queue:" + w.service(), "counter:" + w.counter());
        UUID session = openSession(agent, w.counter());
        send("POST", "/sessions/" + session + "/next", agent.token(), null);
        console.event("ticket.called");
        console.event("ticket.called");

        send("POST", "/sessions/" + session + "/reannounce", agent.token(), null);
        Map<String, Object> onQueue = console.event("ticket.reannounced");
        assertThat(onQueue).containsEntry("topic", "queue:" + w.service());
        assertThat(data(onQueue)).containsEntry("state", "called").containsEntry("announce", true).containsEntry("announce_count", 1).containsEntry("counter_id", w.counter().toString());
        Map<String, Object> onCounter = console.event("ticket.reannounced");
        assertThat(onCounter).containsEntry("topic", "counter:" + w.counter());
        assertThat(data(onCounter)).containsEntry("announce_count", 1);

        send("POST", "/sessions/" + session + "/miss", agent.token(), null);
        Map<String, Object> missed = console.event("ticket.missed");
        assertThat(missed).containsEntry("topic", "queue:" + w.service());
        assertThat(data(missed)).containsEntry("state", "waiting").containsEntry("waiting_count", 1);
        assertThat(console.event("ticket.missed")).containsEntry("topic", "counter:" + w.counter());
    }

    @Test
    void holdingResumingAndForceClosingAreAnnouncedOnTheQueueAndTheCounter() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Person admin = person(Role.TEAM_ADMIN, w.site(), w.group());
        issue(w.service());
        issue(w.service());
        Socket console = connect(agent);
        console.subscribe("queue:" + w.service(), "counter:" + w.counter());
        UUID session = openSession(agent, w.counter());
        send("POST", "/sessions/" + session + "/next", agent.token(), null);
        send("POST", "/sessions/" + session + "/serve", agent.token(), null);
        console.event("ticket.serving");
        console.event("ticket.serving");

        Map<String, Object> held = json(send("POST", "/sessions/" + session + "/hold", agent.token(), null));
        Map<String, Object> heldTicket = (Map<String, Object>) ((List<Object>) held.get("held")).getFirst();
        Map<String, Object> onQueue = console.event("ticket.held");
        assertThat(onQueue).containsEntry("topic", "queue:" + w.service());
        assertThat(data(onQueue)).containsEntry("state", "held").containsEntry("token_number", heldTicket.get("token_number")).containsEntry("counter_id", w.counter().toString());
        assertThat(console.event("ticket.held")).containsEntry("topic", "counter:" + w.counter());

        send("POST", "/sessions/" + session + "/hold", agent.token(), "{\"ticket_id\":\"" + heldTicket.get("id") + "\"}");
        assertThat(data(console.event("ticket.serving"))).containsEntry("state", "serving");
        console.event("ticket.serving");

        send("POST", "/sessions/" + session + "/force-close", admin.token(), null);
        assertThat(data(console.event("ticket.position_changed"))).containsEntry("state", "waiting").containsEntry("waiting_count", 2);
        console.event("ticket.position_changed");
        Map<String, Object> closed = console.event("session.closed");
        assertThat(closed).containsEntry("topic", "counter:" + w.counter());
        assertThat(data(closed)).containsEntry("state", "force_closed");
    }

    @Test
    void aTransferIsAnnouncedOnTheQueueAndTheCounterAndTheSuccessorJoinsTheQueue() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Person colleague = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        Socket console = connect(agent);
        console.subscribe("queue:" + w.service(), "counter:" + w.counter());
        UUID session = openSession(agent, w.counter());
        send("POST", "/sessions/" + session + "/next", agent.token(), null);
        Map<String, Object> serving = json(send("POST", "/sessions/" + session + "/serve", agent.token(), null));
        console.event("ticket.serving");
        console.event("ticket.serving");
        String ticket = (String) ((Map<String, Object>) serving.get("ticket")).get("id");

        Map<String, Object> transferred = json(send("POST", "/tickets/" + ticket + "/transfer", agent.token(), "{\"agent_id\":\"" + colleague.id() + "\",\"note\":\"Second opinion\"}"));

        Map<String, Object> onQueue = console.event("ticket.transferred");
        assertThat(onQueue).containsEntry("topic", "queue:" + w.service());
        assertThat(data(onQueue)).containsEntry("state", "transferred").containsEntry("ticket_id", ticket).containsEntry("counter_id", w.counter().toString()).containsEntry("waiting_count", 1);
        assertThat(console.event("ticket.transferred")).containsEntry("topic", "counter:" + w.counter());
        Map<String, Object> joined = console.event("ticket.issued");
        assertThat(joined).containsEntry("topic", "queue:" + w.service());
        assertThat(data(joined)).containsEntry("state", "waiting").containsEntry("token_number", ((Map<String, Object>) transferred.get("successor")).get("token_number"));
        assertThat(data(joined).get("ticket_id")).as("the successor, not the predecessor").isEqualTo(((Map<String, Object>) transferred.get("successor")).get("id"));
    }

    @Test
    void aSnapshotShowsTheSessionAndTheTicketInProgress() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        UUID session = openSession(agent, w.counter());
        Map<String, Object> called = ticket(json(send("POST", "/sessions/" + session + "/next", agent.token(), null)));

        Socket console = connect(agent);
        console.subscribe("counter:" + w.counter());

        Map<String, Object> data = data(console.next("snapshot"));
        assertThat(data).extractingByKey("session").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("id", session.toString()).containsEntry("state", "open");
        assertThat(data).extractingByKey("ticket").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("token_number", called.get("token_number")).containsEntry("state", "called");
    }

    @Test
    void aRolledBackTransitionIsNeverAnnounced() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Socket console = connect(agent);
        console.subscribe("queue:" + w.service());
        console.next("snapshot");

        // Serving a ticket that is not there is refused before anything is written, so nothing is announced.
        UUID session = openSession(agent, w.counter());
        assertThat(send("POST", "/sessions/" + session + "/serve", agent.token(), null).statusCode()).isEqualTo(409);
        issue(w.service());

        assertThat(console.event("ticket.issued")).containsEntry("seq", 1);
    }

    // ---- FR-QUE-080 authorisation at subscribe time ------------------------------------------------------------

    @Test
    void aSubscriberSeesOnlyTheTopicsItsPermissionsAndScopeAllow() throws Exception {
        World w = world();
        World elsewhere = world();
        Person teammate = person(Role.AGENT, w.site(), w.group());
        Person outsider = person(Role.AGENT, w.site(), null);
        Person otherSite = person(Role.AGENT, elsewhere.site(), w.group());
        Person reception = person(Role.RECEPTION_OPERATOR, w.site(), null);
        Person teamAdmin = person(Role.TEAM_ADMIN, w.site(), null);
        String queue = "queue:" + w.service();
        String counter = "counter:" + w.counter();

        assertThat(denials(teammate, queue, counter)).isEmpty();
        assertThat(denials(teamAdmin, queue, counter)).isEmpty();
        assertThat(denials(outsider, queue, counter)).as("an agent not on the team sees neither its queue nor its counter").containsExactly(Map.entry(queue, "forbidden"), Map.entry(counter, "forbidden"));
        assertThat(denials(otherSite, queue, counter)).as("outside the token's sites").containsExactly(Map.entry(queue, "forbidden"), Map.entry(counter, "forbidden"));
        assertThat(denials(reception, queue, counter)).as("a dashboard viewer watches queues, not counters").containsExactly(Map.entry(counter, "forbidden"));
        assertThat(denials(teammate, "queue:" + UUID.randomUUID(), "counter:" + UUID.randomUUID())).extracting(Map.Entry::getValue).containsExactly("not_found", "not_found");
        assertThat(denials(teammate, "queue:nonsense", "device:" + UUID.randomUUID())).extracting(Map.Entry::getValue).containsExactly("invalid_topic", "unknown_topic");
    }

    /** Subscribes to each topic and returns the ones refused, with the reason; the others must each get a snapshot. */
    private List<Map.Entry<String, String>> denials(Person who, String... topics) throws Exception {
        Socket socket = connect(who);
        socket.subscribe((Object[]) topics);
        List<Map.Entry<String, String>> refused = new ArrayList<>();
        for (String ignored : topics) {
            Map<String, Object> answer = socket.next(f -> "snapshot".equals(f.get("frame")) || "denied".equals(f.get("frame")));
            if ("denied".equals(answer.get("frame"))) refused.add(Map.entry((String) answer.get("topic"), (String) answer.get("code")));
        }
        return refused;
    }

    // ---- §21.1 heartbeat ---------------------------------------------------------------------------------------

    @Test
    void theHubSendsHeartbeatsAndDropsAClientThatSaysNothing() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Socket quiet = connect(agent);

        assertThat(quiet.next("heartbeat")).containsKey("time");
        long deadline = System.currentTimeMillis() + 10_000; // 1 s interval: dropped once silent for more than 3 s
        while (quiet.closeCode == -1 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(quiet.closeCode).isEqualTo(RealtimeHub.HEARTBEAT_TIMEOUT);

        Socket talking = connect(agent);
        for (int i = 0; i < 5; i++) {
            talking.say(Map.of("frame", "heartbeat"));
            Thread.sleep(1000);
        }
        assertThat(talking.closeCode).as("a client that keeps sending is kept").isEqualTo(-1);
    }

    // ---- FR-QUE-081 reconnect and replay -----------------------------------------------------------------------

    @Test
    void aClientThatReconnectsIsCaughtUpFromTheBufferWithoutGapsOrDuplicates() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        String queue = "queue:" + w.service();
        issue(w.service());
        Socket before = connect(agent);
        before.subscribe(queue);
        Map<String, Object> snapshot = before.next("snapshot");
        assertThat(snapshot).containsEntry("seq", 1); // the ticket issued before subscribing is seq 1
        issue(w.service());
        assertThat(before.event("ticket.issued")).containsEntry("seq", 2);
        before.hangUp();

        issue(w.service());
        issue(w.service());

        Socket after = connect(agent);
        after.subscribe(Map.of("topic", queue, "last_seq", 2, "epoch", snapshot.get("epoch")));
        assertThat(after.next("replay")).containsEntry("count", 2).containsEntry("seq", 4);
        assertThat(after.event("ticket.issued")).containsEntry("seq", 3);
        assertThat(after.event("ticket.issued")).containsEntry("seq", 4);
        issue(w.service());
        Map<String, Object> live = after.event("ticket.issued");
        assertThat(live).containsEntry("seq", 5);
        assertThat(data(live)).containsEntry("waiting_count", 5);
    }

    @Test
    void aClientWhoseEpochOrSeqIsUnknownIsGivenAFreshSnapshotFlaggedResync() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        Socket socket = connect(agent);
        socket.subscribe(Map.of("topic", "queue:" + w.service(), "last_seq", 41, "epoch", "from-before-a-restart"));

        Map<String, Object> snapshot = socket.next("snapshot");
        assertThat(snapshot).containsEntry("resync", true);
        assertThat(data(snapshot)).containsEntry("waiting_count", 1);
    }

    // ---- FR-QUE-084 polling ------------------------------------------------------------------------------------

    @Test
    void whereWebSocketIsBlockedTheSameSnapshotCanBePolledOverHttpUnderTheSameAuthorisation() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Person outsider = person(Role.AGENT, w.site(), null);
        issue(w.service());
        issue(w.service());

        Map<String, Object> polled = json(send("GET", "/stream/snapshot?topic=queue:" + w.service(), agent.token(), null));
        assertThat(polled).containsEntry("topic", "queue:" + w.service()).containsEntry("seq", 2);
        assertThat(polled.get("epoch")).isNotNull();
        assertThat(data(polled)).containsEntry("waiting_count", 2);

        assertThat(send("GET", "/stream/snapshot?topic=queue:" + w.service(), outsider.token(), null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/stream/snapshot?topic=queue:" + UUID.randomUUID(), agent.token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/stream/snapshot?topic=nonsense:1", agent.token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/stream/snapshot?topic=queue:" + w.service(), null, null).statusCode()).isEqualTo(401);
    }

    // ---- NFR-MNT-002 -------------------------------------------------------------------------------------------

    @Test
    void theHubReportsItselfUpInDependencyHealth() throws Exception {
        HttpResponse<String> health = send("GET", "/health/dependencies", null, null);
        assertThat(health.body()).contains("\"realtime_hub\":{\"status\":\"up\"");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ticket(Map<String, Object> session) {
        return (Map<String, Object>) session.get("ticket");
    }
}
