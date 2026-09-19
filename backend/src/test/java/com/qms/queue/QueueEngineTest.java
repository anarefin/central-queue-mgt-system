package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The queue ordering engine on its own: pure input, pure output (ADR-0001), covering every strategy (NFR-MNT-004), the
 * score terms and their tie-breaks (FR-QUE-020, FR-QUE-021), escalation (FR-QUE-022, ADR-0004) and the time budget
 * (NFR-SCL-003).
 */
class QueueEngineTest {

    static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    /** A ticket that has waited {@code waitedMinutes}, of a class with the given Head start and maximum wait. */
    private static Candidate ticket(int n, int waitedMinutes, int headstart, Integer maxWait) {
        return ticket(n, waitedMinutes, headstart, maxWait, 0, 0);
    }

    private static Candidate ticket(int n, int waitedMinutes, int headstart, Integer maxWait, double appointmentBonus, int adjustment) {
        Instant since = NOW.minus(Duration.ofMinutes(waitedMinutes));
        return new Candidate(id(n), since, since, headstart, maxWait, appointmentBonus, adjustment);
    }

    private static List<Integer> order(QueueStrategy strategy, Candidate... tickets) {
        return QueueEngine.order(List.of(tickets), strategy, NOW).stream().map(s -> (int) s.ticket().id().getLeastSignificantBits()).toList();
    }

    // ---- FR-QUE-020: the score ---------------------------------------------------------------------------------

    @Test
    void scoreIsEffectiveWaitPlusHeadStartPlusAppointmentBonusPlusEscalationPlusScoreAdjustment() {
        Candidate ticket = ticket(1, 12, 20, null, 15, -4);

        var terms = QueueEngine.terms(ticket, NOW);

        assertThat(terms.effectiveWaitMinutes()).isEqualTo(12.0);
        assertThat(terms.headstartMinutes()).isEqualTo(20.0);
        assertThat(terms.appointmentBonusMinutes()).isEqualTo(15.0);
        assertThat(terms.escalationBonusMinutes()).isZero();
        assertThat(terms.scoreAdjustmentMinutes()).isEqualTo(-4.0);
        assertThat(terms.score()).isEqualTo(12 + 20 + 15 + 0 - 4);
    }

    @Test
    void effectiveWaitCountsFractionsOfAMinuteAndNeverGoesNegative() {
        Candidate thirtySeconds = new Candidate(id(1), NOW.minusSeconds(30), NOW.minusSeconds(30), 0, null, 0, 0);
        Candidate future = new Candidate(id(2), NOW.plusSeconds(90), NOW.plusSeconds(90), 0, null, 0, 0);

        assertThat(QueueEngine.terms(thirtySeconds, NOW).effectiveWaitMinutes()).isEqualTo(0.5);
        assertThat(QueueEngine.terms(future, NOW).effectiveWaitMinutes()).isZero();
    }

    @Test
    void weightedWaitServesTheHighestScoreFirst() {
        assertThat(order(QueueStrategy.WEIGHTED_WAIT, ticket(1, 5, 0, null), ticket(2, 30, 0, null), ticket(3, 17, 0, null))).containsExactly(2, 3, 1);
    }

    @Test
    void aHeadStartTakesEffectOnArrivalButDoesNotOvertakeTicketsThatAlreadyWaitedLonger() {
        // UAT U6: a priority visitor with a 20 minute head start arrives into a long normal queue.
        Candidate priority = ticket(1, 0, 20, null);
        Candidate waitedLess = ticket(2, 19, 0, null);
        Candidate waitedMore = ticket(3, 21, 0, null);

        assertThat(order(QueueStrategy.WEIGHTED_WAIT, waitedLess, waitedMore, priority)).containsExactly(3, 1, 2);
    }

    @Test
    void aPositiveAppointmentBonusAndAScoreAdjustmentMoveATicketByThatManyMinutes() {
        Candidate plain = ticket(1, 30, 0, null);
        Candidate appointment = ticket(2, 20, 0, null, 15, 0);
        Candidate pushedBack = ticket(3, 40, 0, null, 0, -20);

        assertThat(order(QueueStrategy.WEIGHTED_WAIT, plain, appointment, pushedBack)).containsExactly(2, 1, 3);
    }

