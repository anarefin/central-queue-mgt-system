package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0009 and SRS §21.1 end to end with access tokens that live five seconds: the hub closes a socket at its token's
 * {@code exp} unless a {@code reauth} frame carrying a fresh token arrived first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"qms.security.access-token-ttl=PT5S"})
@Import(PostgresContainerConfig.class)
class RealtimeExpiryIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-realtime-expiry");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theHubClosesASocketWhenItsTokenExpiresAndNoReauthCame() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Socket socket = connect(agent.token());
        socket.subscribe("queue:" + w.service());
        assertThat(socket.next("snapshot")).containsEntry("topic", "queue:" + w.service());
        long before = System.currentTimeMillis();

        assertThat(socket.awaitClose(10)).isEqualTo(4401);

        assertThat(System.currentTimeMillis() - before).as("at the token's exp, not at a later heartbeat").isLessThan(6_500);
    }

    @Test
    void aReauthFrameWithAFreshTokenKeepsTheSocketAlivePastTheOldExpiry() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Socket socket = connect(agent.token());
        socket.subscribe("queue:" + w.service());
        socket.next("snapshot");
        long firstExpiry = expiryOf(agent.token());
        Thread.sleep(2_100);
        Person fresh = login(agent);
        assertThat(expiryOf(fresh.token())).isGreaterThan(firstExpiry);

        socket.say(Map.of("frame", "reauth", "token", fresh.token()));
        assertThat(socket.next("reauth")).containsEntry("expires_at", java.time.Instant.ofEpochSecond(expiryOf(fresh.token())).toString());

        Thread.sleep(Math.max(firstExpiry * 1000 - System.currentTimeMillis(), 0) + 700);
        assertThat(socket.closeCode).as("past the first token's expiry").isEqualTo(-1);
        issue(w.service());
        assertThat(socket.next(f -> "event".equals(f.get("frame")))).containsEntry("type", "ticket.issued");

        assertThat(socket.awaitClose(10)).as("and closed at the fresh token's").isEqualTo(4401);
    }

    @Test
    void aReauthWithATokenTheHubDoesNotAcceptIsAnsweredWithAnErrorAndChangesNothing() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        Person someoneElse = person(Role.AGENT, w.site(), w.group());
        Socket socket = connect(agent.token());

        socket.say(Map.of("frame", "reauth", "token", "not-a-token"));
        assertThat(socket.next("error")).containsEntry("code", "unauthorized");
        socket.say(Map.of("frame", "reauth", "token", someoneElse.token()));
        assertThat(socket.next("error")).containsEntry("code", "unauthorized");

        assertThat(socket.awaitClose(10)).isEqualTo(4401);
    }
}
