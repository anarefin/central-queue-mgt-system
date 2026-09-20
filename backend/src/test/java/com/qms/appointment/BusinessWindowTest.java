package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.appointment.BusinessWindow.Day;
import com.qms.appointment.BusinessWindow.Holiday;
import com.qms.appointment.BusinessWindow.Window;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Business hours and the holiday calendar suppressing appointment slots (FR-APT-004). */
class BusinessWindowTest {

    private static Map<DayOfWeek, Day> officeWeek() {
        Map<DayOfWeek, Day> week = new EnumMap<>(DayOfWeek.class);
        week.put(DayOfWeek.MONDAY, new Day(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        return week;
    }

    @Test
    void noHoursConfiguredAtAllIsUnrestricted() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.SATURDAY, Map.of(), null);

        assertThat(window).contains(new Window(LocalTime.MIN, LocalTime.MAX));
    }

    @Test
    void aConfiguredWeekdayGivesItsOwnOpenAndCloseTimes() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.MONDAY, officeWeek(), null);

        assertThat(window).contains(new Window(LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }

    @Test
    void aWeekdayMissingFromAConfiguredWeekIsClosed() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.TUESDAY, officeWeek(), null);

        assertThat(window).isEmpty();
    }

    @Test
    void aFullDayHolidayClosesEvenAConfiguredWeekday() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.MONDAY, officeWeek(), new Holiday(false, null));

        assertThat(window).isEmpty();
    }

    @Test
    void aHalfDayHolidayClipsTheCloseTimeButDoesNotCloseTheDay() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.MONDAY, officeWeek(), new Holiday(true, LocalTime.of(12, 0)));

        assertThat(window).contains(new Window(LocalTime.of(9, 0), LocalTime.of(12, 0)));
    }

    @Test
    void aHalfDayLaterThanTheUsualCloseDoesNotExtendTheDay() {
        Optional<Window> window = BusinessWindow.forDate(DayOfWeek.MONDAY, officeWeek(), new Holiday(true, LocalTime.of(20, 0)));

        assertThat(window).contains(new Window(LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }
}
