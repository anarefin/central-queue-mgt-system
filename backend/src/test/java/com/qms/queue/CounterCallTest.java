package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.QueueEngine.Contender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a counter is given when it asks for its next ticket, and the ticket transitions that follow, without a database:
 * the highest score across the queues it serves with a preference for primary links (FR-QUE-002, FR-QUE-030), the
 * {@code waiting → called → serving → completed} path and its refusals (NFR-MNT-004, SRS §19.1), and the two durations
 * stored at closure (§18.5, Invariant 1).
 */
class CounterCallTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static Contender head(int service, int weight, int ticket, double score) {
        return new Contender(id(service), weight, id(ticket), T0.plusSeconds(ticket), score);
    }

    private static Optional<Integer> picked(double tolerance, Contender... heads) {
        return QueueEngine.pick(List.of(heads), tolerance).map(c -> (int) c.ticketId().getLeastSignificantBits());
    }

    // ---- FR-QUE-030: highest score across the queues, primary links preferred within a tolerance ----------------

    @Test
    void aCounterWithNothingWaitingIsGivenNothing() {
        assertThat(QueueEngine.pick(List.of(), 5)).isEmpty();
    }

    @Test
    void theHighestScoreAcrossTheQueuesWinsWhenTheLinksHaveTheSameWeight() {
        assertThat(picked(5, head(1, 1, 101, 12), head(2, 1, 102, 30), head(3, 1, 103, 20))).contains(102);
    }

    @Test
    void aPrimaryLinkBeatsAFallbackLinkWhoseTicketIsBetterByNoMoreThanTheTolerance() {
        // Service 1 is primary (weight 1) with score 20; service 2 is a fallback (weight 2) with score 24: within 5 minutes.
        assertThat(picked(5, head(1, 1, 101, 20), head(2, 2, 102, 24))).contains(101);
        // Exactly at the tolerance still counts as within it.
        assertThat(picked(5, head(1, 1, 101, 20), head(2, 2, 102, 25))).contains(101);
    }

    @Test
    void aFallbackLinkWinsWhenItsTicketIsBetterByMoreThanTheTolerance() {
        assertThat(picked(5, head(1, 1, 101, 20), head(2, 2, 102, 25.5))).contains(102);
    }

    @Test
    void aZeroToleranceMeansTheHighestScoreAlwaysWins() {
        assertThat(picked(0, head(1, 1, 101, 20), head(2, 2, 102, 20.1))).contains(102);
    }

    @Test
    void aPrimaryLinkWhoseTicketIsFarWorseIsNotPreferred() {
        assertThat(picked(5, head(1, 1, 101, 3), head(2, 2, 102, 40))).contains(102);
    }

    @Test
    void amongLinksOfDifferentFallbackWeightsTheLowerWeightWinsWithinTheTolerance() {
        assertThat(picked(5, head(1, 3, 101, 30), head(2, 2, 102, 28), head(3, 4, 103, 31))).contains(102);
    }

    @Test
    void anEscalatedTicketOnAFallbackLinkStillWinsBecauseItsScoreIsFarAboveTheTolerance() {
        double escalated = QueueEngine.ESCALATION_TIER_MINUTES + 10;
        assertThat(picked(5, head(1, 1, 101, 30), head(2, 2, 102, escalated))).contains(102);
    }

    @Test
    void equalWeightsAndScoresBreakByEarlierArrivalThenLowerId() {
        Contender earlier = new Contender(id(1), 1, id(9), T0, 10);
        Contender later = new Contender(id(2), 1, id(5), T0.plusSeconds(60), 10);
        assertThat(QueueEngine.pick(List.of(later, earlier), 5)).contains(earlier);

        Contender sameTimeLowId = new Contender(id(1), 1, id(5), T0, 10);
        Contender sameTimeHighId = new Contender(id(2), 1, id(9), T0, 10);
        assertThat(QueueEngine.pick(List.of(sameTimeHighId, sameTimeLowId), 5)).contains(sameTimeLowId);
    }

    @Test
    void theChoiceDoesNotDependOnTheOrderTheHeadsAreListedIn() {
        List<Contender> heads = List.of(head(1, 1, 101, 20), head(2, 2, 102, 24), head(3, 1, 103, 19));
        for (int shift = 0; shift < heads.size(); shift++) {
            var rotated = new java.util.ArrayList<>(heads);
            java.util.Collections.rotate(rotated, shift);
            assertThat(QueueEngine.pick(rotated, 5).map(Contender::ticketId)).contains(id(101));
        }
    }

    // ---- SRS §19.1: waiting → called → serving → completed ------------------------------------------------------

    @Test
    void aTicketMovesFromWaitingToCalledToServingToCompleted() {
        assertThat(TicketTransition.CALL.apply("waiting")).contains("called");
        assertThat(TicketTransition.START_SERVICE.apply("called")).contains("serving");
        assertThat(TicketTransition.COMPLETE.apply("serving")).contains("completed");
    }

    @Test
    void eachTransitionIsRefusedFromEveryStateButTheOneItLeaves() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        for (TicketTransition transition : TicketTransition.values()) {
            for (String state : states) {
                assertThat(transition.apply(state).isPresent()).as(transition + " from " + state).isEqualTo(state.equals(transition.from()));
            }
        }
    }

    @Test
    void eachTransitionNamesItsEventAndItsStatesAreOnesTheDatabaseKnows() {
        assertThat(TicketTransition.CALL.eventType()).isEqualTo("ticket.called");
        assertThat(TicketTransition.START_SERVICE.eventType()).isEqualTo("ticket.serving");
        assertThat(TicketTransition.COMPLETE.eventType()).isEqualTo("ticket.completed");
        // The path is one chain: each transition starts where the last ended.
        assertThat(TicketTransition.CALL.to()).isEqualTo(TicketTransition.START_SERVICE.from());
        assertThat(TicketTransition.START_SERVICE.to()).isEqualTo(TicketTransition.COMPLETE.from());
    }

    // ---- §18.5, Invariant 1: durations computed at closure -----------------------------------------------------

    @Test
    void waitIsTheTimeInTheQueueAndServiceIsTheTimeBetweenStartAndCompletion() {
        Instant queued = T0;
        Instant called = T0.plusSeconds(300);
        Instant served = T0.plusSeconds(480);
        Instant closed = T0.plusSeconds(1200);

        TicketTimings timings = TicketTimings.atClosure(queued, called, served, closed);

        assertThat(timings.waitSeconds()).isEqualTo(300);
        assertThat(timings.serviceSeconds()).isEqualTo(720);
    }

    @Test
    void timeSpentCalledButNotYetServedIsNeitherWaitNorService() {
        // Called at +300 s, the visitor takes 10 minutes to reach the desk, service starts at +900 s and ends at +1000 s.
        TicketTimings timings = TicketTimings.atClosure(T0, T0.plusSeconds(300), T0.plusSeconds(900), T0.plusSeconds(1000));

        assertThat(timings.waitSeconds()).isEqualTo(300);
        assertThat(timings.serviceSeconds()).isEqualTo(100);
    }

    @Test
    void aClockThatRunsBehindNeverProducesANegativeDuration() {
        assertThat(TicketTimings.seconds(T0, T0.minusSeconds(5))).isZero();
    }
}
