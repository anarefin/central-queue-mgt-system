package com.qms.platform.realtime;

import java.time.Instant;
import java.util.Map;

/**
 * The one way an event reaches the realtime hub (SRS §21). A bounded context publishes to a topic and never learns who is
 * listening or on which node. Cross-node fan-out (ticket 59, ADR-0010) is {@link ClusterRealtimeFanout} over PostgreSQL
 * {@code LISTEN}/{@code NOTIFY} — no broker, no change to any publisher: it wraps {@link RealtimeHub} for local delivery
 * and broadcasts the same event to every other node. {@link RealtimeHub} itself no longer implements this interface, so
 * {@code ClusterRealtimeFanout} is the only production bean that does — no {@code @Primary} needed to prefer it over
 * {@link RealtimeHub}, which leaves the annotation free for a test to install its own recording double instead.
 *
 * <p>An event published inside a transaction is delivered only once that transaction commits, so a rolled-back transition
 * is never announced and a subscriber who reads the database on hearing an event sees the change.
 */
public interface RealtimePublisher {

    /**
     * @param topic one of the §21.2 topics, built with {@link Topics}
     * @param type an event type from §21.4, such as {@code ticket.called}
     * @param occurredAt when it happened, as the originating device or the server knew it
     * @param data the event's payload; it must be JSON-serialisable
     */
    void publish(String topic, String type, Instant occurredAt, Map<String, Object> data);

    /**
     * The internal {@code principal.changed(sub)} event (ADR-0009): the subject was disabled, or its roles, scopes or device
     * were changed or revoked. The hub drops that subject's sockets at once and will not take a token issued before now for
     * it again, so the client comes back with a fresh token that carries the new claims. Like a published event it takes
     * effect only once the surrounding transaction commits.
     *
     * @param subject the token's {@code sub}
     */
    void principalChanged(String subject);
}
