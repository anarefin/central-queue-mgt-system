package com.qms.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hub's contract with a client, driven with frames as text and no network (SRS §21). Covers §21.1 (subscribe,
 * snapshot then deltas, heartbeat each way), §21.3 (the envelope), FR-QUE-080 (authorised per topic at subscribe time)
 * and FR-QUE-081 (replay from a buffer, else a fresh snapshot with {@code resync: true}).
 */
class RealtimeHubTest {

    static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");
    static final String QUEUE = "queue:11111111-1111-1111-1111-111111111111";
    static final String COUNTER = "counter:22222222-2222-2222-2222-222222222222";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A source that owns topics starting {@code queue:} and {@code counter:}, refuses what {@code refuse} names and shows {@code state}. */
    static class FakeSource implements TopicSource {
        final List<String> refused = new ArrayList<>();
        final List<String> authorisedAs = new ArrayList<>();
        Map<String, Object> state = Map.of("waiting_count", 3);
        Consumer<String> onSnapshot = topic -> {};

        public boolean handles(String topic) {
            return topic.startsWith("queue:") || topic.startsWith("counter:");
        }

        public void authorize(String topic) {
            authorisedAs.add(String.valueOf(SecurityContextHolder.getContext().getAuthentication().getName()));
            if (refused.contains(topic)) throw new ApiException(ErrorCode.FORBIDDEN);
            if (topic.endsWith("not-a-uuid")) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }

        public Map<String, Object> snapshot(String topic) {
            onSnapshot.accept(topic);
            return state;
        }
    }

    /** A client's end of the socket: every frame the hub sent it, parsed. */
    static class Wire implements Outbound {
        final List<String> raw = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> closes = new ArrayList<>();

        public void send(String frame) {
            raw.add(frame);
        }

        public void close(int code, String reason) {
            closes.add(code);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames() {
            synchronized (raw) {
                return raw.stream().map(f -> (Map<String, Object>) MAPPER.readValue(f, Map.class)).toList();
            }
        }

        List<Map<String, Object>> frames(String kind) {
            return frames().stream().filter(f -> kind.equals(f.get("frame"))).toList();
        }

        Map<String, Object> last() {
            return frames().getLast();
        }
    }

    MutableClock clock = new MutableClock(T0);
    FakeSource source = new FakeSource();
    RealtimeProperties properties = RealtimeProperties.defaults();
    RealtimeHub hub;
    Authentication agent = new TestingAuthenticationToken("agent-1", "n/a", "perm:ticket:call_serve_complete:own");

    @BeforeEach
    void newHub() {
        hub = new RealtimeHub(List.of(source), properties, MAPPER, clock);
    }

    @AfterEach
    void stop() {
        hub.close();
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clear();
    }

    private Wire connect() {
        Wire wire = new Wire();
        connection = hub.open(wire, agent);
        return wire;
    }

    Connection connection;

    private void subscribe(String... topics) {
        hub.receive(connection, MAPPER.writeValueAsString(Map.of("frame", "subscribe", "topics", List.of(topics))));
    }

    private void subscribeFrom(String topic, long lastSeq, String epoch) {
        hub.receive(connection, MAPPER.writeValueAsString(Map.of("frame", "subscribe", "topics", List.of(Map.of("topic", topic, "last_seq", lastSeq, "epoch", epoch)))));
    }

    private void publish(String topic, String type, int n) {
        hub.publish(topic, type, T0, Map.of("n", n));
    }

    // ---- §21.1 subscribe, snapshot, then deltas; §21.3 the envelope -----------------------------------------------

