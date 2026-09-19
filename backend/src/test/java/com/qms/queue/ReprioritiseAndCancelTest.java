package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.queue.PriorityPrecedence.Choice;
import com.qms.queue.PriorityPrecedence.Source;
import com.qms.queue.QueueEngine.Candidate;
import com.qms.queue.QueueEngine.Scored;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class precedence at issue, a change of class and staff cancel without a database (NFR-MNT-004): the order the sources are
 * consulted in (FR-QUE-011), that a change of class moves a waiting ticket by Head start alone (FR-QUE-012, ADR-0003), and the
 * transition {@code waiting → cancelled} and every other cancel of §19.1.
 */
class ReprioritiseAndCancelTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60 * 60);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    // ---- FR-QUE-011: where the class comes from -------------------------------------------------------------------

    @Test
    void theSourcesAreConsultedInTheOrderStaffAppointmentVisitorCategoryChannelService() {
        UUID manual = id(1);
        UUID appointment = id(2);
        UUID category = id(3);
        UUID channel = id(4);
        UUID service = id(5);

        assertThat(PriorityPrecedence.choose(manual, appointment, category, channel, service)).isEqualTo(new Choice(manual, Source.MANUAL));
        assertThat(PriorityPrecedence.choose(null, appointment, category, channel, service)).isEqualTo(new Choice(appointment, Source.APPOINTMENT));
        assertThat(PriorityPrecedence.choose(null, null, category, channel, service)).isEqualTo(new Choice(category, Source.VISITOR_CATEGORY));
        assertThat(PriorityPrecedence.choose(null, null, null, channel, service)).isEqualTo(new Choice(channel, Source.CHANNEL_DEFAULT));
        assertThat(PriorityPrecedence.choose(null, null, null, null, service)).isEqualTo(new Choice(service, Source.SERVICE_DEFAULT));
    }

    @Test
    void aLowerSourceNeverOverridesAHigherOneWhateverIsBelowIt() {
        UUID[] classes = {id(1), id(2), id(3), id(4), id(5)};
        Source[] sources = {Source.MANUAL, Source.APPOINTMENT, Source.VISITOR_CATEGORY, Source.CHANNEL_DEFAULT, Source.SERVICE_DEFAULT};
        for (int winner = 0; winner < classes.length; winner++) {
            UUID[] given = new UUID[classes.length];
            for (int i = winner; i < classes.length; i++) given[i] = classes[i];
            Choice choice = PriorityPrecedence.choose(given[0], given[1], given[2], given[3], given[4]);
            assertThat(choice).as("the first named source is " + sources[winner]).isEqualTo(new Choice(classes[winner], sources[winner]));
        }
    }

    @Test
    void whenNoSourceNamesAClassTheTicketBelongsToTheDefaultClass() {
        Choice choice = PriorityPrecedence.choose(null, null, null, null, null);
        assertThat(choice.classId()).as("stored as no class, which reads as the default class").isNull();
        assertThat(choice.source()).isEqualTo(Source.NONE);
        assertThat(Source.CHANNEL_DEFAULT.wire()).isEqualTo("channel_default");
    }

    // ---- FR-QUE-012: a change of class -----------------------------------------------------------------------------

    private static Candidate waiting(int n, double minutesAfterT0, int headstart) {
        Instant at = T0.plusMillis((long) (minutesAfterT0 * 60_000));
        return new Candidate(id(n), at, at, headstart, null, 0, 0);
    }

    private static List<UUID> order(List<Candidate> candidates) {
        return QueueEngine.order(candidates, QueueStrategy.WEIGHTED_WAIT, NOW).stream().map(s -> s.ticket().id()).toList();
    }

    @Test
    void onlyAWaitingTicketCanChangeClass() {
        assertThat(TicketTransition.mayReprioritise("waiting")).isTrue();
        for (String state : List.of("remote", "paused", "called", "serving", "held", "completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.mayReprioritise(state)).as(state).isFalse();
        }
    }

    @Test
    void aTicketGivenAClassWithAHeadStartPassesTheTicketsItsHeadStartCoversAndKeepsItsWait() {
        Candidate first = waiting(1, 0, 0);
        Candidate second = waiting(2, 10, 0);
        Candidate third = waiting(3, 20, 0);
        Candidate fourth = waiting(4, 30, 0);
        assertThat(order(List.of(first, second, third, fourth))).containsExactly(id(1), id(2), id(3), id(4));

        // The fourth ticket, 30 minutes in, is given a class worth 25 minutes: it passes the two tickets that scored 40 and 50, not the first (60).
        Candidate promoted = new Candidate(fourth.id(), fourth.createdAt(), fourth.waitingSince(), 25, null, 0, 0);
        List<Scored> ordered = QueueEngine.order(List.of(first, second, third, promoted), QueueStrategy.WEIGHTED_WAIT, NOW);
        assertThat(ordered).extracting(s -> s.ticket().id()).as("30 + 25 = 55 beats 40 and 50 but not 60").containsExactly(id(1), id(4), id(2), id(3));
        assertThat(ordered.get(1).terms().effectiveWaitMinutes()).as("its own wait is untouched").isEqualTo(30.0);
        assertThat(ordered.get(1).terms().headstartMinutes()).isEqualTo(25.0);
    }

    @Test
    void theOtherTicketsKeepTheirOrderWhenOneChangesClass() {
        List<Candidate> before = List.of(waiting(1, 0, 0), waiting(2, 5, 0), waiting(3, 10, 0), waiting(4, 15, 0));
        Candidate promoted = new Candidate(id(3), before.get(2).createdAt(), before.get(2).waitingSince(), 120, null, 0, 0);
        List<UUID> after = order(List.of(before.get(0), before.get(1), promoted, before.get(3)));
        assertThat(after.getFirst()).isEqualTo(id(3));
        assertThat(after.subList(1, 4)).as("the rest stay in their own order").containsExactly(id(1), id(2), id(4));
    }

    @Test
    void movingATicketBackToTheNormalClassTakesItsHeadStartAway() {
        Candidate vip = new Candidate(id(2), T0.plusSeconds(600), T0.plusSeconds(600), 60, null, 0, 0);
        assertThat(order(List.of(waiting(1, 0, 0), vip))).containsExactly(id(2), id(1));
        Candidate normal = new Candidate(id(2), vip.createdAt(), vip.waitingSince(), 0, null, 0, 0);
        assertThat(order(List.of(waiting(1, 0, 0), normal))).containsExactly(id(1), id(2));
    }

    // ---- §19.1: waiting → cancelled, and cancel from every active state -----------------------------------------------

    @Test
    void cancellingAWaitingTicketClosesItAsCancelledAndWritesTicketCancelled() {
        assertThat(TicketTransition.cancel("waiting")).contains("cancelled");
        assertThat(TicketTransition.CANCELLED_EVENT).isEqualTo("ticket.cancelled");
        assertThat(TicketTransition.CANCELLED).isEqualTo("cancelled");
    }

    @Test
    void anyActiveTicketCanBeCancelled() {
        for (String state : List.of("remote", "waiting", "paused", "called", "serving", "held")) {
            assertThat(TicketTransition.cancel(state)).as(state).contains("cancelled");
        }
    }

    @Test
    void aTicketThatHasAlreadyClosedCannotBeCancelled() {
        for (String state : List.of("completed", "transferred", "no_show", "cancelled", "forfeited")) {
            assertThat(TicketTransition.cancel(state)).as(state).isEmpty();
        }
        assertThat(TicketTransition.cancel("unknown")).isEmpty();
    }

    @Test
    void cancelIsNotOneOfTheTransitionsThatLeaveFromOneState() {
        for (TicketTransition transition : TicketTransition.values()) {
            assertThat(transition.to()).as(transition.name()).isNotEqualTo("cancelled");
        }
    }

    // ---- Invariant 1: the wait a cancelled ticket stores ---------------------------------------------------------------

    private static TicketTimings.Change change(int minutesAfterT0, String from, String to) {
        return new TicketTimings.Change(T0.plusSeconds(minutesAfterT0 * 60L), from, to);
    }

    @Test
    void aCancelledWaitingTicketsWaitRunsUntilTheCancel() {
        List<TicketTimings.Change> changes = new ArrayList<>(List.of(change(0, null, "waiting")));
        changes.add(change(7, "waiting", "cancelled"));
        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo(7 * 60);
    }

    @Test
    void aTicketCancelledWhileCalledOrServingWaitedOnlyUntilItsCall() {
        List<TicketTimings.Change> calledThenCancelled = List.of(change(0, null, "waiting"), change(4, "waiting", "called"), change(9, "called", "cancelled"));
        assertThat(TicketTimings.accruedWait(T0, calledThenCancelled)).isEqualTo(4 * 60);
        List<TicketTimings.Change> servedThenCancelled = List.of(change(0, null, "waiting"), change(4, "waiting", "called"), change(5, "called", "serving"), change(20, "serving", "cancelled"));
        assertThat(TicketTimings.accruedWait(T0, servedThenCancelled)).isEqualTo(4 * 60);
    }

    @Test
    void aChangeOfClassDoesNotBreakTheWaitTheTicketHasAccrued() {
        // ticket.position_changed is waiting → waiting: the stints add up to the same wait.
        List<TicketTimings.Change> changes = List.of(change(0, null, "waiting"), change(3, "waiting", "waiting"), change(8, "waiting", "cancelled"));
        assertThat(TicketTimings.accruedWait(T0, changes)).isEqualTo(8 * 60);
    }
}
