package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import com.qms.queue.QueueEngine.Terms;
import com.qms.queue.TicketTimings.Change;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Re-announce and Miss without a database (NFR-MNT-004): {@code called → called}, {@code called → waiting} and
 * {@code called → no_show} (SRS §19.1, ADR-0005, FR-QUE-050, FR-DSP-028), where a missed ticket re-enters and that the
 * adjustment is what puts it there (FR-QUE-051, ADR-0004), and the wait of a ticket that was called more than once
 * (Invariant 1).
 */
class ReannounceAndMissTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60 * 60);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    /** A ticket that joined the queue {@code minutes} after T0. */
    private static Candidate joined(int n, double minutes, Integer maxWait, int adjustment) {
        Instant at = T0.plusMillis((long) (minutes * 60_000));
        return new Candidate(id(n), at, at, 0, maxWait, 0, adjustment);
    }

    private static Candidate joined(int n, double minutes) {
        return joined(n, minutes, null, 0);
    }

    private static List<Terms> termsOf(List<Candidate> candidates) {
        return QueueEngine.order(candidates, QueueStrategy.WEIGHTED_WAIT, NOW).stream().map(Scored::terms).toList();
    }

    /** The place the missed ticket takes, 1-based, once it has been given the adjustment for {@code position}. */
    private static int placeAfterMiss(Candidate missed, List<Candidate> waiting, ReentryPosition position, int after) {
        int adjustment = QueueEngine.reentryAdjustment(QueueEngine.terms(missed, NOW), termsOf(waiting), position, after);
        Candidate back = new Candidate(missed.id(), missed.createdAt(), missed.waitingSince(), 0, missed.maxWaitMinutes(), 0, adjustment);
        List<Candidate> all = new ArrayList<>(waiting);
        all.add(back);
        return QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW).stream().filter(s -> s.ticket().id().equals(missed.id())).findFirst().orElseThrow().position();
    }

    // ---- called → called: Re-announce (FR-DSP-028, ADR-0005) -----------------------------------------------------

    @Test
    void reannouncingKeepsATicketCalled() {
        assertThat(TicketTransition.REANNOUNCE.apply("called")).contains("called");
        assertThat(TicketTransition.REANNOUNCE.eventType()).isEqualTo("ticket.reannounced");
    }

    @Test
    void reannouncingIsRefusedFromEveryStateButCalled() {
        for (String state : List.of("remote", "waiting", "paused", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.REANNOUNCE.apply(state)).as(state).isEmpty();
            assertThat(TicketTransition.MISS.apply(state)).as(state).isEmpty();
            assertThat(TicketTransition.NO_SHOW.apply(state)).as(state).isEmpty();
        }
    }

    @Test
    void reannouncingIsCappedByTheRepeatLimit() {
        assertThat(TicketTransition.mayReannounce(0, 3)).isTrue();
        assertThat(TicketTransition.mayReannounce(2, 3)).isTrue();
        assertThat(TicketTransition.mayReannounce(3, 3)).as("the limit is reached").isFalse();
        assertThat(TicketTransition.mayReannounce(0, 0)).as("a limit of 0 switches it off").isFalse();
    }

    // ---- called → waiting and called → no_show: Miss (FR-QUE-050, ADR-0005) ---------------------------------------

    @Test
    void aMissReturnsTheTicketToWaitingAndWritesTicketMissed() {
        assertThat(TicketTransition.MISS.apply("called")).contains("waiting");
        assertThat(TicketTransition.MISS.eventType()).isEqualTo("ticket.missed");
    }

    @Test
    void aMissPastTheLimitClosesTheTicketAsNoShowAndWritesTicketNoShow() {
        assertThat(TicketTransition.NO_SHOW.apply("called")).contains("no_show");
        assertThat(TicketTransition.NO_SHOW.eventType()).isEqualTo("ticket.no_show");
    }

    @Test
    void withTheDefaultLimitOfTwoTheThirdMissIsTheNoShow() {
        assertThat(TicketTransition.onMiss(0, 2)).as("first miss").isEqualTo(TicketTransition.MISS);
        assertThat(TicketTransition.onMiss(1, 2)).as("second miss").isEqualTo(TicketTransition.MISS);
        assertThat(TicketTransition.onMiss(2, 2)).as("third miss would make miss_count 3 > 2").isEqualTo(TicketTransition.NO_SHOW);
    }

    @Test
    void aLimitOfZeroMakesTheFirstMissANoShow() {
        assertThat(TicketTransition.onMiss(0, 0)).isEqualTo(TicketTransition.NO_SHOW);
    }

    @Test
    void theMissedTransitionsAreOnesTheDatabaseKnows() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        for (TicketTransition transition : List.of(TicketTransition.REANNOUNCE, TicketTransition.MISS, TicketTransition.NO_SHOW)) {
            assertThat(states).contains(transition.from(), transition.to());
        }
    }

    // ---- FR-QUE-051, ADR-0004: where a missed ticket re-enters -----------------------------------------------------

    /** Five waiting tickets that joined 10, 20, 30, 40 and 50 minutes after T0, so their scores are 50, 40, 30, 20 and 10. */
    private static List<Candidate> fiveWaiting() {
        return List.of(joined(1, 10), joined(2, 20), joined(3, 30), joined(4, 40), joined(5, 50));
    }

    @Test
    void aMissedTicketCanReenterAtTheFront() {
        Candidate missed = joined(9, 5); // it waited longest before, so it would sit at the front anyway
        assertThat(placeAfterMiss(missed, fiveWaiting(), ReentryPosition.FRONT, 3)).isEqualTo(1);
        // A ticket that would otherwise be far back is lifted to the front.
        Candidate late = joined(9, 58);
        assertThat(placeAfterMiss(late, fiveWaiting(), ReentryPosition.FRONT, 3)).isEqualTo(1);
    }

    @Test
    void aMissedTicketThatIsAlreadyAheadOfEveryoneNeedsNoAdjustmentToBeAtTheFront() {
        Terms self = QueueEngine.terms(joined(9, 5), NOW);
        assertThat(QueueEngine.reentryAdjustment(self, termsOf(fiveWaiting()), ReentryPosition.FRONT, 3)).isZero();
    }

    @Test
    void aMissedTicketCanReenterAfterNTicketsWithNAheadOfIt() {
        for (int after = 1; after <= 5; after++) {
            assertThat(placeAfterMiss(joined(9, 5), fiveWaiting(), ReentryPosition.AFTER_N, after)).as("after " + after).isEqualTo(after + 1);
            assertThat(placeAfterMiss(joined(9, 58), fiveWaiting(), ReentryPosition.AFTER_N, after)).as("late, after " + after).isEqualTo(after + 1);
        }
    }

    @Test
    void afterNWithFewerThanNTicketsWaitingMeansTheBack() {
        assertThat(placeAfterMiss(joined(9, 5), fiveWaiting(), ReentryPosition.AFTER_N, 8)).isEqualTo(6);
        assertThat(placeAfterMiss(joined(9, 5), List.of(joined(1, 30)), ReentryPosition.AFTER_N, 3)).isEqualTo(2);
    }

    @Test
    void aMissedTicketCanReenterAtTheBack() {
        assertThat(placeAfterMiss(joined(9, 5), fiveWaiting(), ReentryPosition.BACK, 3)).isEqualTo(6);
        assertThat(placeAfterMiss(joined(9, 58), fiveWaiting(), ReentryPosition.BACK, 3)).as("already last, so no adjustment").isEqualTo(6);
        Terms self = QueueEngine.terms(joined(9, 58), NOW);
        assertThat(QueueEngine.reentryAdjustment(self, termsOf(fiveWaiting()), ReentryPosition.BACK, 3)).isZero();
    }

    @Test
    void aMissedTicketReenteringAnEmptyQueueNeedsNoAdjustment() {
        Terms self = QueueEngine.terms(joined(9, 5), NOW);
        for (ReentryPosition position : ReentryPosition.values()) {
            assertThat(QueueEngine.reentryAdjustment(self, List.of(), position, 3)).as(position.name()).isZero();
        }
    }

    @Test
    void theAdjustmentIsInWholeMinutesAndLandsInsideTheGapEvenWhenScoresAreFractions() {
        List<Candidate> waiting = List.of(joined(1, 10.4), joined(2, 20.7), joined(3, 30.2), joined(4, 40.9));
        for (int after = 1; after <= 4; after++) {
            assertThat(placeAfterMiss(joined(9, 3.3), waiting, ReentryPosition.AFTER_N, after)).as("after " + after).isEqualTo(after + 1);
        }
        assertThat(placeAfterMiss(joined(9, 3.3), waiting, ReentryPosition.FRONT, 3)).isEqualTo(1);
        assertThat(placeAfterMiss(joined(9, 3.3), waiting, ReentryPosition.BACK, 3)).isEqualTo(5);
    }

    @Test
    void theMissedTicketKeepsItsPlaceAsTimePassesBecauseEveryScoreGrowsAtTheSameRate() {
        List<Candidate> waiting = fiveWaiting();
        Candidate missed = joined(9, 5);
        int adjustment = QueueEngine.reentryAdjustment(QueueEngine.terms(missed, NOW), termsOf(waiting), ReentryPosition.AFTER_N, 3);
        List<Candidate> all = new ArrayList<>(waiting);
        all.add(new Candidate(missed.id(), missed.createdAt(), missed.waitingSince(), 0, null, 0, adjustment));
        for (int later : new int[] {0, 5, 90, 600}) {
            List<Scored> order = QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW.plusSeconds(later * 60L));
            assertThat(order.stream().filter(s -> s.ticket().id().equals(missed.id())).findFirst().orElseThrow().position()).as(later + " min later").isEqualTo(4);
        }
    }

    @Test
    void theMissedTicketsOriginalWaitIsNotRewritten() {
        Candidate missed = joined(9, 5);
        Terms self = QueueEngine.terms(missed, NOW);
        int adjustment = QueueEngine.reentryAdjustment(self, termsOf(fiveWaiting()), ReentryPosition.AFTER_N, 3);
        Candidate back = new Candidate(missed.id(), missed.createdAt(), missed.waitingSince(), 0, null, 0, adjustment);
        assertThat(QueueEngine.terms(back, NOW).effectiveWaitMinutes()).as("effective wait is the original wait").isEqualTo(self.effectiveWaitMinutes());
        assertThat(QueueEngine.terms(back, NOW).scoreAdjustmentMinutes()).isEqualTo(adjustment);
    }

    @Test
    void anEscalatedTicketStaysAheadOfARentrantWhateverThePositionAsked() {
        // Ticket 1 is past its 15 minute maximum wait, so it is served before every ticket that is not escalated.
        List<Candidate> waiting = List.of(joined(1, 10, 15, 0), joined(2, 20), joined(3, 30));
        assertThat(placeAfterMiss(joined(9, 40), waiting, ReentryPosition.FRONT, 3)).as("front means the front of the rest").isEqualTo(2);
        assertThat(placeAfterMiss(joined(9, 40), waiting, ReentryPosition.AFTER_N, 1)).as("after 1 reaches the escalated ticket").isEqualTo(2);
        assertThat(placeAfterMiss(joined(9, 40), waiting, ReentryPosition.AFTER_N, 2)).isEqualTo(3);
        assertThat(placeAfterMiss(joined(9, 40), waiting, ReentryPosition.BACK, 3)).isEqualTo(4);
    }

    @Test
    void aMissedTicketThatIsEscalatedItselfNeedsNoAdjustment() {
        Candidate missed = joined(9, 5, 15, 0);
        Terms self = QueueEngine.terms(missed, NOW);
        assertThat(self.escalated()).isTrue();
        for (ReentryPosition position : ReentryPosition.values()) {
            assertThat(QueueEngine.reentryAdjustment(self, termsOf(fiveWaiting()), position, 3)).as(position.name()).isZero();
        }
    }

    // ---- Invariant 1: a ticket called twice waited in two stints ------------------------------------------------

    @Test
    void aTicketCalledOnceWaitedFromTheQueueToTheCall() {
        List<Change> changes = List.of(new Change(T0, null, "waiting"), new Change(T0.plusSeconds(300), "waiting", "called"), new Change(T0.plusSeconds(400), "called", "serving"));
        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo(300);
    }

    @Test
    void aTicketMissedAndCalledAgainWaitsOnlyInWaitingNeverWhileCalled() {
        List<Change> changes = List.of(
                new Change(T0, null, "waiting"),
                new Change(T0.plusSeconds(300), "waiting", "called"),
                new Change(T0.plusSeconds(400), "called", "called"), // re-announced
                new Change(T0.plusSeconds(600), "called", "waiting"), // missed: 300 s of being called are not wait
                new Change(T0.plusSeconds(1000), "waiting", "called"), // 400 s more in the queue
                new Change(T0.plusSeconds(1100), "called", "serving"));
        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo(700);
    }

    @Test
    void aStintStillOpenIsNotCounted() {
        assertThat(TicketTimings.accruedWait(T0, List.of(new Change(T0, null, "waiting")))).isZero();
    }

    // ---- the settings that choose all of the above ------------------------------------------------------------------

    /** The properties as Spring binds them; a bind with no key at all finds nothing, so callers name at least one. */
    private static QueueProperties bound(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("qms.queue", QueueProperties.class).get();
    }

    @Test
    void theDefaultsAreTwoMissesThenAfterThreeWithThreeRepeats() {
        QueueProperties defaults = bound(Map.of("qms.queue.primary-link-tolerance-minutes", "5"));
        assertThat(defaults.missLimit()).isEqualTo(2);
        assertThat(defaults.missReentryPosition()).isEqualTo(ReentryPosition.AFTER_N);
        assertThat(defaults.missReentryAfter()).isEqualTo(3);
        assertThat(defaults.announceRepeatLimit()).isEqualTo(3);
    }

    @Test
    void theReentryPositionAndTheLimitsAreConfigurable() {
        QueueProperties front = bound(Map.of("qms.queue.miss-reentry-position", "front", "qms.queue.miss-limit", "4", "qms.queue.announce-repeat-limit", "1"));
        assertThat(front.missReentryPosition()).isEqualTo(ReentryPosition.FRONT);
        assertThat(front.missLimit()).isEqualTo(4);
        assertThat(front.announceRepeatLimit()).isEqualTo(1);
        assertThat(bound(Map.of("qms.queue.miss-reentry-position", "back")).missReentryPosition()).isEqualTo(ReentryPosition.BACK);
        assertThat(bound(Map.of("qms.queue.miss-reentry-position", "after-n", "qms.queue.miss-reentry-after", "5")).missReentryAfter()).isEqualTo(5);
    }
}
