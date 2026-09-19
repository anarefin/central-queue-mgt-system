package com.qms.platform.realtime;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
 * {@code {topic, last_seq, epoch}}, {@code unsubscribe} with topic names, {@code heartbeat}, and {@code reauth} with a fresh {@code token}. From the hub: {@code snapshot} ({@code topic, seq, epoch,
 * resync, data}), {@code replay} (a catch-up from a buffer, followed by the missed events), {@code event} (the §21.3
 * envelope), {@code denied} ({@code topic, code}), {@code error} and {@code heartbeat}. Every frame carries {@code frame};
 * the event envelope keeps its own {@code type} member, so the two never collide.
 *
 * <p>A socket cannot outlive its credentials (ADR-0009, §21.1): it is closed with {@link #TOKEN_EXPIRED} when its token
 * expires unless a {@code reauth} frame with a fresh token for the same subject arrived first (the hub answers with a
 * {@code reauth} frame carrying {@code expires_at}, or an {@code error} frame with {@code unauthorized} and changes
 * nothing), and closed with {@link #PRINCIPAL_CHANGED} the moment its subject is disabled or has its roles or scopes
 * changed. After that the hub refuses any token issued before the change for that subject, so a client cannot simply
 * reconnect with the token it had; a fresh token carries the new claims, and topics are authorised against them.
 */
@Component
public class RealtimeHub implements RealtimePublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    /** The close code for a client that stopped sending heartbeats. */
    static final int HEARTBEAT_TIMEOUT = 4408;
    /** The close code for a socket whose token expired without a {@code reauth}. */
    static final int TOKEN_EXPIRED = 4401;
    /** The close code for a socket whose subject was disabled or changed (FR-QUE-080). */
    static final int PRINCIPAL_CHANGED = 4403;
    /** How long a {@code principal.changed} is remembered: longer than any access token lives (15 minutes at most, API-013). */
    private static final Duration REVOCATION_MEMORY = Duration.ofMinutes(20);
    private static final Duration MIN_RETRY = Duration.ofSeconds(1);

    private final List<TopicSource> sources;
    private final RealtimeProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Optional<TokenVerifier> verifier;
    /** When each subject last changed: a token issued before that is no longer good for a socket. */
    private final Map<String, Instant> changes = new ConcurrentHashMap<>();
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    RealtimeHub(List<TopicSource> sources, RealtimeProperties properties, JsonMapper mapper, Clock clock, Optional<TokenVerifier> verifier) {
        this.verifier = verifier;
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
        afterCommit(dispatch);
    }

    @Override
    public void principalChanged(String subject) {
        afterCommit(() -> {
            try {
                changes.put(subject, clock.instant());
                for (Connection connection : new ArrayList<>(connections)) {
                    if (subject.equals(connection.subject())) drop(connection, PRINCIPAL_CHANGED, "principal changed");
                }
            } catch (RuntimeException failure) {
                log.warn("realtime principal.changed for {} failed: {}", subject, failure.toString());
            }
        });
    }

    /** Runs {@code action} now, or, inside a transaction, once it commits, so a rolled-back change announces nothing. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ---- connections ------------------------------------------------------------------------------------------

    Connection open(Outbound out, Authentication authentication) {
        Connection connection = new Connection(out, authentication, clock.instant());
        if (changed(authentication)) {
            // The token predates a change to its subject; a fresh one is needed for the new claims (ADR-0009).
            connection.close(PRINCIPAL_CHANGED, "principal changed");
            return connection;
        }
        connections.add(connection);
        startHeartbeat();
        scheduleExpiry(connection);
        return connection;
    }

    void close(Connection connection) {
        connection.expiry(null);
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
        if ("reauth".equals(kind)) {
            reauth(connection, frame.get("token"));
            return;
        }
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

    // ---- credentials that expire or change (ADR-0009) ---------------------------------------------------------

    /**
     * Accepts a fresh token for the same subject in place of the one the socket has, so it lives on past the old expiry. A
     * token that is refused (invalid, another subject's, or issued before the subject changed) leaves the socket as it
     * was, to close at its old expiry, and the client is told.
     */
    private void reauth(Connection connection, Object token) {
        Authentication fresh = null;
        if (token instanceof String text && verifier.isPresent()) {
            try {
                fresh = verifier.get().verify(text);
            } catch (AuthenticationException | IllegalArgumentException refused) {
                log.debug("realtime reauth refused: {}", refused.toString());
            }
        }
        if (fresh == null || !Objects.equals(fresh.getName(), connection.subject()) || changed(fresh) || expired(fresh)) {
            connection.send(error("unauthorized"));
            return;
        }
        Authentication accepted = fresh;
        connection.reauthenticate(accepted);
        scheduleExpiry(connection);
        // The new claims may allow less than the old ones did: keep only the topics they still allow.
        for (Map.Entry<String, Subscription> entry : new ArrayList<>(connection.subscriptions().entrySet())) {
            String name = entry.getKey();
            Optional<TopicSource> owner = source(name);
            String refusal = owner.isEmpty() ? "unknown_topic" : asUser(accepted, () -> authorise(owner.get(), name));
            if (refusal == null) continue;
            connection.subscriptions().remove(name);
            topic(name).detach(entry.getValue());
            connection.send(denied(name, refusal));
        }
        Instant expiresAt = connection.expiresAt();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("frame", "reauth");
        ack.put("expires_at", expiresAt == null ? null : expiresAt.toString());
        connection.send(mapper.writeValueAsString(ack));
    }

    /**
     * Whether the token may predate its subject's last change. A token's {@code iat} has whole seconds only, so one issued in
     * the second of the change cannot be told from before or after and is refused; the client that is turned away comes back a
     * moment later with a token from a later second.
     */
    private boolean changed(Authentication authentication) {
        Instant at = changes.get(authentication.getName());
        if (at == null) return false;
        Instant issued = Connection.issuedAt(authentication);
        return issued == null || !issued.isAfter(at.truncatedTo(ChronoUnit.SECONDS));
    }

    private boolean expired(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getExpiresAt() != null
                && !clock.instant().isBefore(jwt.getToken().getExpiresAt());
    }

    /** Closes the connection with {@code code} and ends its subscriptions at once, without waiting for the transport to say so. */
    private void drop(Connection connection, int code, String reason) {
        connection.close(code, reason);
        close(connection);
    }

    /** Sets the timer that closes the connection when its token expires, replacing any earlier one. */
    private synchronized void scheduleExpiry(Connection connection) {
        Instant expiresAt = connection.expiresAt();
        if (expiresAt == null || scheduler == null || closed) return;
        long millis = Math.max(Duration.between(clock.instant(), expiresAt).toMillis(), 0);
        connection.expiry(scheduler.schedule(() -> expireIfDue(connection), millis, TimeUnit.MILLISECONDS));
    }

    private void expireIfDue(Connection connection) {
        if (!connections.contains(connection)) return;
        if (isDue(connection, clock.instant())) drop(connection, TOKEN_EXPIRED, "token expired");
        else scheduleAgain(connection);
    }

    /** The timer fired before the hub's clock reached the expiry: look again shortly rather than spin. */
    private synchronized void scheduleAgain(Connection connection) {
        Instant expiresAt = connection.expiresAt();
        if (expiresAt == null || scheduler == null || closed) return;
        long millis = Math.max(Duration.between(clock.instant(), expiresAt).toMillis(), MIN_RETRY.toMillis());
        connection.expiry(scheduler.schedule(() -> expireIfDue(connection), millis, TimeUnit.MILLISECONDS));
    }

    private static boolean isDue(Connection connection, Instant now) {
        Instant expiresAt = connection.expiresAt();
        return expiresAt != null && !now.isBefore(expiresAt);
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
        changes.values().removeIf(at -> at.plus(REVOCATION_MEMORY).isBefore(now));
        for (Connection connection : new ArrayList<>(connections)) {
            if (isDue(connection, now)) {
                drop(connection, TOKEN_EXPIRED, "token expired");
            } else if (Duration.between(connection.lastReceived(), now).compareTo(silence) > 0) {
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
