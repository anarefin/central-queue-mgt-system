package com.qms.appointment;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

/**
 * Whether, and within what window, a Site's or Service's business hours and holiday calendar let appointment slots
 * exist on a given weekday (FR-APT-004). Pure: everything it needs is passed in, so it is testable without a
 * database. Reads the same {@code business_hours} and {@code holiday} tables as issuance (ticket 21), independently:
 * an appointment exception can override what this returns, which issuance's own refusal never needs to do.
 */
final class BusinessWindow {

    /** One day's opening hours. */
    record Day(LocalTime open, LocalTime close) {}

    /** A holiday on the day in question; {@code closeTime} is set for a half-day. */
    record Holiday(boolean halfDay, LocalTime closeTime) {}

    /** The window within which slots may fall; clipped by a half-day holiday's earlier close. */
    record Window(LocalTime open, LocalTime close) {}

    private BusinessWindow() {}

    /**
     * @param weekday the date's day of week
     * @param week the hours in force, by weekday; empty means no hours are configured, so the day is unrestricted
     * @param holiday the holiday on that date, or null
     * @return empty when the day is fully suppressed (a full-day holiday, or a weekday missing from a non-empty week); otherwise the window slots must fall within
     */
    static Optional<Window> forDate(DayOfWeek weekday, Map<DayOfWeek, Day> week, Holiday holiday) {
        if (holiday != null && !holiday.halfDay()) return Optional.empty();

        LocalTime open = LocalTime.MIN;
        LocalTime close = LocalTime.MAX;
        if (!week.isEmpty()) {
            Day today = week.get(weekday);
            if (today == null) return Optional.empty();
            open = today.open();
            close = today.close();
        }
        if (holiday != null && holiday.closeTime() != null && holiday.closeTime().isBefore(close)) {
            close = holiday.closeTime();
        }
        return Optional.of(new Window(open, close));
    }
}
