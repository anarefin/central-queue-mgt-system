package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The rules of the call timeout and of parallel serving without a database (NFR-MNT-004): when a called ticket that nobody acts on
 * has timed out (FR-QUE-032), how many tickets a counter may have in progress (FR-AGT-010, FR-AGT-011), and that a ticket returned after
 * a timeout leaves {@code called} for {@code waiting} through the same transition a force-closed session uses (SRS §19.1).
 */
class CallTimeoutAndParallelTest {

    private static final Instant CALLED = Instant.parse("2026-09-19T10:00:00Z");

    private static QueueProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("qms.queue", QueueProperties.class);
    }

    // ---- FR-QUE-032: the call timeout ---------------------------------------------------------------------------

    @Test
    void theCallTimeoutDefaultsToNinetySecondsAndIsConfigurable() {
        assertThat(bind(Map.of()).callTimeoutSeconds()).isEqualTo(90);
        assertThat(bind(Map.of("qms.queue.call-timeout-seconds", "45")).callTimeoutSeconds()).isEqualTo(45);
        assertThat(bind(Map.of("qms.queue.call-timeout-seconds", "0")).callTimeoutSeconds()).as("0 switches it off").isZero();
        assertThatThrownBy(() -> bind(Map.of("qms.queue.call-timeout-seconds", "-1"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCallTimesOutOnceTheTimeoutHasPassedSinceTheCall() {
        assertThat(CallRules.callTimedOut(CALLED, CALLED.plusSeconds(89), 90)).isFalse();
        assertThat(CallRules.callTimedOut(CALLED, CALLED.plusSeconds(90), 90)).as("at the timeout").isTrue();
        assertThat(CallRules.callTimedOut(CALLED, CALLED.plusSeconds(600), 90)).isTrue();
    }

    @Test
    void aTimeoutOfZeroSwitchesTheCheckOffAndATicketNeverCalledNeverTimesOut() {
        assertThat(CallRules.callTimedOut(CALLED, CALLED.plusSeconds(3600), 0)).isFalse();
        assertThat(CallRules.callTimedOut(null, CALLED.plusSeconds(3600), 90)).isFalse();
    }

    @Test
    void aTicketReturnedAfterATimeoutLeavesCalledForWaitingAndOnlyFromCalled() {
        TicketTransition returning = TicketTransition.returnFrom("called").orElseThrow();
        assertThat(returning.apply("called")).contains("waiting");
        assertThat(returning.eventType()).as("the ticket's place changed").isEqualTo("ticket.position_changed");
        assertThat(returning.apply("serving")).isEmpty();
        assertThat(returning.apply("waiting")).isEmpty();
    }

    // ---- FR-AGT-010, FR-AGT-011: parallel serving ---------------------------------------------------------------

    @Test
    void aServiceThatIsNotParallelLetsACounterServeOneAtATimeWhateverItsMaximum() {
        assertThat(CallRules.limit(false, 1)).isEqualTo(1);
        assertThat(CallRules.limit(false, 5)).isEqualTo(1);
    }

    @Test
    void aParallelServiceLetsACounterServeItsConfiguredMaximum() {
        assertThat(CallRules.limit(true, 3)).isEqualTo(3);
        assertThat(CallRules.limit(true, 0)).as("never less than one").isEqualTo(1);
    }

    @Test
    void anIdleCounterMayAlwaysTakeATicketAndABusyOneOnlyWithRoomInEveryServiceInProgress() {
        assertThat(CallRules.mayTakeAnother(0, List.of(1))).isTrue();
        assertThat(CallRules.mayTakeAnother(1, List.of(1, 1))).as("call next is disabled while a ticket is in progress (FR-AGT-010)").isFalse();
        assertThat(CallRules.mayTakeAnother(1, List.of(3, 3))).isTrue();
        assertThat(CallRules.mayTakeAnother(2, List.of(3, 3, 3))).isTrue();
        assertThat(CallRules.mayTakeAnother(3, List.of(3, 3, 3))).as("the maximum is reached").isFalse();
    }

    @Test
    void aCounterBusyWithAServiceThatIsNotParallelTakesNoSecondTicketAndNeitherDoesOneAskingForSuchAService() {
        assertThat(CallRules.mayTakeAnother(1, List.of(1, 3))).as("busy with a serial Service").isFalse();
        assertThat(CallRules.mayTakeAnother(1, List.of(3, 1))).as("the next is a serial Service").isFalse();
    }
}
