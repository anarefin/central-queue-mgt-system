package com.qms.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.integration.webhook.WebhookDeliveryRepository.AttemptRow;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The delivery worker against real PostgreSQL and a real HTTP server standing in for the receiving endpoint (ticket
 * 57, FR-INT-021, no Docker beyond the database this suite already needs): a real HMAC-SHA256 signature over the
 * timestamp and body, a successful delivery, retry with backoff on failure, and terminal failure once every attempt
 * is exhausted.
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, WebhookDeliveryWorkerIT.Clocks.class})
class WebhookDeliveryWorkerIT {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.now();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", () -> newKeyDir());
        registry.add("qms.webhook.send-poll-cron", () -> "-");
        registry.add("qms.webhook.max-attempts", () -> "3");
        registry.add("qms.webhook.backoff-base-seconds", () -> "30");
        // This suite's own fake receiver is plain HTTP on loopback, so it needs the test-only bypass of
        // WebhookEndpointSecurity's HTTPS-and-not-private-address check; no environment variable exposes this in
        // application.yml, so it can only ever be turned on by a test setting the Spring property directly.
        registry.add("qms.webhook.allow-insecure-endpoints-for-tests", () -> "true");
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static String newKeyDir() {
        try {
            return java.nio.file.Files.createTempDirectory("qms-keys-webhook-worker").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired WebhookDeliveryWorker worker;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired WebhookDeliveryRepository deliveries;

    static final Instant BASE = Instant.parse("2026-09-21T04:00:00Z");
    static final String SECRET = "shared-secret";

    private HttpServer server;
    private final AtomicInteger nextStatus = new AtomicInteger(200);
    private final BlockingQueue<Recorded> received = new ArrayBlockingQueue<>(10);

    private record Recorded(String signature, String timestamp, String eventHeader, String body) {}

    @BeforeEach
    void startAtBaseAndFakeReceiver() throws IOException {
        clock.set(BASE);
        // worker.tick() sweeps every due delivery with no scoping of its own; this class's tests share one Postgres
        // container with no rollback between them, so a delivery an earlier test method left "queued" (its
        // next_attempt_at not yet due against the clock reset above, but possibly due once a later test advances
        // the clock) would otherwise be swept up here too.
        jdbc.update("DELETE FROM webhook_delivery_attempt");
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_event");
        jdbc.update("DELETE FROM webhook_endpoint");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", this::handle);
        server.start();
    }

    @AfterEach
    void stopFakeReceiver() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Recorded(
                exchange.getRequestHeaders().getFirst("X-QMS-Webhook-Signature"),
                exchange.getRequestHeaders().getFirst("X-QMS-Webhook-Timestamp"),
                exchange.getRequestHeaders().getFirst("X-QMS-Webhook-Event"),
                new String(body, StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(nextStatus.get(), -1);
        exchange.close();
    }

    private String endpointUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks";
    }

    // ---- fixtures: a queued delivery, built through the real create + event/delivery insert path -------------------

    private UUID newDelivery(String eventType, Map<String, Object> data) {
        UUID endpointId = endpoints.insert("Test", endpointUrl(), List.of(eventType), SECRET, UUID.randomUUID(), clock.instant());
        UUID eventId = deliveries.recordEvent("key-" + UUID.randomUUID(), eventType, clock.instant(), data, clock.instant()).orElseThrow();
        return deliveries.queueDelivery(eventId, endpointId, eventType, clock.instant());
    }

    private String status(UUID deliveryId) {
        return jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?", String.class, deliveryId);
    }

    private Map<String, Object> row(UUID deliveryId) {
        return jdbc.queryForMap("SELECT status, attempt_count FROM webhook_delivery WHERE id = ?", deliveryId);
    }

    // ---- FR-INT-021: signed, delivered, logged --------------------------------------------------------------------

    @Test
    void aQueuedDeliveryIsSentWithAValidHmacSignatureAndMarkedSent() throws Exception {
        UUID id = newDelivery("ticket.called", Map.of("ticket_id", "t-1", "token_number", "A-001"));

        int processed = worker.tick();

        assertThat(processed).isEqualTo(1);
        assertThat(status(id)).isEqualTo("sent");
        Recorded request = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.eventHeader()).isEqualTo("ticket.called");
        assertThat(request.body()).contains("\"token_number\":\"A-001\"").contains("\"type\":\"ticket.called\"");
        String expectedSignature = WebhookSigner.sign(SECRET, Long.parseLong(request.timestamp()), request.body());
        assertThat(request.signature()).isEqualTo(expectedSignature);

        List<AttemptRow> attempts = deliveries.attemptsOf(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).success()).isTrue();
        assertThat(attempts.get(0).responseStatus()).isEqualTo(200);
    }

    @Test
    void aFailingEndpointRetriesWithBackoffThenIsTerminallyFailedOnceAttemptsAreExhausted() {
        UUID id = newDelivery("ticket.called", Map.of("ticket_id", "t-1"));
        nextStatus.set(500);

        worker.tick(); // attempt 1/3: fails, retry scheduled after the base backoff
        assertThat(status(id)).isEqualTo("queued");
        assertThat(row(id).get("attempt_count")).isEqualTo(1);

        clock.set(clock.instant().plusSeconds(31));
        worker.tick(); // attempt 2/3: fails, backoff doubles
        assertThat(status(id)).isEqualTo("queued");
        assertThat(row(id).get("attempt_count")).isEqualTo(2);

        clock.set(clock.instant().plusSeconds(61));
        worker.tick(); // attempt 3/3: fails, exhausted
        assertThat(status(id)).isEqualTo("failed");

        List<AttemptRow> attempts = deliveries.attemptsOf(id);
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allMatch(a -> !a.success() && a.responseStatus() == 500);
    }

    @Test
    void aDeliveryNotYetDueIsNotAttempted() {
        UUID id = newDelivery("ticket.called", Map.of("ticket_id", "t-1"));
        nextStatus.set(500);
        worker.tick(); // attempt 1/3: fails, backoff scheduled 30s out

        clock.set(clock.instant().plusSeconds(5)); // still within the backoff window
        int processed = worker.tick();

        assertThat(processed).isZero();
        assertThat(status(id)).isEqualTo("queued");
    }

    // ---- FR-INT-021: manual replay from the delivery log --------------------------------------------------------

    @Test
    void aReplayedFailedDeliveryIsSentAgainAtOnce() {
        UUID id = newDelivery("ticket.called", Map.of("ticket_id", "t-1"));
        nextStatus.set(500);
        worker.tick(); // attempt 1/3
        clock.set(clock.instant().plusSeconds(31));
        worker.tick(); // attempt 2/3
        clock.set(clock.instant().plusSeconds(61));
        worker.tick(); // attempt 3/3: exhausted, terminally failed
        assertThat(status(id)).isEqualTo("failed");

        nextStatus.set(200);
        deliveries.resetForReplay(id, clock.instant());
        worker.tick();

        assertThat(status(id)).isEqualTo("sent");
    }
}
