package com.qms.notification;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.notification.NotificationMessageRepository.MessageRow;
import com.qms.platform.Profiles;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Web Push adapter (ticket 39, FR-INT-040, §14.1, §18.3): RFC 8291-encrypts the message for each of the
 * ticket's own, still-active subscriptions and sends it with a VAPID {@code Authorization} header (RFC 8292). A
 * subscription the push service reports gone (404/410) is revoked here and then never tried again
 * ({@link PushSubscriptionRepository#revoke}); a ticket with none left (or none to start with) fails with nothing
 * more specific to blame than "no subscription", so {@link NotificationSendWorker} treats it exactly like any other
 * transient failure — retry, then fall back to the trigger's next channel (usually {@code in_app}).
 */
@Component
@Profile(Profiles.SERVING)
class WebPushChannel implements NotificationChannel {

    static final String KEY = "web_push";

    private final PushSubscriptionRepository subscriptions;
    private final VapidKeyStore vapid;
    private final WebPushProperties properties;
    private final AuditWriter audit;
    private final JsonMapper mapper;
    private final Clock clock;
    private final RestClient http;

    WebPushChannel(
            PushSubscriptionRepository subscriptions,
            VapidKeyStore vapid,
            WebPushProperties properties,
            AuditWriter audit,
            JsonMapper mapper,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.properties = properties;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        // Never follow a redirect: a push service has no legitimate reason to send one, and following one would let
        // an initial, validated (PushEndpointSecurity) host redirect this request anywhere afterwards (ticket 39).
        HttpClient noRedirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.http = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(noRedirects)).build();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Outcome send(MessageRow message) {
        if (message.ticketId() == null) return Outcome.failure("no_ticket_subscription");
        List<PushSubscriptionRepository.Row> subs = subscriptions.activeForTicket(message.ticketId());
        if (subs.isEmpty()) return Outcome.failure("no_subscription");

        byte[] payload = payload(message);
        boolean delivered = false;
        String lastFailure = null;
        for (PushSubscriptionRepository.Row sub : subs) {
            try {
                sendOne(sub, payload);
                delivered = true;
            } catch (GoneException gone) {
                subscriptions.revoke(sub.endpoint(), gone.reason, clock.instant());
                audit.record(AuditEvent.of("notification.push_subscription_revoked", "ticket", message.ticketId()).withReason(gone.reason));
            } catch (PushEndpointSecurity.UnsafeEndpointException unsafe) {
                // Re-checked immediately before every send (not just at subscribe time), since DNS can rebind between the
                // two; an endpoint that fails this now is never worth trying again, so it is revoked exactly like a 410.
                subscriptions.revoke(sub.endpoint(), "unsafe_endpoint:" + unsafe.getMessage(), clock.instant());
                audit.record(AuditEvent.of("notification.push_subscription_revoked", "ticket", message.ticketId())
                        .withReason("unsafe_endpoint:" + unsafe.getMessage()));
            } catch (RuntimeException other) {
                lastFailure = other.getMessage();
            }
        }
        return delivered ? Outcome.success("delivered") : Outcome.failure(lastFailure == null ? "no_subscription" : lastFailure);
    }

    private void sendOne(PushSubscriptionRepository.Row sub, byte[] payload) {
        PushEndpointSecurity.requireSafe(sub.endpoint(), properties.allowInsecureEndpointsForTests());
        byte[] body = WebPushEncryption.encrypt(sub.p256dhBytes(), sub.authBytes(), payload);
        String authorization = VapidAuthorization.header(vapid, properties.subject(), sub.endpoint(), properties.jwtTtl(), clock);
        http.post()
                .uri(sub.endpoint())
                .header("Authorization", authorization)
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", Integer.toString(properties.ttlSeconds()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body)
                .retrieve()
                .onStatus(
                        status -> status.value() == 404 || status.value() == 410,
                        (req, res) -> {
                            throw new GoneException("gone_" + res.getStatusCode().value());
                        })
                .toBodilessEntity();
    }

    private byte[] payload(MessageRow message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_id", message.id().toString());
        data.put("trigger", message.triggerKey());
        data.put("subject", message.renderedSubject());
        data.put("body", message.renderedBody());
        return mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    }

    private static final class GoneException extends RuntimeException {
        private final String reason;

        GoneException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
