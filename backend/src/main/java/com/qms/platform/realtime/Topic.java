package com.qms.platform.realtime;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * One topic: its monotonic {@code seq} (FR-QUE-081), the buffer that lets a reconnecting client catch up, and the
 * subscriptions that are told of each event. Every method takes the topic's lock, so events reach every subscriber in
 * {@code seq} order and a subscriber is never attached halfway through one.
 *
 * <p>{@code seq} lives in memory. The {@code epoch} names this run of the topic: a client that reconnects after a
 * restart presents an epoch the hub does not know and is given a fresh snapshot rather than a replay of unrelated
 * numbers (cross-node sequencing arrives with ticket 59).
 */
final class Topic {

    private final String name;
    private final String epoch = UUID.randomUUID().toString();
    private final JsonMapper mapper;
    private final RealtimeProperties properties;
    private long seq;
    private final ArrayDeque<Envelope> buffer = new ArrayDeque<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    Topic(String name, JsonMapper mapper, RealtimeProperties properties) {
        this.name = name;
        this.mapper = mapper;
        this.properties = properties;
    }

    String epoch() {
        return epoch;
    }

    synchronized long head() {
        return seq;
    }

    /** Gives the event the next seq, keeps it for replay and hands it to every subscription. */
    synchronized Envelope publish(String type, Instant occurredAt, Map<String, Object> data, Instant now) {
        long next = ++seq;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("frame", "event");
        frame.put("topic", name);
        frame.put("seq", next);
        frame.put("type", type);
        frame.put("occurred_at", occurredAt.atOffset(ZoneOffset.UTC).toString());
        frame.put("data", data);
        Envelope event = new Envelope(next, mapper.writeValueAsString(frame), now);
        buffer.addLast(event);
        trim(now);
        for (Subscription subscription : subscriptions) subscription.deliver(event);
        return event;
    }

    /** Keeps at least the configured number of events and at least the configured window (FR-QUE-081), and nothing older than both. */
    private void trim(Instant now) {
        Instant oldestKept = now.minus(properties.replayWindow());
        while (buffer.size() > properties.replayEvents() && buffer.peekFirst().recorded().isBefore(oldestKept)) buffer.removeFirst();
    }

    /** Starts a subscription that holds its deltas until {@link #live}; returns it with the seq the snapshot will stand for. */
    synchronized Sync begin(Connection connection) {
        Subscription subscription = new Subscription(connection, true);
        subscriptions.add(subscription);
        return new Sync(subscription, seq);
    }

    record Sync(Subscription subscription, long seq) {}

    synchronized void live(Subscription subscription, String snapshotFrame, long afterSeq) {
        if (!subscription.detached()) subscription.live(snapshotFrame, afterSeq);
    }

    /**
     * Catches a client up from its last seen seq (FR-QUE-081). Returns null when it cannot: the epoch is not this one, the
     * client is ahead of the topic, or the events it missed have left the buffer. The caller then sends a snapshot.
     */
    synchronized Subscription replay(Connection connection, long lastSeq, String clientEpoch) {
        if (!epoch.equals(clientEpoch) || lastSeq < 0 || lastSeq > seq) return null;
        if (lastSeq < seq && (buffer.isEmpty() || buffer.peekFirst().seq() > lastSeq + 1)) return null;
        List<Envelope> missed = buffer.stream().filter(e -> e.seq() > lastSeq).toList();
        Subscription subscription = new Subscription(connection, false);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("frame", "replay");
        ack.put("topic", name);
        ack.put("seq", seq);
        ack.put("epoch", epoch);
        ack.put("count", missed.size());
        subscription.send(mapper.writeValueAsString(ack));
        for (Envelope event : missed) subscription.send(event.frame());
        subscriptions.add(subscription);
        return subscription;
    }

    synchronized void detach(Subscription subscription) {
        subscription.detach();
        subscriptions.remove(subscription);
    }

    synchronized int subscribers() {
        return subscriptions.size();
    }
}
