package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.issuance.TicketResponse;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code ticket:{ticket_id}} topic end to end (ticket 37, §20.2, §21.2, FR-MOB-013, FR-SEC-033): a visitor connects
 * with the ticket's own id and secret, never a JWT, and may watch only that one ticket. A wrong or missing secret is
 * refused at the handshake, the same as a request with no token at all; a staff JWT still connects and subscribes to
 * every other topic exactly as before (ticket 11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
class VisitorTicketRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-ticket-realtime");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aVisitorWatchesOnlyTheirOwnTicketsTopicWithItsOwnSecret() throws Exception {
        World w = world();
        TicketResponse ticket = issueTicket(w.service());

        Socket socket = connectTicket(ticket.id(), ticket.secret());
        socket.subscribe("ticket:" + ticket.id());
        Map<String, Object> snapshot = socket.next("snapshot");
        assertThat(snapshot).containsEntry("topic", "ticket:" + ticket.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
        assertThat(data).containsEntry("ticket_id", ticket.id().toString()).containsEntry("token_number", ticket.tokenNumber());
    }

    @Test
    void aTicketsOwnSecretDoesNotOpenAnotherTicketsTopic() throws Exception {
        World w = world();
        TicketResponse mine = issueTicket(w.service());
        TicketResponse theirs = issueTicket(w.service());

        Socket socket = connectTicket(mine.id(), mine.secret());
        socket.subscribe("ticket:" + theirs.id());
        assertThat(socket.next("denied")).containsEntry("topic", "ticket:" + theirs.id()).containsEntry("code", "forbidden");
    }

    @Test
    void aWrongOrMissingSecretIsRefusedAtTheHandshakeLikeNoTokenAtAll() throws Exception {
        World w = world();
        TicketResponse ticket = issueTicket(w.service());

        assertThatThrownBy(() -> connectTicket(ticket.id(), "not-the-right-secret"))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401));

        assertThatThrownBy(() -> connectTicket(UUID.randomUUID(), ticket.secret()))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401));
    }

    @Test
    void staffTopicsAreUnaffectedByTheAnonymousTicketPath() throws Exception {
        World w = world();
        Person admin = person(Role.ORG_ADMIN, w.site(), null);

        Socket socket = connect(admin.token());
        socket.subscribe("queue:" + w.service());
        assertThat(socket.next("snapshot")).containsEntry("topic", "queue:" + w.service());
    }
}
