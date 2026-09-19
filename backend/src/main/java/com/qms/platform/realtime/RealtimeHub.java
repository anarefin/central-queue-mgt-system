package com.qms.platform.realtime;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The realtime hub (SRS §21), running inside the backend (ADR-0010). Contexts publish events through
 * {@link RealtimePublisher}; clients connect over the WebSocket in {@link StreamHandler}, subscribe to topics and get a
 * snapshot of each and then its deltas in {@code seq} order.
 *
 * <p>Frames are JSON text. From the client: {@code subscribe} with {@code topics}, each a name or
 * {@code {topic, last_seq, epoch}}, {@code unsubscribe} with topic names, and {@code heartbeat}. From the hub: {@code snapshot} ({@code topic, seq, epoch,
 * resync, data}), {@code replay} (a catch-up from a buffer, followed by the missed events), {@code event} (the §21.3
 * envelope), {@code denied} ({@code topic, code}), {@code error} and {@code heartbeat}. Every frame carries {@code frame};
 * the event envelope keeps its own {@code type} member, so the two never collide.
 */
@Component
public class RealtimeHub implements RealtimePublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    /** The close code for a client that stopped sending heartbeats. */
    static final int HEARTBEAT_TIMEOUT = 4408;

    private final List<TopicSource> sources;
    private final RealtimeProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    RealtimeHub(List<TopicSource> sources, RealtimeProperties properties, JsonMapper mapper, Clock clock) {
        this.sources = List.copyOf(sources);
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- publishing -------------------------------------------------------------------------------------------

    @Override
    public void publish(String topic, String type, Instant occurredAt, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        Runnable dispatch = () -> {
            try {
                topic(topic).publish(type, occurredAt, payload, clock.instant());
            } catch (RuntimeException failure) {
                // The change is committed; a failed fan-out must not turn it into an error for the caller.
                log.warn("realtime publish to {} failed: {}", topic, failure.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    // ---- connections ------------------------------------------------------------------------------------------

    Connection open(Outbound out, Authentication authentication) {
        Connection connection = new Connection(out, authentication, clock.instant());
        connections.add(connection);
        startHeartbeat();
        return connection;
    }

    void close(Connection connection) {
        connections.remove(connection);
        for (Map.Entry<String, Subscription> entry : connection.subscriptions().entrySet()) {
            topic(entry.getKey()).detach(entry.getValue());
        }
        connection.subscriptions().clear();
    }

    int connectionCount() {
        return connections.size();
    }

    boolean running() {
        return !closed;
    }

    /** One frame from a client. Nothing a client sends can throw: a bad frame is answered with an {@code error} frame. */
    @SuppressWarnings("unchecked")
    void receive(Connection connection, String text) {
        connection.heardAt(clock.instant());
        Map<String, Object> frame;
        try {
            frame = mapper.readValue(text, Map.class);
        } catch (RuntimeException notJson) {
            connection.send(error("invalid_frame"));
            return;
        }
        Object kind = frame.get("frame");
        if ("heartbeat".equals(kind)) return;
        if ("subscribe".equals(kind) && frame.get("topics") instanceof List<?> requested) {
            for (Object entry : requested) subscribe(connection, entry);
            return;
        }
        if ("unsubscribe".equals(kind) && frame.get("topics") instanceof List<?> named) {
            for (Object entry : named) {
                if (!(entry instanceof String name)) continue;
                Subscription subscription = connection.subscriptions().remove(name);
                if (subscription != null) topic(name).detach(subscription);
            }
            return;
        }
        connection.send(error("invalid_frame"));
    }

    // ---- subscribing ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void subscribe(Connection connection, Object entry) {
        String name;
        Long lastSeq = null;
        String epoch = null;
        if (entry instanceof String plain) {
            name = plain;
        } else if (entry instanceof Map<?, ?> map && map.get("topic") instanceof String named) {
            name = named;
            if (map.get("last_seq") instanceof Number n) lastSeq = n.longValue();
            if (map.get("epoch") instanceof String e) epoch = e;
        } else {
            connection.send(error("invalid_frame"));
            return;
        }

        Optional<TopicSource> source = source(name);
        if (source.isEmpty()) {
            connection.send(denied(name, "unknown_topic"));
            return;
        }
        String refusal = asUser(connection.authentication(), () -> authorise(source.get(), name));
        if (refusal != null) {
            connection.send(denied(name, refusal));
            return;
        }

        // A second subscribe to a topic replaces the first: it is how a client asks to be caught up again.
        Topic topic = topic(name);
        Subscription previous = connection.subscriptions().remove(name);
        if (previous != null) topic.detach(previous);

        if (lastSeq != null) {
            Subscription replaying = topic.replay(connection, lastSeq, epoch);
            if (replaying != null) {
                connection.subscriptions().put(name, replaying);
                return;
            }
        }
        Topic.Sync sync = topic.begin(connection);
        connection.subscriptions().put(name, sync.subscription());
        Map<String, Object> data;
        try {
            data = asUser(connection.authentication(), () -> source.get().snapshot(name));
        } catch (RuntimeException failure) {
            log.warn("realtime snapshot of {} failed: {}", name, failure.toString());
            connection.subscriptions().remove(name);
            topic.detach(sync.subscription());
            connection.send(error(name, "internal_error"));
            return;
        }
        topic.live(sync.subscription(), snapshotFrame(name, sync.seq(), topic.epoch(), lastSeq != null, data), sync.seq());
    }

    /** The reason a subscriber is refused, or null when it may subscribe. */
    private static String authorise(TopicSource source, String topic) {
        try {
            source.authorize(topic);
            return null;
        } catch (ApiException refused) {
            return refused.code() == ErrorCode.VALIDATION_FAILED ? "invalid_topic" : refused.code().wire();
        } catch (AccessDeniedException refused) {
            return ErrorCode.FORBIDDEN.wire();
        }
    }

    /**
     * The snapshot of a topic for a client that polls (FR-QUE-084): the same authorisation and the same state a subscriber
     * gets first, with the seq and epoch it stands for. Runs as the caller of the request.
     */
    public Map<String, Object> snapshotOf(String name) {
        TopicSource source = source(name).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        source.authorize(name);
        Topic topic = topic(name);
        long seq = topic.head();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", name);
        result.put("seq", seq);
        result.put("epoch", topic.epoch());
        result.put("data", source.snapshot(name));
        return result;
    }

    // ---- heartbeat --------------------------------------------------------------------------------------------

    private synchronized void startHeartbeat() {
        if (scheduler != null || closed) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "realtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long millis = properties.heartbeatInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::beat, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Heartbeat each way (§21.1): tells every client the hub is alive, and drops a client that has said nothing for more
     * than its missed heartbeats and a grace of one interval, so a half-open connection does not hold subscriptions.
     */
    void beat() {
        Instant now = clock.instant();
        Duration silence = properties.heartbeatInterval().multipliedBy(properties.missedHeartbeats() + 1L);
        String heartbeat = heartbeat(now);
        for (Connection connection : new ArrayList<>(connections)) {
            if (Duration.between(connection.lastReceived(), now).compareTo(silence) > 0) {
                connection.close(HEARTBEAT_TIMEOUT, "heartbeat timeout");
            } else {
                connection.send(heartbeat);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (scheduler != null) scheduler.shutdownNow();
        for (Connection connection : new ArrayList<>(connections)) connection.close(1001, "going away");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Topic topic(String name) {
        return topics.computeIfAbsent(name, n -> new Topic(n, mapper, properties));
    }

    private Optional<TopicSource> source(String name) {
        return sources.stream().filter(s -> s.handles(name)).findFirst();
    }

    private static <T> T asUser(Authentication authentication, Supplier<T> action) {
        SecurityContext before = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    private String snapshotFrame(String topic, long seq, String epoch, boolean resync, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("frame", "snapshot");
        frame.put("topic", topic);
        frame.put("seq", seq);
        frame.put("epoch", epoch);
        frame.put("resync", resync);
        frame.put("data", data);
        return mapper.writeValueAsString(frame);
    }

    private String denied(String topic, String code) {
        return mapper.writeValueAsString(Map.of("frame", "denied", "topic", topic, "code", code));
    }

    private String error(String code) {
        return mapper.writeValueAsString(Map.of("frame", "error", "code", code));
    }

    private String error(String topic, String code) {
        return mapper.writeValueAsString(Map.of("frame", "error", "topic", topic, "code", code));
    }

    private String heartbeat(Instant now) {
        return mapper.writeValueAsString(Map.of("frame", "heartbeat", "time", now.toString()));
    }
}
