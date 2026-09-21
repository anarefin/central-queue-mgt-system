package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** FR-RPT-005's three cadences: how far back a delivery's own window reaches, and when the schedule next fires. */
class ReportScheduleCadenceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    @Test
    void dailyReachesBackOneDayAndFiresAgainOneDayLater() {
        assertThat(ReportScheduleCadence.DAILY.windowStart(NOW)).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(ReportScheduleCadence.DAILY.next(NOW)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void weeklyReachesBackSevenDaysAndFiresAgainSevenDaysLater() {
        assertThat(ReportScheduleCadence.WEEKLY.windowStart(NOW)).isEqualTo(NOW.minus(Duration.ofDays(7)));
        assertThat(ReportScheduleCadence.WEEKLY.next(NOW)).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void monthlyReachesBackOneCalendarMonthEvenAcrossDifferentMonthLengths() {
        // 2026-03-15 minus one calendar month is 2026-02-15, not a fixed 30/31-day duration.
        assertThat(ReportScheduleCadence.MONTHLY.windowStart(NOW)).isEqualTo(Instant.parse("2026-02-15T10:00:00Z"));
        assertThat(ReportScheduleCadence.MONTHLY.next(NOW)).isEqualTo(Instant.parse("2026-04-15T10:00:00Z"));

        // 2026-03-31 plus one calendar month clamps to 2026-04-30 (April has no 31st), the same clamping
        // java.time.ZonedDateTime#plusMonths already applies.
        Instant endOfMarch = Instant.parse("2026-03-31T10:00:00Z");
        assertThat(ReportScheduleCadence.MONTHLY.next(endOfMarch)).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
    }

    @Test
    void wireRoundTrips() {
        assertThat(ReportScheduleCadence.fromWire("daily")).contains(ReportScheduleCadence.DAILY);
        assertThat(ReportScheduleCadence.fromWire("weekly")).contains(ReportScheduleCadence.WEEKLY);
        assertThat(ReportScheduleCadence.fromWire("monthly")).contains(ReportScheduleCadence.MONTHLY);
        assertThat(ReportScheduleCadence.fromWire("yearly")).isEmpty();
        for (ReportScheduleCadence cadence : ReportScheduleCadence.values()) {
            assertThat(ReportScheduleCadence.fromWire(cadence.wire())).contains(cadence);
        }
    }
}