    @Test
    void aSubscriberIsSentTheTopicsSnapshotFirstAndThenItsDeltasInOrder() {
        Wire wire = connect();
        subscribe(QUEUE);
        publish(QUEUE, "ticket.issued", 1);
        publish(QUEUE, "ticket.called", 2);

        List<Map<String, Object>> frames = wire.frames();
        assertThat(frames).extracting(f -> f.get("frame")).containsExactly("snapshot", "event", "event");
        assertThat(frames.get(0)).containsEntry("topic", QUEUE).containsEntry("seq", 0).containsEntry("resync", false).containsEntry("data", Map.of("waiting_count", 3));
        assertThat(frames.get(0).get("epoch")).isNotNull();
        assertThat(frames.get(1)).containsEntry("seq", 1).containsEntry("type", "ticket.issued");
        assertThat(frames.get(2)).containsEntry("seq", 2).containsEntry("type", "ticket.called");
    }

    @Test
    void anEventCarriesTopicSeqTypeOccurredAtAndData() {
        Wire wire = connect();
        subscribe(COUNTER);
        hub.publish(COUNTER, "session.opened", Instant.parse("2026-09-18T06:16:41Z"), Map.of("session_id", "s-1"));

        assertThat(wire.last())
                .containsEntry("topic", COUNTER)
                .containsEntry("seq", 1)
                .containsEntry("type", "session.opened")
                .containsEntry("occurred_at", "2026-09-18T06:16:41Z")
                .containsEntry("data", Map.of("session_id", "s-1"));
    }

    @Test
    void eachTopicCountsItsOwnSeqFromOneWithoutGaps() {
        Wire wire = connect();
        subscribe(QUEUE, COUNTER);
        publish(QUEUE, "ticket.issued", 1);
        publish(COUNTER, "session.opened", 1);
        publish(QUEUE, "ticket.called", 2);
        publish(QUEUE, "ticket.serving", 3);
        publish(COUNTER, "session.closed", 2);

        assertThat(seqs(wire, QUEUE)).containsExactly(1L, 2L, 3L);
        assertThat(seqs(wire, COUNTER)).containsExactly(1L, 2L);
    }

    @Test
    void aTopicOnlyTellsItsOwnSubscribers() {
        Wire onQueue = connect();
        subscribe(QUEUE);
        Wire onCounter = connect();
        subscribe(COUNTER);
        publish(QUEUE, "ticket.issued", 1);

        assertThat(onQueue.frames("event")).hasSize(1);
        assertThat(onCounter.frames("event")).isEmpty();
    }

    @Test
    void anEventPublishedWhileTheSnapshotIsBeingReadArrivesOnceAfterTheSnapshot() {
        Wire wire = connect();
        source.onSnapshot = topic -> publish(QUEUE, "ticket.issued", 1); // lands between "note the seq" and "send the snapshot"
        subscribe(QUEUE);
        publish(QUEUE, "ticket.called", 2);

        assertThat(wire.frames()).extracting(f -> f.get("frame")).containsExactly("snapshot", "event", "event");
        assertThat(wire.frames().get(0)).containsEntry("seq", 0);
        assertThat(seqs(wire, QUEUE)).containsExactly(1L, 2L);
    }

    @Test
    void anEventPublishedInATransactionIsDeliveredOnlyOnceItCommits() {
        Wire wire = connect();
        subscribe(QUEUE);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publish(QUEUE, "ticket.called", 1);
            assertThat(wire.frames("event")).isEmpty();
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }
        assertThat(wire.frames("event")).hasSize(1);
    }

    @Test
    void anEventPublishedInATransactionThatRollsBackIsNeverDelivered() {
        Wire wire = connect();
        subscribe(QUEUE);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publish(QUEUE, "ticket.called", 1);
            TransactionSynchronizationUtils.invokeAfterCompletion(TransactionSynchronizationManager.getSynchronizations(), org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }
        assertThat(wire.frames("event")).isEmpty();
        hub.receive(connection, MAPPER.writeValueAsString(Map.of("frame", "subscribe", "topics", List.of(QUEUE))));
        assertThat(wire.frames("snapshot").getLast()).containsEntry("seq", 0);
    }

    // ---- FR-QUE-080 authorisation at subscribe time ---------------------------------------------------------------