    @Test
    void equalScoresBreakByEarliestCreationThenLowestId() {
        Instant tenAgo = NOW.minus(Duration.ofMinutes(10));
        // Three tickets tie on a score of 10: two created 10 minutes ago, and one created a minute later whose extra
        // minute of Head start makes up for its shorter wait.
        Candidate a = new Candidate(id(5), tenAgo, tenAgo, 0, null, 0, 0);
        Candidate b = new Candidate(id(3), tenAgo, tenAgo, 0, null, 0, 0);
        Candidate c = new Candidate(id(1), tenAgo.plusSeconds(60), tenAgo.plusSeconds(60), 1, null, 0, 0);
        // Two more tie on a score of 11 and were created together, so only the id separates them.
        Candidate d = new Candidate(id(9), tenAgo.minusSeconds(60), tenAgo.minusSeconds(60), 0, null, 0, 0);
        Candidate e = new Candidate(id(8), tenAgo.minusSeconds(60), tenAgo.minusSeconds(60), 0, null, 0, 0);

        List<Scored> scored = QueueEngine.order(List.of(a, b, c, d, e), QueueStrategy.WEIGHTED_WAIT, NOW);

        assertThat(scored.stream().map(s -> s.ticket().id().getLeastSignificantBits()).toList()).containsExactly(8L, 9L, 3L, 5L, 1L);
        assertThat(scored.get(2).terms().score()).isEqualTo(scored.get(4).terms().score());
    }

    @Test
    void positionsAreOneBasedAndTheInputListIsNotChanged() {
        List<Candidate> input = new ArrayList<>(List.of(ticket(1, 1, 0, null), ticket(2, 2, 0, null)));
        List<Candidate> copy = List.copyOf(input);

        List<Scored> ordered = QueueEngine.order(input, QueueStrategy.WEIGHTED_WAIT, NOW);

        assertThat(ordered.stream().map(Scored::position).toList()).containsExactly(1, 2);
        assertThat(input).isEqualTo(copy);
        assertThat(QueueEngine.order(List.of(), QueueStrategy.WEIGHTED_WAIT, NOW)).isEmpty();
    }

    // ---- FR-QUE-021: the other strategies ----------------------------------------------------------------------

    @Test
    void strictPriorityServesTheClassWithTheLargerHeadStartFirstThenFirstComeFirstServed() {
        Candidate normalOld = ticket(1, 50, 0, null);
        Candidate priorityNew = ticket(2, 1, 20, null);
        Candidate priorityOlder = ticket(3, 8, 20, null);
        Candidate top = ticket(4, 0, 60, null);

        assertThat(order(QueueStrategy.STRICT_PRIORITY, normalOld, priorityNew, priorityOlder, top)).containsExactly(4, 3, 2, 1);
    }

    @Test
    void strictPriorityIgnoresScoreAdjustmentsAndAppointmentBonuses() {
        Candidate first = ticket(1, 10, 0, null, 0, -30);
        Candidate second = ticket(2, 5, 0, null, 15, 30);

        assertThat(order(QueueStrategy.STRICT_PRIORITY, second, first)).containsExactly(1, 2);
    }

    @Test
    void fifoIsCreationOrderAndNothingElse() {
        Candidate old = ticket(1, 30, 0, null, 0, -100);
        Candidate priority = ticket(2, 1, 60, 5, 15, 100);
        Candidate middle = ticket(3, 10, 0, null);

        assertThat(order(QueueStrategy.FIFO, priority, middle, old)).containsExactly(1, 3, 2);
    }

    @Test
    void everyStrategyOrdersAnEmptyAndASingleTicketQueue() {
        for (QueueStrategy strategy : QueueStrategy.values()) {
            assertThat(order(strategy)).as(strategy.wire()).isEmpty();
            assertThat(order(strategy, ticket(1, 3, 0, null))).as(strategy.wire()).containsExactly(1);
        }
    }

    @Test
    void strategiesHaveStableWireNamesAndTheDefaultIsWeightedWait() {
        assertThat(QueueStrategy.wires()).containsExactly("weighted_wait", "strict_priority", "fifo");
        assertThat(QueueStrategy.fromWire("strict_priority")).contains(QueueStrategy.STRICT_PRIORITY);
        assertThat(QueueStrategy.fromWire("lifo")).isEmpty();
        assertThat(QueueStrategy.DEFAULT).isEqualTo(QueueStrategy.WEIGHTED_WAIT);
    }

    // ---- FR-QUE-022: escalation --------------------------------------------------------------------------------

    @Test
    void aTicketPastItsMaximumWaitIsServedBeforeEveryTicketThatIsNotEscalated() {
        Candidate priority = ticket(1, 0, 120, null);
        Candidate starved = ticket(2, 61, 0, 60);
        Candidate longWaiting = ticket(3, 500, 0, null);

        assertThat(order(QueueStrategy.WEIGHTED_WAIT, priority, longWaiting, starved)).containsExactly(2, 3, 1);
        assertThat(order(QueueStrategy.STRICT_PRIORITY, priority, longWaiting, starved)).as("strict priority is also protected from starvation").first().isEqualTo(2);
    }

