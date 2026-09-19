package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Transfer without a database (NFR-MNT-004): {@code serving → transferred} (SRS §19.1, FR-QUE-052, ADR-0006, Invariant 4), the
 * successor's wait and transfer Head start (FR-QUE-053), and the personal queue of a ticket targeted at an Agent or a Counter
 * (FR-QUE-003).
 */
class TransferTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static Instant at(double minutes) {
        return T0.plusMillis((long) (minutes * 60_000));
    }

    // ---- serving → transferred (FR-QUE-052, Invariant 4) --------------------------------------------------------

    @Test
    void transferringClosesTheServingTicketAsTransferredAndWritesTicketTransferred() {
        assertThat(TicketTransition.TRANSFER.apply("serving")).contains("transferred");
        assertThat(TicketTransition.TRANSFER.eventType()).isEqualTo("ticket.transferred");
    }

    @Test
    void onlyAServingTicketCanBeTransferred() {
        for (String state : List.of("remote", "waiting", "paused", "called", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.TRANSFER.apply(state)).as(state).isEmpty();
        }
    }

    @Test
    void transferredIsTerminal() {
        for (TicketTransition transition : TicketTransition.values()) {
            assertThat(transition.from()).as(transition.name()).isNotEqualTo("transferred");
        }
    }

    @Test
    void theTransitionIsOneTheDatabaseKnows() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        assertThat(states).contains(TicketTransition.TRANSFER.from(), TicketTransition.TRANSFER.to());
    }

    // ---- the transfer Head start (FR-QUE-053) -------------------------------------------------------------------

    @Test
    void theHeadStartDefaultsToThePredecessorsAccruedWaitInMinutes() {
        assertThat(TransferRules.headStartMinutes(null, 1200)).isEqualTo(20);
        assertThat(TransferRules.headStartMinutes(null, 0)).isZero();
    }

    @Test
    void aWaitThatIsNotWholeMinutesIsRoundedToTheNearestMinute() {
        assertThat(TransferRules.headStartMinutes(null, 89)).isEqualTo(1);
        assertThat(TransferRules.headStartMinutes(null, 90)).isEqualTo(2);
        assertThat(TransferRules.headStartMinutes(null, 29)).isZero();
    }

    @Test
    void aConfiguredHeadStartReplacesTheDefault() {
        assertThat(TransferRules.headStartMinutes(10, 1200)).isEqualTo(10);
        assertThat(TransferRules.headStartMinutes(0, 1200)).as("0 sends the visitor to the back of the new queue").isZero();
    }

    @Test
    void theHeadStartIsConfigurableAndDefaultsToTheAccruedWait() {
        assertThat(bind(Map.of()).transferHeadstartMinutes()).isNull();
        assertThat(bind(Map.of("qms.queue.transfer-headstart-minutes", "7")).transferHeadstartMinutes()).isEqualTo(7);
        assertThat(bind(Map.of("qms.queue.transfer-headstart-minutes", "")).transferHeadstartMinutes()).as("an empty variable is not set").isNull();
    }

    private static QueueProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("qms.queue", QueueProperties.class);
    }

    // ---- the successor's place: its wait starts at the transfer, the Head start keeps it from the back ----------------

    private static Candidate joined(int n, double minutes) {
        return new Candidate(id(n), at(minutes), at(minutes), 0, null, 0, 0);
    }

    @Test
    void aSuccessorWithTheAccruedWaitAsHeadStartOutranksTicketsThatHaveWaitedLessThanThePredecessorDid() {
        Instant transferTime = at(60);
        // Three visitors already in the target queue: joined at minutes 30, 45 and 55, so they have waited 30, 15 and 5 minutes.
        List<Candidate> queue = new ArrayList<>(List.of(joined(1, 30), joined(2, 45), joined(3, 55)));
        // The predecessor waited 20 minutes; the successor's own wait starts now, and its Head start is those 20 minutes.
        queue.add(new Candidate(id(9), transferTime, transferTime, 0, null, 0, TransferRules.headStartMinutes(null, 20 * 60)));

        List<UUID> order = QueueEngine.order(queue, QueueStrategy.WEIGHTED_WAIT, transferTime).stream().map(s -> s.ticket().id()).toList();

        assertThat(order).as("behind the one who waited 30 minutes, ahead of those who waited 15 and 5").containsExactly(id(1), id(9), id(2), id(3));
    }

    @Test
    void withoutAHeadStartASuccessorWouldGoToTheBack() {
        Instant transferTime = at(60);
        List<Candidate> queue = new ArrayList<>(List.of(joined(1, 30), joined(2, 45), joined(3, 55)));
        queue.add(new Candidate(id(9), transferTime, transferTime, 0, null, 0, 0));

        List<Scored> order = QueueEngine.order(queue, QueueStrategy.WEIGHTED_WAIT, transferTime);

        assertThat(order.getLast().ticket().id()).isEqualTo(id(9));
    }

    @Test
    void theSuccessorsWaitStartsAtTheTransferNotAtTheVisitorsFirstArrival() {
        Instant transferTime = at(60);
        Candidate successor = new Candidate(id(9), transferTime, transferTime, 0, null, 0, 20);

        assertThat(QueueEngine.terms(successor, transferTime).effectiveWaitMinutes()).isZero();
        assertThat(QueueEngine.terms(successor, at(65)).effectiveWaitMinutes()).isEqualTo(5.0);
    }

    @Test
    void thePredecessorsWaitStopsAtTransferAndTheSuccessorsOwnStintStartsThere() {
        // Waiting 0-20 min, called at 20, served, transferred at 35: the predecessor's wait is the one stint.
        List<TicketTimings.Change> predecessor = List.of(
                new TicketTimings.Change(at(0), null, "waiting"),
                new TicketTimings.Change(at(20), "waiting", "called"),
                new TicketTimings.Change(at(22), "called", "serving"),
                new TicketTimings.Change(at(35), "serving", "transferred"));
        assertThat(TicketTimings.accruedWait(at(0), predecessor)).isEqualTo(20 * 60);

        // The successor is created waiting at 35 and called at 50: 15 minutes, none of them the predecessor's.
        List<TicketTimings.Change> successor = List.of(new TicketTimings.Change(at(35), null, "waiting"), new TicketTimings.Change(at(50), "waiting", "called"));
        assertThat(TicketTimings.accruedWait(at(35), successor)).isEqualTo(15 * 60);
    }

    // ---- the personal queue (FR-QUE-003) ------------------------------------------------------------------------

    @Test
    void aTicketWithNoTargetIsDrawnByAnyone() {
        assertThat(TransferRules.Target.ANYONE.drawableBy(id(1), id(2))).isTrue();
    }

    @Test
    void aTicketTargetedAtAnAgentIsDrawnByThatAgentAtAnyCounterAndByNoOneElse() {
        TransferRules.Target target = new TransferRules.Target(null, id(2));

        assertThat(target.drawableBy(id(1), id(2))).isTrue();
        assertThat(target.drawableBy(id(7), id(2))).as("at another counter").isTrue();
        assertThat(target.drawableBy(id(1), id(3))).as("another agent at the same counter").isFalse();
    }

    @Test
    void aTicketTargetedAtACounterIsDrawnByThatCounterWhoeverSitsAtIt() {
        TransferRules.Target target = new TransferRules.Target(id(1), null);

        assertThat(target.drawableBy(id(1), id(2))).isTrue();
        assertThat(target.drawableBy(id(1), id(3))).isTrue();
        assertThat(target.drawableBy(id(5), id(2))).isFalse();
    }
}