    @Test
    void aTopicTheSubscriberMayNotSeeIsRefusedWithNoSnapshotAndNoDeltas() {
        source.refused.add(QUEUE);
        Wire wire = connect();
        subscribe(QUEUE, COUNTER);
        publish(QUEUE, "ticket.issued", 1);
        publish(COUNTER, "session.opened", 1);

        assertThat(wire.frames("denied")).singleElement().satisfies(f -> assertThat(f).containsEntry("topic", QUEUE).containsEntry("code", "forbidden"));
        assertThat(wire.frames("snapshot")).extracting(f -> f.get("topic")).containsExactly(COUNTER);
        assertThat(wire.frames("event")).extracting(f -> f.get("topic")).containsExactly(COUNTER);
    }

    @Test
    void topicsAreAuthorisedAsTheConnectionsOwnerNotAsWhoeverRunsTheHubThread() {
        Wire wire = connect();
        subscribe(QUEUE);
        assertThat(source.authorisedAs).containsExactly("agent-1");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(wire.frames("snapshot")).hasSize(1);
    }

    @Test
    void aTopicNoOneOwnsAndAMalformedTopicAreRefusedByName() {
        Wire wire = connect();
        subscribe("nonsense:1", "queue:not-a-uuid");

        assertThat(wire.frames("denied")).extracting(f -> f.get("code")).containsExactly("unknown_topic", "invalid_topic");
    }

    @Test
    void anUnsubscribedTopicIsNoLongerSent() {
        Wire wire = connect();
        subscribe(QUEUE, COUNTER);
        hub.receive(connection, MAPPER.writeValueAsString(Map.of("frame", "unsubscribe", "topics", List.of(QUEUE))));
        publish(QUEUE, "ticket.issued", 1);
        publish(COUNTER, "session.opened", 1);

        assertThat(wire.frames("event")).extracting(f -> f.get("topic")).containsExactly(COUNTER);
    }

    @Test
    void aRefusedTopicDoesNotStopTheClientSubscribingToAnother() {
        source.refused.add(QUEUE);
        Wire wire = connect();
        subscribe(QUEUE);
        source.refused.clear();
        subscribe(QUEUE);

        assertThat(wire.frames()).extracting(f -> f.get("frame")).containsExactly("denied", "snapshot");
    }

    @Test
    void badFramesAreAnsweredNotFatal() {
        Wire wire = connect();
        hub.receive(connection, "not json");
        hub.receive(connection, "{\"frame\":\"dance\"}");
        hub.receive(connection, "{\"frame\":\"subscribe\",\"topics\":[42]}");

        assertThat(wire.frames("error")).hasSize(3);
        assertThat(wire.closes).isEmpty();
    }

    // ---- FR-QUE-081 replay ----------------------------------------------------------------------------------------

    private String epochOf(Wire wire, String topic) {
        return (String) wire.frames("snapshot").stream().filter(f -> topic.equals(f.get("topic"))).findFirst().orElseThrow().get("epoch");
    }

    @Test
    void aClientThatReconnectsIsSentTheEventsItMissedAndNoSnapshot() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        publish(QUEUE, "ticket.issued", 1);
        publish(QUEUE, "ticket.called", 2);
        hub.close(connection); // the socket drops after the client saw seq 1
        publish(QUEUE, "ticket.serving", 3);
        publish(QUEUE, "ticket.completed", 4);

        Wire second = connect();
        subscribeFrom(QUEUE, 2, epoch);
        publish(QUEUE, "ticket.issued", 5);

