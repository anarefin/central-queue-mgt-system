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
 * Remote arrival, check-in and forfeit without a database (NFR-MNT-004): {@code remote → waiting} (check-in,
 * FR-MOB-021), {@code remote → remote} (a visitor's own delay, FR-MOB-031, and a {@code move_back} forfeit,
 * FR-MOB-022) and {@code remote → forfeited} (a {@code cancel} forfeit, FR-MOB-022, ADR-0004), and that "back N
 * places" lands exactly where it says, anchored on the ticket's own current rank rather than the front of the queue
 * (unlike a missed ticket's re-entry, {@link QueueEngine#reentryAdjustment}).
 */
class RemoteArrivalTest {

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

    /** The place {@code self} takes, 1-based, once it has been given the adjustment for moving {@code places} back. */
    private static int placeAfterMoveBack(Candidate self, List<Candidate> others, int places) {
        int adjustment = QueueEngine.moveBackAdjustment(QueueEngine.terms(self, NOW), termsOf(others), places);
        Candidate back = new Candidate(self.id(), self.createdAt(), self.waitingSince(), 0, self.maxWaitMinutes(), 0, adjustment);
        List<Candidate> all = new ArrayList<>(others);
        all.add(back);
        return QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW).stream().filter(s -> s.ticket().id().equals(self.id())).findFirst().orElseThrow().position();
    }

    /** The place {@code self} currently holds among {@code others} (self not included), 1-based. */
    private static int currentPlace(Candidate self, List<Candidate> others) {
        List<Candidate> all = new ArrayList<>(others);
        all.add(self);
        return QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW).stream().filter(s -> s.ticket().id().equals(self.id())).findFirst().orElseThrow().position();
    }

    // ---- remote → waiting: check-in (FR-MOB-021, §19.1) -----------------------------------------------------------

    @Test
    void checkingInMovesARemoteTicketToWaiting() {
        assertThat(TicketTransition.CHECK_IN.apply("remote")).contains("waiting");
        assertThat(TicketTransition.CHECK_IN.eventType()).isEqualTo("ticket.checked_in");
    }

    @Test
    void checkInIsRefusedFromEveryStateButRemote() {
        for (String state : List.of("waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.CHECK_IN.apply(state)).as(state).isEmpty();
        }
    }

    // ---- remote → remote: a visitor's own delay (FR-MOB-031) ----------------------------------------------------

    @Test
    void aDelayKeepsATicketRemote() {
        assertThat(TicketTransition.DELAY.apply("remote")).contains("remote");
        assertThat(TicketTransition.DELAY.eventType()).isEqualTo("ticket.delayed");
    }

    // ---- remote → forfeited, and remote → remote: forfeit (FR-MOB-022, ADR-0004) ----------------------------------

    @Test
    void aCancelForfeitClosesTheTicketAsForfeited() {
        assertThat(TicketTransition.FORFEIT.apply("remote")).contains("forfeited");
        assertThat(TicketTransition.FORFEIT.eventType()).isEqualTo("ticket.forfeited");
    }

    @Test
    void aMoveBackForfeitKeepsTheTicketRemote() {
        assertThat(TicketTransition.FORFEIT_MOVED_BACK.apply("remote")).contains("remote");
        assertThat(TicketTransition.FORFEIT_MOVED_BACK.eventType()).isEqualTo("ticket.forfeit_moved_back");
    }

    @Test
    void everyRemoteArrivalTransitionIsOneTheDatabaseKnows() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        for (TicketTransition transition : List.of(TicketTransition.CHECK_IN, TicketTransition.DELAY, TicketTransition.FORFEIT, TicketTransition.FORFEIT_MOVED_BACK)) {
            assertThat(states).contains(transition.from(), transition.to());
        }
    }

    // ---- ADR-0004: "back N places" is anchored on the ticket's own current rank -----------------------------------

    /** Five waiting tickets that joined 10, 20, 30, 40 and 50 minutes after T0 (scores 50, 40, 30, 20, 10). */
    private static List<Candidate> fiveWaiting() {
        return List.of(joined(1, 10), joined(2, 20), joined(3, 30), joined(4, 40), joined(5, 50));
    }

    @Test
    void movingBackNPlacesLandsExactlyNPlacesBehindWhereItStandsNow() {
        // A ticket that joined 15 min after T0 currently ranks 2nd among the five (scores 50, [45], 40, 30, 20, 10).
        Candidate self = joined(9, 15);
        List<Candidate> others = fiveWaiting();
        assertThat(currentPlace(self, others)).isEqualTo(2);
        assertThat(placeAfterMoveBack(self, others, 1)).as("back 1 from 2nd").isEqualTo(3);
        assertThat(placeAfterMoveBack(self, others, 3)).as("back 3 from 2nd").isEqualTo(5);
    }

    @Test
    void movingBackFromTheFrontStillCountsFromWhereItStandsNotFromTheQueuesFront() {
        // Joined before everyone else, so it is at the front (score highest of all).
        Candidate self = joined(9, 5);
        List<Candidate> others = fiveWaiting();
        assertThat(currentPlace(self, others)).isEqualTo(1);
        assertThat(placeAfterMoveBack(self, others, 2)).as("back 2 from the front").isEqualTo(3);
    }

    @Test
    void fewerTicketsThanPlacesRemainingMeansTheVeryBack() {
        Candidate self = joined(9, 45);
        List<Candidate> others = fiveWaiting();
        assertThat(placeAfterMoveBack(self, others, 50)).as("50 places back, with only five others, falls to the very back").isEqualTo(others.size() + 1);
    }

    @Test
    void zeroOrNegativePlacesIsNoAdjustment() {
        Terms self = QueueEngine.terms(joined(9, 15), NOW);
        List<Terms> others = termsOf(fiveWaiting());
        assertThat(QueueEngine.moveBackAdjustment(self, others, 0)).isZero();
        assertThat(QueueEngine.moveBackAdjustment(self, others, -1)).isZero();
    }

    @Test
    void movingBackInAnEmptyQueueNeedsNoAdjustment() {
        Terms self = QueueEngine.terms(joined(9, 15), NOW);
        assertThat(QueueEngine.moveBackAdjustment(self, List.of(), 5)).isZero();
    }

    @Test
    void anEscalatedTicketNeedsNoAdjustmentToMoveBack() {
        Candidate self = joined(9, 5, 15, 0);
        Terms terms = QueueEngine.terms(self, NOW);
        assertThat(terms.escalated()).isTrue();
        assertThat(QueueEngine.moveBackAdjustment(terms, termsOf(fiveWaiting()), 3)).isZero();
    }

    @Test
    void theTicketKeepsItsPlaceAsTimePassesBecauseEveryScoreGrowsAtTheSameRate() {
        List<Candidate> others = fiveWaiting();
        Candidate self = joined(9, 15);
        int adjustment = QueueEngine.moveBackAdjustment(QueueEngine.terms(self, NOW), termsOf(others), 2);
        List<Candidate> all = new ArrayList<>(others);
        all.add(new Candidate(self.id(), self.createdAt(), self.waitingSince(), 0, null, 0, adjustment));
        for (int later : new int[] {0, 5, 90, 600}) {
            List<Scored> order = QueueEngine.order(all, QueueStrategy.WEIGHTED_WAIT, NOW.plusSeconds(later * 60L));
            assertThat(order.stream().filter(s -> s.ticket().id().equals(self.id())).findFirst().orElseThrow().position()).as(later + " min later").isEqualTo(4);
        }
    }

    @Test
    void theTicketsOriginalWaitIsNotRewritten() {
        Candidate self = joined(9, 15);
        Terms terms = QueueEngine.terms(self, NOW);
        int adjustment = QueueEngine.moveBackAdjustment(terms, termsOf(fiveWaiting()), 2);
        Candidate back = new Candidate(self.id(), self.createdAt(), self.waitingSince(), 0, null, 0, adjustment);
        assertThat(QueueEngine.terms(back, NOW).effectiveWaitMinutes()).as("effective wait is the original wait").isEqualTo(terms.effectiveWaitMinutes());
        assertThat(QueueEngine.terms(back, NOW).scoreAdjustmentMinutes()).isEqualTo(adjustment);
    }

    // ---- the settings that choose the defaults (FR-MOB-020, FR-MOB-022, FR-MOB-031) --------------------------------

    private static QueueProperties bound(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("qms.queue", QueueProperties.class).get();
    }

    @Test
    void theDefaultsAreForfeitBackFiveDelayBackThreeApproachingAtThreeOrFifteenMinutes() {
        QueueProperties defaults = bound(Map.of("qms.queue.primary-link-tolerance-minutes", "5"));
        assertThat(defaults.remoteForfeitBackPlaces()).isEqualTo(5);
        assertThat(defaults.remoteDelayBackPlaces()).isEqualTo(3);
        assertThat(defaults.approachingTurnThresholdTickets()).isEqualTo(3);
        assertThat(defaults.approachingTurnThresholdMinutes()).isEqualTo(15);
    }

    @Test
    void theBackPlacesAndThresholdsAreConfigurable() {
        QueueProperties custom = bound(Map.of(
                "qms.queue.remote-forfeit-back-places", "8",
                "qms.queue.remote-delay-back-places", "1",
                "qms.queue.approaching-turn-threshold-tickets", "5",
                "qms.queue.approaching-turn-threshold-minutes", "20"));
        assertThat(custom.remoteForfeitBackPlaces()).isEqualTo(8);
        assertThat(custom.remoteDelayBackPlaces()).isEqualTo(1);
        assertThat(custom.approachingTurnThresholdTickets()).isEqualTo(5);
        assertThat(custom.approachingTurnThresholdMinutes()).isEqualTo(20);
    }
}
