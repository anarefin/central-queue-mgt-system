package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.security.Role;
import com.qms.session.SessionService;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FR-QUE-032 over the realtime hub: a called ticket that its Agent has not acted on for the call timeout prompts the Agent's console
 * on the counter's topic as {@code ticket.call_timeout}, once, and the ticket is unchanged. A real server and a real WebSocket, with the
 * timeout at one second and the schedule off so the test drives the check itself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"qms.queue.call-timeout-seconds=1", "qms.queue.call-timeout-check-cron=-"})
@Import(PostgresContainerConfig.class)
class CallTimeoutRealtimeIT extends RealServerSupport {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-call-timeout");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired SessionService sessions;

    private static Map<String, Object> event(Socket socket, String type) throws InterruptedException {
        return socket.next(f -> "event".equals(f.get("frame")) && type.equals(f.get("type")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theAgentsConsoleIsPromptedOnItsCounterOnceWhenACalledTicketGoesUnansweredAndTheTicketIsUnchanged() throws Exception {
        World w = world();
        Person agent = person(Role.AGENT, w.site(), w.group());
        issue(w.service());
        UUID session = openSession(agent, w.counter());
        Socket console = connect(agent.token());
        console.subscribe("counter:" + w.counter());
        console.next("snapshot");
        Map<String, Object> called = (Map<String, Object>) json(send("POST", "/sessions/" + session + "/next", agent.token(), null)).get("ticket");
        event(console, "ticket.called");

        sessions.promptTimedOutCalls();
        assertThat(console.frames.stream().filter(f -> "ticket.call_timeout".equals(f.get("type"))).count()).as("not before the timeout").isZero();

        Thread.sleep(1200);
        assertThat(sessions.promptTimedOutCalls()).isPositive();
        Map<String, Object> prompt = event(console, "ticket.call_timeout");
        Map<String, Object> data = (Map<String, Object>) prompt.get("data");
        assertThat(prompt).containsEntry("topic", "counter:" + w.counter());
        assertThat(data).containsEntry("ticket_id", called.get("id")).containsEntry("token_number", called.get("token_number")).containsEntry("state", "called")
                .containsEntry("session_id", session.toString()).containsEntry("timeout_seconds", 1).containsEntry("version", called.get("version"));

        assertThat(sessions.promptTimedOutCalls()).as("once per call").isZero();
        Map<String, Object> restored = json(send("GET", "/sessions/current", agent.token(), null));
        assertThat((Map<String, Object>) restored.get("ticket")).containsEntry("state", "called").containsEntry("call_timed_out", true).containsEntry("version", called.get("version"));
    }
}
