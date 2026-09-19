package com.qms.platform.realtime;

import java.time.Instant;
import java.util.Map;

/**
 * The one way an event reaches the realtime hub (SRS §21). A bounded context publishes to a topic and never learns who is
 * listening or on which node. Cross-node fan-out (ticket 59, ADR-0010) is a second implementation of this interface, not a
 * change to any publisher.
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
}
