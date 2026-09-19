package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import com.qms.queue.QueueEngine.Terms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Hold and force-close without a database (NFR-MNT-004): {@code serving → held}, {@code held → serving} and
 * {@code held → waiting} (SRS §19.1, FR-AGT-013, ADR-0008), the hold limit, and that a ticket returned to the front by a
 * force-closed session lands there by Score adjustment without touching its wait (ADR-0004).
 */
class HoldAndForceCloseTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60 * 60);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static Candidate joined(int n, double minutes) {
        Instant at = T0.plusMillis((long) (minutes * 60_000));
        return new Candidate(id(n), at, at, 0, null, 0, 0);
    }

    // ---- serving → held (FR-AGT-013) ----------------------------------------------------------------------------

    @Test
    void holdingParksTheServingTicketAndWritesTicketHeld() {
        assertThat(TicketTransition.HOLD.apply("serving")).contains("held");
        assertThat(TicketTransition.HOLD.eventType()).isEqualTo("ticket.held");
    }

    @Test
    void onlyAServingTicketCanBeHeld() {
        for (String state : List.of("remote", "waiting", "paused", "called", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.HOLD.apply(state)).as(state).isEmpty();
        }
    }

    @Test
    void theHeldCountIsCappedByTheHoldLimit() {
        assertThat(TicketTransition.mayHold(0, 3)).isTrue();
        assertThat(TicketTransition.mayHold(2, 3)).isTrue();
        assertThat(TicketTransition.mayHold(3, 3)).as("the limit is reached").isFalse();
        assertThat(TicketTransition.mayHold(0, 0)).as("a limit of 0 switches Hold off").isFalse();
    }

    @Test
    void theHoldLimitDefaultsToThreeAndIsConfigurable() {
        assertThat(bind(Map.of()).holdLimit()).isEqualTo(3);
        assertThat(bind(Map.of("qms.queue.hold-limit", "5")).holdLimit()).isEqualTo(5);
        assertThat(bind(Map.of("qms.queue.hold-limit", "0")).holdLimit()).isZero();
    }

    private static QueueProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("qms.queue", QueueProperties.class);
    }

    // ---- held → serving: resume (ADR-0008) -----------------------------------------------------------------------

    @Test
    void resumingPutsAHeldTicketBackInServiceAndIsAnnouncedAsServing() {
        assertThat(TicketTransition.RESUME.apply("held")).contains("serving");
        assertThat(TicketTransition.RESUME.eventType()).isEqualTo("ticket.serving");
    }

    @Test
    void onlyAHeldTicketCanBeResumed() {
        for (String state : List.of("remote", "waiting", "paused", "called", "serving", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.RESUME.apply(state)).as(state).isEmpty();
        }
    }

    // ---- held → waiting: the session is force-closed (ADR-0008) --------------------------------------------------

    @Test
    void aForceClosedSessionReturnsItsHeldTicketToWaiting() {
        assertThat(TicketTransition.returnFrom("held")).contains(TicketTransition.RETURN_FROM_HELD);
        assertThat(TicketTransition.RETURN_FROM_HELD.apply("held")).contains("waiting");
    }

    @Test
    void aForceClosedSessionAlsoReturnsItsCalledAndServingTickets() {
        assertThat(TicketTransition.returnFrom("called").orElseThrow().apply("called")).contains("waiting");
        assertThat(TicketTransition.returnFrom("serving").orElseThrow().apply("serving")).contains("waiting");
        for (String state : List.of("remote", "waiting", "paused", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.returnFrom(state)).as(state).isEmpty();
        }
    }

    @Test
    void everyReturnWritesOneEventThatSaysThePositionChanged() {
        for (TicketTransition transition : List.of(TicketTransition.RETURN_FROM_CALLED, TicketTransition.RETURN_FROM_SERVING, TicketTransition.RETURN_FROM_HELD)) {
            assertThat(transition.eventType()).isEqualTo("ticket.position_changed");
            assertThat(transition.to()).isEqualTo("waiting");
        }
    }

    @Test
    void theHeldTransitionsAreOnesTheDatabaseKnows() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        for (TicketTransition transition : List.of(
                TicketTransition.HOLD, TicketTransition.RESUME, TicketTransition.RETURN_FROM_CALLED, TicketTransition.RETURN_FROM_SERVING, TicketTransition.RETURN_FROM_HELD)) {
            assertThat(states).contains(transition.from(), transition.to());
        }
    }

    // ---- the front of the queue, by Score adjustment (ADR-0004) ---------------------------------------------------

    /** Five waiting tickets that joined 10..50 minutes after T0, so their scores are 50, 40, 30, 20 and 10. */
    private static List<Candidate> fiveWaiting() {
        return List.of(joined(1, 10), joined(2, 20), joined(3, 30), joined(4, 40), joined(5, 50));
    }

    private static List<Terms> termsOf(List<Candidate> candidates) {
        return QueueEngine.order(candidates, QueueStrategy.WEIGHTED_WAIT, NOW).stream().map(Scored::terms).toList();
    }

    @Test
    void aReturnedTicketLandsAtTheFrontWhateverItsOwnWaitAndKeepsItsQueuedAt() {
        // It joined last, so it would be at the back; the adjustment lifts it ahead of the first, without touching when it queued.
        Candidate late = joined(9, 58);
        int adjustment = QueueEngine.reentryAdjustment(QueueEngine.terms(late, NOW), termsOf(fiveWaiting()), ReentryPosition.FRONT, 1);
        Candidate back = new Candidate(late.id(), late.createdAt(), late.waitingSince(), 0, null, 0, adjustment);
        List<Candidate> all = new ArrayList<>(fiveWaiting());
        all.add(back);

        Scored first = QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW).getFirst();

        assertThat(adjustment).isPositive();
        assertThat(first.ticket().id()).isEqualTo(late.id());
        assertThat(first.ticket().waitingSince()).as("queued_at is never rewritten").isEqualTo(late.waitingSince());
    }

    @Test
    void ticketsReturnedOneAfterAnotherEndInTheOrderTheyJoinedTheQueue() {
        // Each goes to the front in turn, so the session returns the latest to join first and the earliest last.
        List<Candidate> queue = new ArrayList<>(fiveWaiting());
        for (Candidate returning : List.of(joined(23, 55), joined(22, 45), joined(21, 40))) {
            int adjustment = QueueEngine.reentryAdjustment(QueueEngine.terms(returning, NOW), termsOf(queue), ReentryPosition.FRONT, 1);
            queue.add(new Candidate(returning.id(), returning.createdAt(), returning.waitingSince(), 0, null, 0, adjustment));
        }

        List<UUID> order = QueueEngine.order(queue, QueueStrategy.WEIGHTED_WAIT, NOW).stream().map(s -> s.ticket().id()).toList();

        assertThat(order.subList(0, 3)).containsExactly(id(21), id(22), id(23));
    }
}
