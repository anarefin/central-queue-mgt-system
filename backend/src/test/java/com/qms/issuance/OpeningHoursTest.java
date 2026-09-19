package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.issuance.OpeningHours.Day;
import com.qms.issuance.OpeningHours.Holiday;
import com.qms.issuance.OpeningHours.Refusal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Weekly hours with holidays, half-days and per-channel cut-offs (FR-CFG-020, FR-CFG-021, FR-CFG-022, FR-ISS-003). The clock
 * is the Site's own: 2026-09-19 is a Saturday, 2026-09-20 a Sunday, 2026-09-21 a Monday.
 */
class OpeningHoursTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    private static ZonedDateTime at(String date, String time) {
        return ZonedDateTime.parse(date + "T" + time + ":00+06:00[Asia/Dhaka]");
    }

    /** Sunday to Thursday 09:00 to 17:00. */
    private static Map<DayOfWeek, Day> officeWeek() {
        Map<DayOfWeek, Day> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : new DayOfWeek[] {DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY}) {
            week.put(day, new Day(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        }
        return week;
    }

    private static String reason(Optional<Refusal> refusal) {
        return refusal.map(Refusal::reason).orElse("open");
    }

    @Test
    void aSiteWithNoHoursTakesTicketsAtAnyTime() {
        assertThat(reason(OpeningHours.check(at("2026-09-19", "03:00"), Map.of(), null, 0))).isEqualTo("open");
        assertThat(reason(OpeningHours.check(at("2026-09-19", "23:59"), Map.of(), null, 60))).isEqualTo("open");
    }

    @Test
    void opensAtTheOpeningTimeAndClosesAtTheClosingTime() {
        assertThat(reason(OpeningHours.check(at("2026-09-20", "08:59"), officeWeek(), null, 0))).isEqualTo("outside_hours");
        assertThat(reason(OpeningHours.check(at("2026-09-20", "09:00"), officeWeek(), null, 0))).isEqualTo("open");
        assertThat(reason(OpeningHours.check(at("2026-09-20", "16:59"), officeWeek(), null, 0))).isEqualTo("open");
        assertThat(reason(OpeningHours.check(at("2026-09-20", "17:00"), officeWeek(), null, 0))).isEqualTo("outside_hours");
    }

    @Test
    void aWeekdayWithNoHoursIsAClosedDay() {
        assertThat(reason(OpeningHours.check(at("2026-09-19", "12:00"), officeWeek(), null, 0))).as("Saturday").isEqualTo("outside_hours");
    }

    @Test
    void aHolidayClosesTheWholeDayAndNamesIt() {
        Optional<Refusal> refusal = OpeningHours.check(at("2026-09-20", "12:00"), officeWeek(), new Holiday("Independence Day", false, null), 0);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().reason()).isEqualTo("holiday");
        assertThat(refusal.get().details()).containsEntry("holiday", "Independence Day");
    }

    @Test
    void aHalfDayClosesAtItsOwnTimeInsteadOfTheUsualOne() {
        Holiday half = new Holiday("Eve", true, LocalTime.of(13, 0));

        assertThat(reason(OpeningHours.check(at("2026-09-20", "12:59"), officeWeek(), half, 0))).isEqualTo("open");
        assertThat(reason(OpeningHours.check(at("2026-09-20", "13:00"), officeWeek(), half, 0))).isEqualTo("outside_hours");
    }

    @Test
    void aHalfDayNeverExtendsTheDayBeyondTheUsualClosingTime() {
        Holiday late = new Holiday("Odd", true, LocalTime.of(20, 0));

        assertThat(reason(OpeningHours.check(at("2026-09-20", "17:30"), officeWeek(), late, 0))).isEqualTo("outside_hours");
    }

    @Test
    void aHalfDayOnASiteWithNoHoursStillClosesAtItsTime() {
        Holiday half = new Holiday("Eve", true, LocalTime.of(13, 0));

        assertThat(reason(OpeningHours.check(at("2026-09-19", "12:00"), Map.of(), half, 0))).isEqualTo("open");
        assertThat(reason(OpeningHours.check(at("2026-09-19", "14:00"), Map.of(), half, 0))).isEqualTo("outside_hours");
    }

    @Test
    void theCutOffStopsIssuingThatManyMinutesBeforeClosing() {
        assertThat(reason(OpeningHours.check(at("2026-09-20", "16:29"), officeWeek(), null, 30))).isEqualTo("open");

        Optional<Refusal> refusal = OpeningHours.check(at("2026-09-20", "16:30"), officeWeek(), null, 30);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().reason()).isEqualTo("past_cutoff");
        assertThat(refusal.get().details()).containsEntry("cutoff_at", "2026-09-20T16:30:00+06:00");
    }

    @Test
    void afterClosingItIsOutsideHoursNotPastTheCutOff() {
        assertThat(reason(OpeningHours.check(at("2026-09-20", "17:10"), officeWeek(), null, 30))).isEqualTo("outside_hours");
    }

    @Test
    void theCutOffFollowsAHalfDaysEarlierClosing() {
        Holiday half = new Holiday("Eve", true, LocalTime.of(13, 0));

        assertThat(reason(OpeningHours.check(at("2026-09-20", "12:15"), officeWeek(), half, 60))).isEqualTo("past_cutoff");
        assertThat(reason(OpeningHours.check(at("2026-09-20", "11:59"), officeWeek(), half, 60))).isEqualTo("open");
    }

    @Test
    void aCutOffLongerThanTheDayNeverLetsAnyoneIn() {
        Optional<Refusal> refusal = OpeningHours.check(at("2026-09-20", "09:00"), officeWeek(), null, 600);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().reason()).isEqualTo("past_cutoff");
        assertThat(refusal.get().details()).containsEntry("cutoff_at", "2026-09-20T09:00:00+06:00");
    }

    @Test
    void theDayIsTheDayAtTheSitesTimeZone() {
        // 22:30 UTC on Saturday is 04:30 on Sunday in Dhaka: before the office opens, and on a Sunday.
        ZonedDateTime local = java.time.Instant.parse("2026-09-19T22:30:00Z").atZone(DHAKA);

        assertThat(local.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(reason(OpeningHours.check(local, officeWeek(), null, 0))).isEqualTo("outside_hours");
    }
}
