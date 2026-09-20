package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.platform.security.Role;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** A real server on a real port with a real WebSocket client, for the tests of a socket's credentials (ADR-0009). */
abstract class RealServerSupport {

    static final String PASSWORD = "Correct-Horse-9";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void hangUp() {
        sockets.forEach(Socket::hangUp);
    }

    record World(UUID site, UUID group, UUID service, UUID counter) {}

    record Person(UUID id, String username, String token) {}

    World world() {
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
    Person person(Role role, UUID site, UUID teamOf) throws Exception {
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
        return login(user, username);
    }

    /** Signs in again, as the user is now: a new access token carrying their current roles. */
    Person login(Person who) throws Exception {
        return login(who.id(), who.username());
    }

    private Person login(UUID user, String username) throws Exception {
        HttpResponse<String> login = send("POST", "/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Person(user, username, (String) MAPPER.readValue(login.body(), Map.class).get("access_token"));
    }

    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return MAPPER.readValue(response.body(), Map.class);
    }

    UUID openSession(Person agent, UUID counter) throws Exception {
        return UUID.fromString((String) json(send("POST", "/sessions", agent.token(), "{\"counter_id\":\"" + counter + "\"}")).get("id"));
    }

    void issue(UUID service) {
        issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
    }

    /** Issues a ticket the way {@link #issue} does, but returns it, id and secret included, for the visitor ticket page
     * tests (ticket 37, §20.2). */
    TicketResponse issueTicket(UUID service) {
        return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
    }

    /** The {@code exp} of an access token, in epoch seconds. */
    static long expiryOf(String token) {
        Map<?, ?> claims = MAPPER.readValue(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8), Map.class);
        return ((Number) claims.get("exp")).longValue();
    }

    /** The frames the server sends, in the order they arrive, and how the socket was closed. */
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

        /** Waits for the server to close the socket and returns the close code; fails if it stays open. */
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

    /** Connects the way a browser does: the token rides in the subprotocol offer. */
    Socket connect(String token) throws Exception {
        Socket socket = new Socket();
        socket.socket = http.newWebSocketBuilder()
                .subprotocols(RealtimeEndpoint.PROTOCOL, "bearer." + token)
                .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), socket)
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    /** Connects the way the visitor ticket page does (ticket 37, §20.2, FR-SEC-033): the ticket id and its own secret ride
     * the subprotocol offer instead of a token, the same trick for the same reason (SRS §21.1). */
    Socket connectTicket(UUID ticketId, String credential) throws Exception {
        Socket socket = new Socket();
        socket.socket = http.newWebSocketBuilder()
                .subprotocols(RealtimeEndpoint.PROTOCOL, "ticket." + ticketId + "." + credential)
                .buildAsync(URI.create("ws://localhost:" + port + RealtimeEndpoint.PATH), socket)
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }
}
