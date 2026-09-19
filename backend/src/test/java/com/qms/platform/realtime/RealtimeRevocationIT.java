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
 * ADR-0009, FR-QUE-080, FR-CFG-104 and ADR-0008 end to end against a real server: disabling a user, or changing their roles,
 * drops their sockets at once; the token they had cannot bring the socket back; a disabled Agent's counter session closes
 * and the tickets it held go back to the front of the queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class RealtimeRevocationIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-realtime-revocation");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void disablingAnAgentDropsTheirSocketsClosesTheirSessionAndReturnsTheirTicketsToTheFront() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Person agent = person(Role.AGENT, w.site(), w.group());
        for (int i = 0; i < 4; i++) issue(w.service());
        UUID session = openSession(agent, w.counter());
        send("POST", "/sessions/" + session + "/next", agent.token(), null);
        send("POST", "/sessions/" + session + "/serve", agent.token(), null);
        assertThat(send("POST", "/sessions/" + session + "/hold", agent.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/sessions/" + session + "/next", agent.token(), null).statusCode()).isEqualTo(200);
        send("POST", "/sessions/" + session + "/serve", agent.token(), null);
        Socket console = connect(agent.token());
        console.subscribe("counter:" + w.counter());
        console.next("snapshot");
        Socket second = connect(agent.token());

        HttpResponse<String> disabled = send("POST", "/users/" + agent.id() + "/disable", admin.token(), "{\"reason\":\"left the company\"}");

        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(console.awaitClose(5)).as("dropped at once, not at token expiry").isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(second.awaitClose(5)).isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).isEqualTo("force_closed");
        List<Map<String, Object>> tickets = jdbc.queryForList("SELECT state, counter_session_id, score_adjustment_minutes FROM ticket WHERE service_id = ?", w.service());
        assertThat(tickets).hasSize(4).allSatisfy(t -> {
            assertThat(t.get("state")).isEqualTo("waiting");
            assertThat(t.get("counter_session_id")).as("binding cleared (Invariant 2)").isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id IN (SELECT id FROM ticket WHERE service_id = ?) AND payload::text LIKE '%user_disabled%' AND payload::text LIKE '%front%'", Integer.class, w.service()))
                .as("the serving and the held ticket were each returned to the front").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", String.class, session)).isEqualTo("user_disabled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, agent.id())).as("refresh tokens invalidated").isZero();

        Socket comeBack = connect(agent.token());
        assertThat(comeBack.awaitClose(5)).as("the access token they still hold cannot bring the socket back").isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
    }

    @Test
    void changingRolesDropsTheirSocketsAndTheClientIsAuthorisedAgainstItsNewRolesWhenItComesBack() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);
        Person agent = person(Role.AGENT, w.site(), w.group());
        Socket socket = connect(agent.token());
        socket.subscribe("counter:" + w.counter());
        assertThat(socket.next("snapshot")).containsEntry("topic", "counter:" + w.counter());

        HttpResponse<String> changed = send("PUT", "/users/" + agent.id() + "/roles", admin.token(),
                "{\"roles\":[{\"role\":\"reception_operator\",\"site_ids\":[\"" + w.site() + "\"]}],\"reason\":\"moved to reception\"}");

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(socket.awaitClose(5)).isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);
        assertThat(connect(agent.token()).awaitClose(5)).as("the token with the old roles is no good").isEqualTo(RealtimeHub.PRINCIPAL_CHANGED);

        Thread.sleep(1_100); // a token issued in the second of the change cannot be told from an older one
        Socket back = connect(login(agent).token());
        back.subscribe("counter:" + w.counter());
        assertThat(back.next("denied")).containsEntry("topic", "counter:" + w.counter()).containsEntry("code", "forbidden");
        assertThat(back.closeCode).isEqualTo(-1);
    }
}
