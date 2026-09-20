package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.support.PostgresContainerConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The email adapter end to end (ticket 40, FR-INT-040, §14.1, §27.2): a real SMTP conversation (EHLO, MAIL FROM,
 * RCPT TO, DATA, QUIT) against a local fake relay standing in for the client's own SMTP relay, no Docker beyond the
 * database this suite already needs — the same spirit {@link WebPushChannelIT} already applies to its own protocol.
 * The fake server is started once, in a static initializer, so its port is known before {@link
 * DynamicPropertySource} configures the singleton {@link EmailChannel} bean the whole class shares.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class EmailChannelIT {

    private static final FakeSmtpServer SERVER = FakeSmtpServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", () -> newTempDir("qms-keys-email"));
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
        registry.add("qms.notification.email.host", () -> "127.0.0.1");
        registry.add("qms.notification.email.port", SERVER::port);
        registry.add("qms.notification.email.starttls", () -> "false");
        registry.add("qms.notification.email.from", () -> "noreply@qms.local");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    private static String newTempDir(String prefix) {
        try {
            return java.nio.file.Files.createTempDirectory(prefix).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EmailChannel channel;

    @BeforeEach
    void clearReceived() {
        SERVER.received().clear();
    }

    private UUID newVisitor(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, email, created_at) VALUES (?, ?, 'Karim', 'general', ?, now())",
                id, "V-" + id.toString().substring(0, 8), email);
        return id;
    }

    private static NotificationMessageRepository.MessageRow messageFor(UUID visitorId, String subject, String body) {
        return new NotificationMessageRepository.MessageRow(
                UUID.randomUUID(), "appointment_confirmed", "email", List.of("email", "web_push"), 0, "en", false, UUID.randomUUID(), UUID.randomUUID(), null, visitorId,
                Map.of(), subject, body, "queued", 0, Instant.now(), null);
    }

    @Test
    void aVisitorWithAnEmailReceivesARealSmtpDeliveredMessage() throws Exception {
        UUID visitor = newVisitor("visitor@example.com");

        NotificationChannel.Outcome outcome = channel.send(messageFor(visitor, "Your appointment is confirmed", "See you at the clinic on the booked slot."));

        assertThat(outcome.success()).isTrue();
        FakeSmtpServer.Received received = SERVER.received().poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.from()).isEqualTo("noreply@qms.local");
        assertThat(received.to()).isEqualTo("visitor@example.com");
        assertThat(received.data()).contains("Subject: Your appointment is confirmed").contains("See you at the clinic on the booked slot.");
    }

    @Test
    void aVisitorWithNoEmailOnFileFailsWithNoEmailAndNeverConnects() {
        UUID visitor = newVisitor(null);

        NotificationChannel.Outcome outcome = channel.send(messageFor(visitor, "Subject", "Body"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.providerResponse()).isEqualTo("no_email");
        assertThat(SERVER.received()).isEmpty();
    }

    @Test
    void aMessageWithNoVisitorFailsWithNoVisitor() {
        NotificationChannel.Outcome outcome = channel.send(messageFor(null, "Subject", "Body"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.providerResponse()).isEqualTo("no_visitor");
    }

    // ---- a minimal fake SMTP relay: just enough of RFC 5321 for a plain, unauthenticated, non-TLS send ------------

    static final class FakeSmtpServer {
        private static final Pattern ADDRESS = Pattern.compile("<([^>]*)>");

        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final BlockingQueue<Received> received = new ArrayBlockingQueue<>(10);

        record Received(String from, String to, String data) {}

        private FakeSmtpServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.acceptThread = new Thread(this::acceptLoop, "fake-smtp-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        static FakeSmtpServer start() {
            try {
                ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                return new FakeSmtpServer(socket);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        BlockingQueue<Received> received() {
            return received;
        }

        void stop() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    handle(socket);
                } catch (IOException e) {
                    return; // socket closed on shutdown
                }
            }
        }

        private void handle(Socket socket) {
            try (socket;
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false)) {
                reply(out, "220 localhost fake SMTP ready");
                String from = null;
                String to = null;
                String line;
                while ((line = in.readLine()) != null) {
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        reply(out, "250 localhost");
                    } else if (upper.startsWith("MAIL FROM")) {
                        from = address(line);
                        reply(out, "250 OK");
                    } else if (upper.startsWith("RCPT TO")) {
                        to = address(line);
                        reply(out, "250 OK");
                    } else if (upper.startsWith("DATA")) {
                        reply(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                        StringBuilder data = new StringBuilder();
                        String dataLine;
                        while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                            data.append(dataLine.startsWith("..") ? dataLine.substring(1) : dataLine).append('\n');
                        }
                        received.add(new Received(from, to, data.toString()));
                        reply(out, "250 OK: queued");
                    } else if (upper.startsWith("QUIT")) {
                        reply(out, "221 Bye");
                        return;
                    } else if (upper.startsWith("RSET")) {
                        reply(out, "250 OK");
                    } else if (upper.startsWith("NOOP")) {
                        reply(out, "250 OK");
                    } else {
                        reply(out, "500 unrecognized command");
                    }
                }
            } catch (IOException ignored) {
                // client disconnected; nothing more to serve
            }
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        private static String address(String commandLine) {
            Matcher matcher = ADDRESS.matcher(commandLine);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
