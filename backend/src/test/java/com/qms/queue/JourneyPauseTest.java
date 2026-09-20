package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.TicketTimings.Change;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code waiting ↔ paused} without a database (NFR-MNT-004): FR-QUE-063's pause and its reverse (SRS §19.1), and that a
 * paused stint accrues no wait (Invariant 1). The database write that applies this to a Visit's siblings is
 * {@link TicketEvents}; this suite is only the transitions themselves and the arithmetic Invariant 1 depends on.
 */
class JourneyPauseTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");

    @Test
    void pauseLeavesWaitingForPausedAndIsWrittenAsTicketPaused() {
        assertThat(TicketTransition.PAUSE.apply("waiting")).contains("paused");
        assertThat(TicketTransition.PAUSE.eventType()).isEqualTo("ticket.paused");
    }

    @Test
    void onlyAWaitingTicketCanBePaused() {
        for (String state : List.of("remote", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.PAUSE.apply(state)).as(state).isEmpty();
        }
    }

    @Test
    void unpauseLeavesPausedForWaitingAndIsWrittenAsTicketUnpaused() {
        assertThat(TicketTransition.UNPAUSE.apply("paused")).contains("waiting");
        assertThat(TicketTransition.UNPAUSE.eventType()).isEqualTo("ticket.unpaused");
    }

    @Test
    void onlyAPausedTicketCanBeUnpaused() {
        for (String state : List.of("remote", "waiting", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.UNPAUSE.apply(state)).as(state).isEmpty();
        }
    }

    @Test
    void pauseAndUnpauseAreStatesTheDatabaseKnows() {
        List<String> states = List.of("remote", "waiting", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited");
        assertThat(states).contains(TicketTransition.PAUSE.from(), TicketTransition.PAUSE.to(), TicketTransition.UNPAUSE.from(), TicketTransition.UNPAUSE.to());
    }

    @Test
    void aTicketRemainsPausableInTheActiveStates() {
        // Every active state, including paused, can still be cancelled (SRS §19.1: any active state to cancelled).
        for (String state : List.of("remote", "waiting", "paused", "called", "serving", "held")) {
            assertThat(TicketTransition.cancel(state)).as(state).isPresent();
        }
    }

    // ---- Invariant 1: a paused stint accrues no wait ------------------------------------------------------------

    @Test
    void aRoundTripThroughPausedAccruesNoWaitForTheTimeItWasPaused() {
        // Joins the queue at T0, waits 5 minutes, is paused (another stop of its Visit is called) for 3 minutes, then
        // waits another 4 minutes before it is itself called: only the 5 + 4 = 9 minutes of waiting should count.
        List<Change> changes = List.of(
                new Change(T0, null, "waiting"),
                new Change(T0.plusSeconds(5 * 60), "waiting", "paused"),
                new Change(T0.plusSeconds(8 * 60), "paused", "waiting"),
                new Change(T0.plusSeconds(12 * 60), "waiting", "called"));

        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo((5 + 4) * 60);
    }

    @Test
    void severalPausedStintsEachAccrueNoWait() {
        // Paused twice (two other stops of the Journey called in turn), waiting in between and after.
        List<Change> changes = List.of(
                new Change(T0, null, "waiting"),
                new Change(T0.plusSeconds(2 * 60), "waiting", "paused"),
                new Change(T0.plusSeconds(3 * 60), "paused", "waiting"),
                new Change(T0.plusSeconds(5 * 60), "waiting", "paused"),
                new Change(T0.plusSeconds(9 * 60), "paused", "waiting"),
                new Change(T0.plusSeconds(11 * 60), "waiting", "called"));

        // Stints: [0,2) + [3,5) + [9,11) = 2 + 2 + 2 = 6 minutes.
        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo(6 * 60);
    }
}
