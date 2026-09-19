package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.issuance.TokenNumbering.Period;
import com.qms.issuance.TokenNumbering.ResetBoundary;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The numbering rule of SRS §4.4 and its parameters (FR-CFG-018): prefix, separator, padding 0 to 6, and the reset
 * boundary (daily, weekly, monthly, never) evaluated at a site-local reset time.
 */
class TokenNumberingTest {

    @Test
    void aTokenNumberIsPrefixSeparatorAndPaddedSequenceInWesternArabicDigits() {
        assertThat(TokenNumbering.format("S", 42)).isEqualTo("S-042");
        assertThat(TokenNumbering.format("QC", 1)).isEqualTo("QC-001");
        assertThat(TokenNumbering.format("D", 1000)).as("a longer sequence is never truncated").isEqualTo("D-1000");
        assertThat(TokenNumbering.format("স", 7)).as("digits stay Western Arabic whatever the prefix").isEqualTo("স-007");
    }

    @Test
    void theResetKeyIsTheSiteLocalCalendarDay() {
        Instant lateEveningUtc = Instant.parse("2026-09-19T20:30:00Z");

        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("UTC"))).isEqualTo("2026-09-19");
        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("Asia/Dhaka"))).as("already tomorrow in Dhaka (UTC+6)").isEqualTo("2026-09-20");
        assertThat(TokenNumbering.resetKey(lateEveningUtc, ZoneId.of("America/Los_Angeles"))).isEqualTo("2026-09-19");
    }

    @Test
    void paddingIsAMinimumWidthFromZeroToSixAndTheSeparatorAnyString() {
        assertThat(TokenNumbering.format("A", "", 0, 7)).isEqualTo("A7");
        assertThat(TokenNumbering.format("A", "", 3, 7)).isEqualTo("A007");
        assertThat(TokenNumbering.format("A", "/", 6, 42)).isEqualTo("A/000042");
        assertThat(TokenNumbering.format("A", " - ", 2, 123)).as("never truncated").isEqualTo("A - 123");
    }

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    private static Period period(String utc, ResetBoundary boundary, String resetTime) {
        return TokenNumbering.period(Instant.parse(utc), DHAKA, boundary, LocalTime.parse(resetTime));
    }

    @Test
    void aDailyPeriodEndsAtTheSiteLocalResetTimeNotAtMidnight() {
        // 2026-09-19T21:59Z is 03:59 on the 20th in Dhaka: still the period that began at 04:00 on the 19th.
        Period before = period("2026-09-19T21:59:00Z", ResetBoundary.DAILY, "04:00");
        Period after = period("2026-09-19T22:00:00Z", ResetBoundary.DAILY, "04:00");

        assertThat(before.key()).isEqualTo("2026-09-19");
        assertThat(before.start()).isEqualTo(Instant.parse("2026-09-18T22:00:00Z"));
        assertThat(before.end()).isEqualTo(Instant.parse("2026-09-19T22:00:00Z"));
        assertThat(after.key()).as("04:00 in Dhaka on the 20th").isEqualTo("2026-09-20");
        assertThat(after.start()).isEqualTo(before.end());
    }

    @Test
    void aWeeklyPeriodBeginsOnMondayAtTheResetTimeAndIsNamedByItsIsoWeek() {
        // 2026-09-14 is a Monday.
        assertThat(period("2026-09-14T17:59:00Z", ResetBoundary.WEEKLY, "00:00").key()).as("Monday 23:59 Dhaka").isEqualTo("2026-W38");
        assertThat(period("2026-09-20T17:59:00Z", ResetBoundary.WEEKLY, "00:00").key()).as("Sunday 23:59 Dhaka").isEqualTo("2026-W38");
        Period nextWeek = period("2026-09-20T18:00:00Z", ResetBoundary.WEEKLY, "00:00");
        assertThat(nextWeek.key()).as("Monday 00:00 Dhaka").isEqualTo("2026-W39");
        assertThat(nextWeek.end()).isEqualTo(Instant.parse("2026-09-27T18:00:00Z"));
        assertThat(period("2026-09-21T01:00:00Z", ResetBoundary.WEEKLY, "08:00").key()).as("Monday 07:00 is before the 08:00 reset").isEqualTo("2026-W38");
        assertThat(period("2026-12-31T00:00:00Z", ResetBoundary.WEEKLY, "00:00").key()).as("ISO week-based year").isEqualTo("2026-W53");
    }

    @Test
    void aMonthlyPeriodBeginsOnTheFirstAtTheResetTime() {
        assertThat(period("2026-09-30T17:59:00Z", ResetBoundary.MONTHLY, "00:00").key()).isEqualTo("2026-09");
        assertThat(period("2026-09-30T18:00:00Z", ResetBoundary.MONTHLY, "00:00").key()).as("1 October 00:00 Dhaka").isEqualTo("2026-10");
        assertThat(period("2026-09-30T18:00:00Z", ResetBoundary.MONTHLY, "06:00").key()).as("still before the 06:00 reset").isEqualTo("2026-09");
        assertThat(period("2026-01-01T00:00:00Z", ResetBoundary.MONTHLY, "12:00").key()).as("across the year boundary").isEqualTo("2025-12");
    }

    @Test
    void aSequenceThatNeverResetsHasOneEndlessPeriod() {
        Period first = period("2026-01-01T00:00:00Z", ResetBoundary.NEVER, "00:00");
        Period later = period("2030-06-30T00:00:00Z", ResetBoundary.NEVER, "13:00");

        assertThat(first.key()).isEqualTo("never").isEqualTo(later.key());
        assertThat(first.end()).isNull();
    }

    @Test
    void theKeysOfDifferentBoundariesCannotBeMistakenForEachOther() {
        String at = "2026-09-14T06:00:00Z";
        assertThat(java.util.Set.of(
                        period(at, ResetBoundary.DAILY, "00:00").key(),
                        period(at, ResetBoundary.WEEKLY, "00:00").key(),
                        period(at, ResetBoundary.MONTHLY, "00:00").key(),
                        period(at, ResetBoundary.NEVER, "00:00").key()))
                .hasSize(4);
    }
}
