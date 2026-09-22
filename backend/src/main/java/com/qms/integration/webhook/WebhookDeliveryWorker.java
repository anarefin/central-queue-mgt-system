package com.qms.integration.webhook;

import com.qms.integration.webhook.WebhookDeliveryRepository.DueDelivery;
import com.qms.platform.Profiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends due deliveries (FR-INT-021), off the request thread entirely, driven by {@link WebhookDeliveryScheduler} —
 * the same shape {@code notification.NotificationSendWorker} already gives ticket 38's own delivery pipeline. Each
 * attempt is its own transaction so one delivery's failure never blocks the batch, and a slow or hanging endpoint is
 * bounded by {@link WebhookProperties#requestTimeoutSeconds()} so it can never hold this worker, let alone a queue
 * action, for long (FR-INT-022). A failed attempt retries with exponential backoff up to
 * {@link WebhookProperties#maxAttempts()}, after which the delivery is terminally {@code failed} until an admin
 * replays it from the delivery log.
 */
@Component
@Profile(Profiles.SERVING)
class WebhookDeliveryWorker {

    private static final int BATCH_SIZE = 50;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookProperties properties;
    private final Clock clock;
    private final JsonMapper mapper;
    private final HttpClient http;

    WebhookDeliveryWorker(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, WebhookProperties properties, Clock clock, JsonMapper mapper) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper;
        // Never follow a redirect: an endpoint has no legitimate reason to send one, and following one would let an
        // initial, validated (WebhookEndpointSecurity) host redirect this request anywhere afterwards.
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds())).build();
    }

    /** One sweep: every due delivery, each in its own transaction. */
    int tick() {
        Instant now = clock.instant();
        List<DueDelivery> due = deliveries.due(now, BATCH_SIZE);
        for (DueDelivery delivery : due) attempt(delivery, now);
        return due.size();
    }

    @Transactional
    void attempt(DueDelivery delivery, Instant now) {
        int attemptNo = delivery.attemptCount() + 1;
        Outcome outcome = send(delivery, now);
        deliveries.insertAttempt(delivery.deliveryId(), attemptNo, outcome.success(), outcome.status(), outcome.error(), now);

        if (outcome.success()) {
            deliveries.markSent(delivery.deliveryId(), now);
            return;
        }
        boolean exhausted = attemptNo >= properties.maxAttempts();
        if (exhausted) {
            deliveries.markFailed(delivery.deliveryId(), attemptNo, outcome.error());
        } else {
            deliveries.retry(delivery.deliveryId(), attemptNo, now.plusSeconds(backoffSeconds(attemptNo)), outcome.error());
        }
    }

    private Outcome send(DueDelivery delivery, Instant now) {
        Optional<String> secret = endpoints.secretOf(delivery.endpointId());
        if (secret.isEmpty()) return Outcome.failure(null, "endpoint_not_found");
        try {
            // Re-checked immediately before every send, not just when the endpoint was created: DNS can rebind between the two.
            WebhookEndpointSecurity.requireSafe(delivery.endpointUrl(), properties.allowInsecureEndpointsForTests());
        } catch (WebhookEndpointSecurity.UnsafeEndpointException unsafe) {
            return Outcome.failure(null, "unsafe_endpoint:" + unsafe.getMessage());
        }

        String body = mapper.writeValueAsString(payload(delivery));
        long timestamp = now.getEpochSecond();
        String signature = WebhookSigner.sign(secret.get(), timestamp, body);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.endpointUrl()))
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-QMS-Webhook-Id", delivery.deliveryId().toString())
                    .header("X-QMS-Webhook-Event", delivery.eventType())
                    .header("X-QMS-Webhook-Timestamp", Long.toString(timestamp))
                    .header("X-QMS-Webhook-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? Outcome.success(status) : Outcome.failure(status, "http_" + status);
        } catch (IOException networkFailure) {
            return Outcome.failure(null, "network_error:" + networkFailure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failure(null, "interrupted");
        }
    }

    private static Map<String, Object> payload(DueDelivery delivery) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", delivery.deliveryId().toString());
        body.put("type", delivery.eventType());
        body.put("occurred_at", delivery.occurredAt().toString());
        body.put("data", delivery.data());
        return body;
    }

    private long backoffSeconds(int attemptNo) {
        return (long) properties.backoffBaseSeconds() * (1L << Math.max(0, attemptNo - 1));
    }

    private record Outcome(boolean success, Integer status, String error) {
        static Outcome success(int status) {
            return new Outcome(true, status, null);
        }

        static Outcome failure(Integer status, String error) {
            return new Outcome(false, status, error);
        }
    }
}