        assertThat(second.frames()).extracting(f -> f.get("frame")).containsExactly("replay", "event", "event", "event");
        assertThat(second.frames().get(0)).containsEntry("count", 2).containsEntry("seq", 4);
        assertThat(seqs(second, QUEUE)).containsExactly(3L, 4L, 5L);
    }

    @Test
    void aClientThatIsUpToDateGetsAnEmptyReplayAndThenLiveEvents() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        publish(QUEUE, "ticket.issued", 1);
        hub.close(connection);

        Wire second = connect();
        subscribeFrom(QUEUE, 1, epoch);
        publish(QUEUE, "ticket.called", 2);

        assertThat(second.frames("replay")).singleElement().satisfies(f -> assertThat(f).containsEntry("count", 0));
        assertThat(seqs(second, QUEUE)).containsExactly(2L);
    }

    @Test
    void whenTheMissedEventsHaveLeftTheBufferTheClientGetsAFreshSnapshotFlaggedResync() {
        properties = new RealtimeProperties(Duration.ofSeconds(20), 2, Duration.ofMinutes(5), 10);
        newHub();
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        publish(QUEUE, "ticket.issued", 1);
        hub.close(connection);
        for (int i = 2; i <= 16; i++) publish(QUEUE, "ticket.called", i);
        clock.advance(Duration.ofMinutes(6));
        publish(QUEUE, "ticket.called", 17); // trimming happens as events arrive: past ten events and past five minutes, the old ones go

        Wire second = connect();
        subscribeFrom(QUEUE, 1, epoch);

        assertThat(second.frames()).extracting(f -> f.get("frame")).containsExactly("snapshot");
        assertThat(second.last()).containsEntry("resync", true).containsEntry("seq", 17).containsEntry("data", Map.of("waiting_count", 3));
    }

    @Test
    void aClientFromBeforeARestartIsResyncedBecauseItsSeqMeansNothingToTheNewRun() {
        connect();
        Wire wire = connect();
        subscribeFrom(QUEUE, 7, "an-epoch-from-before-the-restart");

        assertThat(wire.last()).containsEntry("frame", "snapshot").containsEntry("resync", true);
    }

    @Test
    void aClientClaimingToBeAheadOfTheTopicIsResynced() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);

        Wire second = connect();
        subscribeFrom(QUEUE, 99, epoch);

        assertThat(second.last()).containsEntry("frame", "snapshot").containsEntry("resync", true);
    }

    @Test
    void theBufferKeepsEverythingFromTheLastFiveMinutesEvenPastAThousandEvents() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        for (int i = 1; i <= 1500; i++) publish(QUEUE, "ticket.called", i);
        hub.close(connection);

        Wire second = connect();
        subscribeFrom(QUEUE, 0, epoch);

        assertThat(second.frames("replay")).singleElement().satisfies(f -> assertThat(f).containsEntry("count", 1500));
    }

    @Test
    void theBufferKeepsTheLastThousandEventsEvenWhenTheyAreOlderThanFiveMinutes() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        for (int i = 1; i <= 1200; i++) publish(QUEUE, "ticket.called", i);
        hub.close(connection);
        clock.advance(Duration.ofHours(1));
        publish(QUEUE, "ticket.called", 1201); // trimming happens as events arrive

        Wire second = connect();
        subscribeFrom(QUEUE, 201, epoch); // needs events 202..1201: exactly the newest thousand

        assertThat(second.frames("replay")).singleElement().satisfies(f -> assertThat(f).containsEntry("count", 1000));

        Wire third = connect();
        subscribeFrom(QUEUE, 200, epoch); // one more than the buffer keeps

        assertThat(third.last()).containsEntry("frame", "snapshot").containsEntry("resync", true);
    }

    @Test
    void anEventOlderThanFiveMinutesIsKeptWhileFewerThanAThousandEventsAreBuffered() {
        Wire first = connect();
        subscribe(QUEUE);
        String epoch = epochOf(first, QUEUE);
        publish(QUEUE, "ticket.issued", 1);
        hub.close(connection);
        clock.advance(Duration.ofMinutes(30));
        publish(QUEUE, "ticket.called", 2);

        Wire second = connect();
        subscribeFrom(QUEUE, 0, epoch);

        assertThat(seqs(second, QUEUE)).containsExactly(1L, 2L);
    }

    @Test
    void aTopicNobodyListensToStillRemembersItsEventsForAClientThatComesBack() {
        publish(QUEUE, "ticket.issued", 1);
        publish(QUEUE, "ticket.called", 2);
        Wire wire = connect();
        subscribe(QUEUE);
        String epoch = epochOf(wire, QUEUE);

        Wire later = connect();
        subscribeFrom(QUEUE, 0, epoch);
        assertThat(seqs(later, QUEUE)).containsExactly(1L, 2L);
    }

    @Test
    void closingAConnectionEndsItsSubscriptions() {
        Wire wire = connect();
        subscribe(QUEUE);
        hub.close(connection);
        publish(QUEUE, "ticket.issued", 1);

        assertThat(wire.frames("event")).isEmpty();
        assertThat(hub.connectionCount()).isZero();
    }

    // ---- §21.1 heartbeat each way ---------------------------------------------------------------------------------

    @Test
    void theHubSendsAHeartbeatEveryIntervalToEveryClientThatIsStillTalking() {
        Wire wire = connect();
        clock.advance(Duration.ofSeconds(20));
        hub.beat();
        hub.receive(connection, "{\"frame\":\"heartbeat\"}");
        clock.advance(Duration.ofSeconds(20));
        hub.beat();

        assertThat(wire.frames("heartbeat")).hasSize(2);
        assertThat(wire.closes).isEmpty();
    }

    @Test
    void aClientThatMissesTwoHeartbeatsAndTheGraceIsDropped() {
        Wire wire = connect();
        clock.advance(Duration.ofSeconds(60));
        hub.beat();
        assertThat(wire.closes).isEmpty(); // exactly at the limit is still alive

        clock.advance(Duration.ofSeconds(1));
        hub.beat();
        assertThat(wire.closes).containsExactly(RealtimeHub.HEARTBEAT_TIMEOUT);
    }

    @Test
    void anyFrameFromTheClientCountsAsItBeingAlive() {
        Wire wire = connect();
        clock.advance(Duration.ofSeconds(50));
        subscribe(QUEUE);
        clock.advance(Duration.ofSeconds(50));
        hub.beat();

        assertThat(wire.closes).isEmpty();
    }

    @Test
    void aTransportThatCannotTakeAFrameIsClosed() {
        AtomicReference<Boolean> closed = new AtomicReference<>(false);
        Outbound broken = new Outbound() {
            public void send(String frame) throws Exception {
                throw new java.io.IOException("broken pipe");
            }

            public void close(int code, String reason) {
                closed.set(true);
            }
        };
        connection = hub.open(broken, agent);
        subscribe(QUEUE);

        assertThat(closed.get()).isTrue();
    }

    // ---- FR-QUE-084 the polling fallback reads the same snapshot ---------------------------------------------------

    @Test
    void aPollingClientIsGivenTheSnapshotWithTheSeqAndEpochItStandsFor() {
        publish(QUEUE, "ticket.issued", 1);
        SecurityContextHolder.getContext().setAuthentication(agent);

        Map<String, Object> polled = hub.snapshotOf(QUEUE);

        assertThat(polled).containsEntry("topic", QUEUE).containsEntry("seq", 1L).containsEntry("data", Map.of("waiting_count", 3));
        assertThat(polled.get("epoch")).isNotNull();
    }

    @Test
    void aPollingClientIsRefusedATopicItMayNotSeeAndToldWhenNoOneOwnsTheTopic() {
        source.refused.add(QUEUE);
        SecurityContextHolder.getContext().setAuthentication(agent);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hub.snapshotOf(QUEUE)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hub.snapshotOf("nonsense:1")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static List<Long> seqs(Wire wire, String topic) {
        return wire.frames("event").stream().filter(f -> topic.equals(f.get("topic"))).map(f -> ((Number) f.get("seq")).longValue()).toList();
    }
}
