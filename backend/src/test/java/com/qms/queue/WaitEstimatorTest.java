package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The wait estimate as arithmetic: FR-QUE-040 (the formula), FR-QUE-041 (the rolling average) and FR-QUE-042 (a rounded range). */
class WaitEstimatorTest {

    // ---- FR-QUE-040: tickets ahead / max(open counters, 1) x rolling average handling time ----------------------

    @Test
    void theEstimateIsTicketsAheadOverOpenCountersTimesTheHandlingTime() {
        assertThat(WaitEstimator.minutes(6, 2, 10)).isCloseTo(30, within(1e-9));
        assertThat(WaitEstimator.minutes(7, 4, 12)).isCloseTo(21, within(1e-9));
    }

    @Test
    void withNoOpenCounterTheDivisorIsOneNotZero() {
        assertThat(WaitEstimator.minutes(3, 0, 10)).isCloseTo(30, within(1e-9));
        assertThat(WaitEstimator.minutes(3, 0, 10)).isEqualTo(WaitEstimator.minutes(3, 1, 10));
    }

    @Test
    void nobodyAheadMeansNothingToWaitFor() {
        assertThat(WaitEstimator.minutes(0, 3, 10)).isZero();
    }

    // ---- FR-QUE-041: trailing 20 completed tickets, expected time below 5 samples -------------------------------

    @Test
    void belowFiveSamplesTheExpectedHandlingTimeIsUsed() {
        assertThat(WaitEstimator.handlingMinutes(List.of(), 10)).isEqualTo(10);
        assertThat(WaitEstimator.handlingMinutes(List.of(1200, 1200, 1200, 1200), 10)).isEqualTo(10);
    }

    @Test
    void fromFiveSamplesTheAverageOfThemIsUsed() {
        assertThat(WaitEstimator.handlingMinutes(List.of(1200, 1200, 1200, 1200, 1200), 10)).isCloseTo(20, within(1e-9));
        assertThat(WaitEstimator.handlingMinutes(List.of(300, 600, 900, 1200, 1500, 1800), 10)).isCloseTo(17.5, within(1e-9));
    }

    @Test
    void onlyTheNewestTwentyCountAndTheListIsNewestFirst() {
        List<Integer> newestFirst = new ArrayList<>(Collections.nCopies(WaitEstimator.WINDOW, 600));
        newestFirst.addAll(Collections.nCopies(30, 6000));
        assertThat(WaitEstimator.handlingMinutes(newestFirst, 99)).isCloseTo(10, within(1e-9));
    }

    // ---- FR-QUE-042 / FR-ISS-005: a rounded range, never a promise ----------------------------------------------

    @ParameterizedTest
    @CsvSource({"0,0,10,0,5", "1,1,10,10,15", "2,1,10,20,25", "3,2,11,15,20", "3,2,12,15,20", "7,1,10,70,75", "4,0,4,15,20"})
    void theRangeIsFiveMinutesWideAndRoundedToFive(int ahead, int counters, double handling, int low, int high) {
        assertThat(WaitEstimator.estimate(ahead, counters, handling)).isEqualTo(new WaitEstimate(low, high));
    }

    @ParameterizedTest
    @CsvSource({"5,2,7.3", "9,3,4.9", "13,1,2.2", "2,2,14.9", "40,3,6"})
    void theRangeAlwaysHoldsTheFigureItRoundsAndNeverPromisesIt(int ahead, int counters, double handling) {
        double minutes = WaitEstimator.minutes(ahead, counters, handling);
        WaitEstimate range = WaitEstimator.estimate(ahead, counters, handling);
        assertThat(minutes).isBetween((double) range.low(), (double) range.high());
        assertThat(range.high() - range.low()).isEqualTo(WaitEstimator.BUCKET_MINUTES);
    }
}
