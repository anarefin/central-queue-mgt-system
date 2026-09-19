package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Breaks without a database (NFR-MNT-004): which session states a break may start or end in (SRS §19.3), what blocks it
 * (FR-AGT-021), how long it lasted and whether it overran (FR-AGT-020, FR-AGT-022), and the break report's sums.
 */
class BreakRulesTest {

    private static final Instant T0 = Instant.parse("2026-09-19T10:00:00Z");

    // ---- §19.3: open <-> on_break ---------------------------------------------------------------------------------

    @Test
    void anOpenSessionGoesOnBreakAndABreakEndsBackToOpen() {
        assertThat(BreakRules.start("open")).contains("on_break");
        assertThat(BreakRules.end("on_break")).contains("open");
    }

    @Test
    void onlyAnOpenSessionStartsABreak() {
        for (String state : List.of("on_break", "closing", "closed", "force_closed")) {
            assertThat(BreakRules.start(state)).as(state).isEmpty();
        }
    }

    @Test
    void onlyASessionOnABreakEndsOne() {
        for (String state : List.of("open", "closing", "closed", "force_closed")) {
            assertThat(BreakRules.end(state)).as(state).isEmpty();
        }
    }

    @Test
    void theStatesAreOnesTheDatabaseKnows() {
        List<String> states = List.of("open", "on_break", "closing", "closed", "force_closed");
        assertThat(states).contains(BreakRules.start("open").orElseThrow(), BreakRules.end("on_break").orElseThrow());
    }

    // ---- FR-AGT-021: the ticket in progress must be resolved first --------------------------------------------------

    @Test
    void aCalledOrServingTicketBlocksABreakButAHeldOneDoesNot() {
        assertThat(BreakRules.blocksBreak("called")).isTrue();
        assertThat(BreakRules.blocksBreak("serving")).isTrue();
        assertThat(BreakRules.blocksBreak("held")).isFalse();
    }

    // ---- FR-AGT-020, FR-AGT-022: duration and overrun ---------------------------------------------------------------

    @Test
    void aBreakLastsWholeSecondsFromStartToEndAndNeverLessThanZero() {
        assertThat(BreakRules.seconds(T0, T0.plusSeconds(1500))).isEqualTo(1500);
        assertThat(BreakRules.seconds(T0, T0)).isZero();
        assertThat(BreakRules.seconds(T0, T0.minusSeconds(5))).as("a clock stepping back").isZero();
    }

    @Test
    void aBreakOverranOnlyWhenItLastedLongerThanItsTypesMaximum() {
        assertThat(BreakRules.overran(30 * 60, 30)).as("exactly the maximum is not an overrun").isFalse();
        assertThat(BreakRules.overran(30 * 60 + 1, 30)).isTrue();
        assertThat(BreakRules.overran(99999, null)).as("no maximum").isFalse();
    }

    // ---- FR-AGT-024: availability as an admin sees it ---------------------------------------------------------------

    @Test
    void availabilityFollowsTheSessionAndAnAgentWithNoLiveSessionIsOffline() {
        assertThat(BreakRules.availability("open")).isEqualTo("available");
        assertThat(BreakRules.availability("on_break")).isEqualTo("on_break");
        assertThat(BreakRules.availability("closing")).isEqualTo("closing");
        assertThat(BreakRules.availability("closed")).isEqualTo("offline");
        assertThat(BreakRules.availability("force_closed")).isEqualTo("offline");
        assertThat(BreakRules.availability(null)).isEqualTo("offline");
    }

    // ---- FR-AGT-022: the report -------------------------------------------------------------------------------------

    private static final UUID RINA = new UUID(0, 1);
    private static final UUID KARIM = new UUID(0, 2);
    private static final UUID LUNCH = new UUID(1, 1);
    private static final UUID PRAYER = new UUID(1, 2);

    private static BreakReport.Taken taken(UUID agent, String name, UUID type, Integer max, int minutes) {
        return new BreakReport.Taken(agent, name, type, Map.of("en", type.equals(LUNCH) ? "Lunch" : "Prayer"), max, T0, T0.plusSeconds(minutes * 60L));
    }

    @Test
    void theReportSumsCountTotalAverageAndOverrunsPerAgentAndType() {
        List<BreakReport.Row> rows = BreakReport.summarise(List.of(
                taken(RINA, "Rina", LUNCH, 30, 20),
                taken(RINA, "Rina", LUNCH, 30, 40),
                taken(RINA, "Rina", PRAYER, null, 10),
                taken(KARIM, "Karim", LUNCH, 30, 31)));

        assertThat(rows).hasSize(3);
        BreakReport.Row lunch = rows.stream().filter(r -> r.agentId().equals(RINA) && r.breakType().id().equals(LUNCH)).findFirst().orElseThrow();
        assertThat(lunch.count()).isEqualTo(2);
        assertThat(lunch.totalSeconds()).isEqualTo(3600);
        assertThat(lunch.averageSeconds()).isEqualTo(1800);
        assertThat(lunch.overruns()).as("only the 40 minute one").isEqualTo(1);
        BreakReport.Row prayer = rows.stream().filter(r -> r.breakType().id().equals(PRAYER)).findFirst().orElseThrow();
        assertThat(prayer.overruns()).as("a type with no maximum never overruns").isZero();
        assertThat(rows.stream().filter(r -> r.agentId().equals(KARIM)).findFirst().orElseThrow().overruns()).isEqualTo(1);
    }

    @Test
    void theReportIsOrderedByAgentNameAndIsEmptyWithNoBreaks() {
        assertThat(BreakReport.summarise(List.of())).isEmpty();
        List<BreakReport.Row> rows = BreakReport.summarise(List.of(taken(RINA, "Rina", LUNCH, null, 5), taken(KARIM, "Karim", LUNCH, null, 5)));
        assertThat(rows.stream().map(BreakReport.Row::agentName)).containsExactly("Karim", "Rina");
    }

    @Test
    void theAverageIsRoundedToWholeSeconds() {
        List<BreakReport.Row> rows = BreakReport.summarise(List.of(
                new BreakReport.Taken(RINA, "Rina", LUNCH, Map.of(), null, T0, T0.plusSeconds(1)),
                new BreakReport.Taken(RINA, "Rina", LUNCH, Map.of(), null, T0, T0.plusSeconds(2))));
        assertThat(rows.getFirst().averageSeconds()).isEqualTo(2);
    }
}
