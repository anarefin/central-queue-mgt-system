package com.qms.platform.realtime;

import java.time.Instant;
import java.util.Map;

/**
 * A Spring application event mirroring exactly what {@link RealtimePublisher#publish} just gave the hub: the one,
 * decoupled place a downstream consumer that cares about every §21.4 event type — not one topic's subscribers, but
 * the event itself — can listen from, without that consumer (or this package) knowing about each other. Outbound
 * webhooks (ticket 57, FR-INT-020) are the first such consumer; a future one needs no change here.
 *
 * <p>Published once per {@link RealtimePublisher#publish} call, after the same after-commit ordering the hub itself
 * uses (a rolled-back transition raises nothing). A single business transition that fans out to several topics
 * (queue:/ticket:/counter:/zone:, see {@code queue.TicketEvents}) raises one of these per topic, with the same
 * {@code type}, {@code occurredAt} and {@code data} each time; a consumer that must see one business event once,
 * not once per topic, dedupes on those three fields.
 */
public record RealtimeEventOccurred(String topic, String type, Instant occurredAt, Map<String, Object> data) {}
