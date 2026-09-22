package com.qms.platform.realtime;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-node fan-out of realtime events and {@code principal.changed} (ticket 59, ADR-0010, FR-QUE-080): the second
 * implementation of {@link RealtimePublisher} ticket 11 left this seam for. Every node delivers to its own connections
 * directly, through {@link RealtimeHub}, exactly as a single node always has; this class additionally broadcasts the
 * same message over a PostgreSQL {@code NOTIFY} channel that every node {@code LISTEN}s on, so a node other than the
 * one that made the change tells its own subscribers too. No broker, no extra stateful component (ADR-0010) — only the
 * database every node already has, and it works the same whether the cluster has one node or several.
 *
 * <p>{@link RealtimeHub} itself no longer implements {@link RealtimePublisher} — this is the only production bean that
 * does, so every bounded context that publishes through {@link RealtimePublisher} reaches this without needing
 * {@code @Primary} (which would otherwise fight a test's own {@code @Primary} {@code RealtimePublisher} double, such as
 * the ones queue/WaitEstimationIT and issuance/ConfigVersioningIT use to record what gets published). A message this
 * node sent is recognised by its own {@code nodeId} and not re-applied when it comes back over the channel every node
 * (including the sender) receives it on.
 *
 * <p>Not {@code @Profile}-restricted, deliberately: {@link RealtimeHub} itself never was either, because a bean
 * outside {@code com.qms.platform.realtime} that is not itself profile-gated (such as
 * {@code dashboard.AlertAcknowledgeService}) still needs a {@link RealtimePublisher} to construct, even under the
 * {@code migrate}/{@code rotate-keys} profiles, which never actually publish anything — there is no web layer, and
 * no business transition runs, before the process does its one job and exits.
 */
@Component
public class ClusterRealtimeFanout implements RealtimePublisher, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClusterRealtimeFanout.class);

    static final String CHANNEL = "qms_realtime";
    /** PostgreSQL's own NOTIFY payload limit is 8000 bytes; §21.3 payloads are compact, so this is a generous margin. */
    private static final int MAX_PAYLOAD_BYTES = 7800;
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(2);
    private static final int POLL_TIMEOUT_MILLIS = 5000;

    private final RealtimeHub hub;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final String nodeId = UUID.randomUUID().toString();

    private volatile Thread listener;
    private volatile Connection current;
    private volatile boolean running;

    ClusterRealtimeFanout(RealtimeHub hub, DataSource dataSource, JdbcTemplate jdbc, JsonMapper mapper) {
        this.hub = hub;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- SmartLifecycle: the listener runs for as long as this node serves requests --------------------------------

    @Override
    public void start() {
        running = true;
        listener = new Thread(this::listen, "realtime-cluster-listen");
        listener.setDaemon(true);
        listener.start();
    }

    @Override
    public void stop() {
        running = false;
        Connection connection = current;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing to interrupt a blocked read; any failure here is moot, the loop is exiting either way
            }
        }
        if (listener != null) listener.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- RealtimePublisher: local delivery exactly as a single node always had, plus a broadcast ------------------

    @Override
    public void publish(String topic, String type, Instant occurredAt, Map<String, Object> data) {
        hub.publish(topic, type, occurredAt, data);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("node", nodeId);
        message.put("kind", "event");
        message.put("topic", topic);
        message.put("type", type);
        message.put("occurred_at", occurredAt.toString());
        message.put("data", data);
        broadcastAfterCommit(message);
    }

    @Override
    public void principalChanged(String subject) {
        hub.principalChanged(subject);
        broadcastAfterCommit(Map.of("node", nodeId, "kind", "principal", "subject", subject));
    }

    /** Runs {@code action} now, or, inside a transaction, once it commits — the same rule {@code RealtimeHub.publish}
     * itself applies, so a rolled-back transition is never broadcast either. */
    private void broadcastAfterCommit(Map<String, Object> message) {
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast(message);
                }
            });
        } else {
            broadcast(message);
        }
    }

    private void broadcast(Map<String, Object> message) {
        try {
            String payload = mapper.writeValueAsString(message);
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                log.warn("realtime cluster broadcast for topic {} exceeds {} bytes; other nodes will resync their own subscribers on reconnect instead",
                        message.get("topic"), MAX_PAYLOAD_BYTES);
                return;
            }
            jdbc.query("SELECT pg_notify(?, ?)", rs -> null, CHANNEL, payload);
        } catch (RuntimeException failure) {
            log.warn("realtime cluster broadcast failed: {}", failure.toString());
        }
    }

    // ---- listening: one dedicated connection, held for as long as the node runs -----------------------------------

    private void listen() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                current = connection;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_TIMEOUT_MILLIS);
                    if (notifications == null) continue;
                    for (PGNotification notification : notifications) apply(notification.getParameter());
                }
            } catch (Exception failure) {
                if (!running) return;
                log.warn("realtime cluster listener lost its connection, retrying: {}", failure.toString());
                sleep(RECONNECT_DELAY);
            } finally {
                current = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(String payload) {
        Map<String, Object> message;
        try {
            message = mapper.readValue(payload, Map.class);
        } catch (RuntimeException malformed) {
            log.warn("realtime cluster message could not be parsed: {}", malformed.toString());
            return;
        }
        if (nodeId.equals(message.get("node"))) return; // this node's own broadcast; already delivered locally
        try {
            if ("principal".equals(message.get("kind"))) {
                hub.principalChanged((String) message.get("subject"));
            } else if ("event".equals(message.get("kind"))) {
                hub.deliverRemote((String) message.get("topic"), (String) message.get("type"), Instant.parse((String) message.get("occurred_at")),
                        (Map<String, Object>) message.get("data"));
            }
        } catch (RuntimeException failure) {
            log.warn("realtime cluster message could not be applied: {}", failure.toString());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
