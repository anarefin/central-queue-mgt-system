package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The agent KPI "login adherence"'s (§15.2) rostered-hours proxy, in isolation from any database. */
class RosteredHoursTest {

    private static final Instant MONDAY = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void aSiteWithNoConfiguredHoursIsUnrestrictedTwentyFourSevenSoEveryDayIsAFullDay() {
        long seconds = RosteredHours.seconds(Map.of(), MONDAY, MONDAY.plusSeconds(3 * 86400));
        assertThat(seconds).isEqualTo(3 * 86400L);
    }

    @Test
    void aConfiguredWeekdaySumsItsOwnOpenSecondsAcrossEveryOccurrenceInRange() {
        // Monday: 8h (28800s); Tuesday: closed (absent, ISO weekday 2).
        Map<Integer, Long> weekly = Map.of(1, 28800L);
        long seconds = RosteredHours.seconds(weekly, MONDAY, MONDAY.plusSeconds(2 * 86400));
        assertThat(seconds).isEqualTo(28800L);
    }

    @Test
    void aWeekdayAbsentFromTheConfiguredMapIsClosedAndContributesNothing() {
        Map<Integer, Long> weekly = Map.of(1, 28800L);
        // Just the Tuesday after the Monday, weekday 2, not in the map.
        long seconds = RosteredHours.seconds(weekly, MONDAY.plusSeconds(86400), MONDAY.plusSeconds(2 * 86400));
        assertThat(seconds).isZero();
    }

    @Test
    void anEmptyOrInvertedRangeIsZero() {
        assertThat(RosteredHours.seconds(Map.of(), MONDAY, MONDAY)).isZero();
        assertThat(RosteredHours.seconds(Map.of(), MONDAY, MONDAY.minusSeconds(1))).isZero();
    }
}
