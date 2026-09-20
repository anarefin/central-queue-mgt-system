package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code zone:{zone_id}} topic end to end (ticket 28, FR-DSP-010, FR-DSP-011): a display board watches its own
 * zone and sees a call within the hub's own delivery latency, a staff caller who manages the site may watch it too,
 * and the polling fallback of FR-QUE-084 serves the same data over {@code GET /stream/snapshot}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class DisplayRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-display-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Paired(UUID id, String accessToken) {}

    private Paired pairDisplay(String adminToken, UUID site, UUID zone) throws Exception {
        HttpResponse<String> code = send("POST", "/devices/pairing-codes", adminToken,
                "{\"kind\":\"display\",\"site_id\":\"" + site + "\",\"zone_id\":\"" + zone + "\",\"label\":\"Lobby screen\"}");
        Map<String, Object> codeBody = json(code);
        HttpResponse<String> paired = send("POST", "/devices/pair", null, "{\"code\":\"" + codeBody.get("code") + "\"}");
        Map<String, Object> body = json(paired);
        return new Paired(UUID.fromString((String) body.get("device_id")), (String) body.get("access_token"));
    }

    private UUID zoneOf(UUID counter) {
        return jdbc.queryForObject("SELECT zone_id FROM counter WHERE id = ?", UUID.class, counter);
    }

    @Test
    void aDisplayWatchesItsOwnZoneAndSeesACallWithinTheHubsDeliveryAndTheSnapshotListsTheCounterAndTheNextStrip() throws Exception {
        World w = world();
        UUID zone = zoneOf(w.counter());
        Person agent = person(Role.AGENT, w.site(), w.group());
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Paired display = pairDisplay(admin.token(), w.site(), zone);
        issue(w.service());

        Socket socket = connect(display.accessToken());
        socket.subscribe("zone:" + zone);
        Map<String, Object> snapshot = data(socket.next("snapshot"));
        assertThat((List<?>) snapshot.get("serving")).hasSize(1);
        Map<String, Object> counterRow = (Map<String, Object>) ((List<?>) snapshot.get("serving")).getFirst();
        assertThat(counterRow).containsEntry("counter_id", w.counter().toString()).containsEntry("token_number", null).containsEntry("counter_label", "Desk 1");
        assertThat((List<?>) snapshot.get("next")).hasSize(1);

        UUID session = openSession(agent, w.counter());
        Map<String, Object> called = json(send("POST", "/sessions/" + session + "/next", agent.token(), null));
        Map<String, Object> ticket = (Map<String, Object>) called.get("ticket");

        Map<String, Object> event = socket.next(f -> "event".equals(f.get("frame")) && "ticket.called".equals(f.get("type")));
        assertThat(event).containsEntry("topic", "zone:" + zone);
        assertThat(data(event)).containsEntry("counter_id", w.counter().toString()).containsEntry("token_number", ticket.get("token_number"));
    }

    @Test
    void anAdminWhoManagesTheZonesSiteMayWatchItButAnotherRoleMayNot() throws Exception {
        World w = world();
        UUID zone = zoneOf(w.counter());
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Person agent = person(Role.AGENT, w.site(), w.group());

        Socket adminSocket = connect(admin.token());
        adminSocket.subscribe("zone:" + zone);
        assertThat(adminSocket.next("snapshot")).containsEntry("topic", "zone:" + zone);

        Socket agentSocket = connect(agent.token());
        agentSocket.subscribe("zone:" + zone);
        assertThat(agentSocket.next("denied")).containsEntry("topic", "zone:" + zone).containsEntry("code", "forbidden");
    }

    @Test
    void aDisplayFromAnotherZoneMayNotWatchAndAnUnknownZoneIsNotFound() throws Exception {
        World w = world();
        World elsewhere = world();
        UUID zone = zoneOf(w.counter());
        UUID otherZone = zoneOf(elsewhere.counter());
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Paired displayElsewhere = pairDisplay(person(Role.ORG_ADMIN, elsewhere.site(), null).token(), elsewhere.site(), otherZone);

        Socket socket = connect(displayElsewhere.accessToken());
        socket.subscribe("zone:" + zone);
        assertThat(socket.next("denied")).containsEntry("topic", "zone:" + zone).containsEntry("code", "forbidden");

        Socket adminSocket = connect(admin.token());
        adminSocket.subscribe("zone:" + UUID.randomUUID());
        assertThat(adminSocket.next("denied")).containsEntry("code", "not_found");
    }

    @Test
    void whereWebSocketIsBlockedTheSameZoneSnapshotCanBePolledOverHttp() throws Exception {
        World w = world();
        UUID zone = zoneOf(w.counter());
        Person admin = person(Role.ORG_ADMIN, w.site(), null);

        Map<String, Object> polled = json(send("GET", "/stream/snapshot?topic=zone:" + zone, admin.token(), null));
        assertThat(polled).containsEntry("topic", "zone:" + zone);
        assertThat((List<?>) data(polled).get("serving")).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }
}