    @Test
    void escalationBeginsOnlyWhenTheWaitExceedsTheMaximum() {
        assertThat(QueueEngine.terms(ticket(1, 60, 0, 60), NOW).escalated()).as("exactly at the maximum").isFalse();
        assertThat(QueueEngine.terms(ticket(2, 61, 0, 60), NOW).escalated()).isTrue();
        assertThat(QueueEngine.terms(ticket(3, 10_000, 0, null), NOW).escalated()).as("no maximum, no escalation").isFalse();
    }

    @Test
    void theEscalationBonusGrowsWithTheWaitBeyondTheMaximum() {
        var at61 = QueueEngine.terms(ticket(1, 61, 0, 60), NOW);
        var at75 = QueueEngine.terms(ticket(1, 75, 0, 60), NOW);

        assertThat(at61.escalationBonusMinutes()).isEqualTo(QueueEngine.ESCALATION_TIER_MINUTES + 1);
        assertThat(at75.escalationBonusMinutes() - at61.escalationBonusMinutes()).isEqualTo(14.0);
        assertThat(at75.score()).isGreaterThan(at61.score());
    }

    @Test
    void escalationOverridesANegativeScoreAdjustmentButKeepsAPositiveOne() {
        // A positional move set a large negative adjustment (ADR-0004); the hard cap on wait still holds.
        var pushedBack = QueueEngine.terms(ticket(1, 70, 0, 60, 0, -500), NOW);
        var pushedForward = QueueEngine.terms(ticket(2, 70, 0, 60, 0, 30), NOW);
        var notEscalated = QueueEngine.terms(ticket(3, 10, 0, 60, 0, -500), NOW);

        assertThat(pushedBack.escalated()).isTrue();
        assertThat(pushedBack.adjustmentOverridden()).isTrue();
        assertThat(pushedBack.scoreAdjustmentMinutes()).isZero();
        assertThat(pushedForward.adjustmentOverridden()).isFalse();
        assertThat(pushedForward.scoreAdjustmentMinutes()).isEqualTo(30.0);
        assertThat(notEscalated.adjustmentOverridden()).isFalse();
        assertThat(notEscalated.scoreAdjustmentMinutes()).isEqualTo(-500.0);
        assertThat(order(QueueStrategy.WEIGHTED_WAIT, ticket(4, 300, 0, null), ticket(1, 70, 0, 60, 0, -500))).containsExactly(1, 4);
    }

    @Test
    void escalationIsMeasuredOnRealWaitNotOnTheScore() {
        // A big head start makes the score large but does not count as waiting.
        assertThat(QueueEngine.terms(ticket(1, 5, 500, 60), NOW).escalated()).isFalse();
    }

    @Test
    void escalatedTicketsAreOrderedAmongThemselvesByHowFarTheyAreOverdue() {
        Candidate slightlyLate = ticket(1, 61, 0, 60);
        Candidate veryLate = ticket(2, 90, 0, 60);

        assertThat(order(QueueStrategy.WEIGHTED_WAIT, slightlyLate, veryLate)).containsExactly(2, 1);
    }

    @Test
    void fifoDoesNotEscalateButStillReportsTheFlag() {
        Candidate starved = ticket(2, 61, 0, 60);
        Candidate older = ticket(1, 100, 0, null);

        assertThat(order(QueueStrategy.FIFO, starved, older)).containsExactly(1, 2);
        assertThat(QueueEngine.order(List.of(starved), QueueStrategy.FIFO, NOW).getFirst().terms().escalated()).isTrue();
    }

    // ---- NFR-SCL-003: 500 waiting tickets in under 50 ms -------------------------------------------------------

    @Test
    void orderingFiveHundredWaitingTicketsTakesUnderFiftyMilliseconds() {
        Random random = new Random(42);
        List<Candidate> queue = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int waited = random.nextInt(180);
            Instant since = NOW.minus(Duration.ofMinutes(waited)).minusSeconds(random.nextInt(60));
            queue.add(new Candidate(UUID.randomUUID(), since, since, random.nextInt(4) * 10, random.nextBoolean() ? 60 : null, random.nextInt(2) * 15, random.nextInt(21) - 10));
        }
        for (QueueStrategy strategy : QueueStrategy.values()) {
            for (int warmup = 0; warmup < 20; warmup++) QueueEngine.order(queue, strategy, NOW);
            long best = Long.MAX_VALUE;
            for (int run = 0; run < 5; run++) {
                long started = System.nanoTime();
                List<Scored> ordered = QueueEngine.order(queue, strategy, NOW);
                best = Math.min(best, System.nanoTime() - started);
                assertThat(ordered).hasSize(500);
            }
            assertThat(Duration.ofNanos(best)).as(strategy.wire()).isLessThan(Duration.ofMillis(50));
        }
    }
}
