package com.qms.issuance;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a Site's or Service's hours, its holiday calendar and a channel's cut-off let a ticket be issued at a moment
 * (FR-CFG-020..022, FR-ISS-003). Pure: everything it needs is passed in, so it can be tested without a database.
 *
 * <p>An empty week means no hours are configured, and the Service takes tickets at any time. A weekday missing from a
 * non-empty week is a closed day. A holiday closes the Site for the day; a half-day closes it at the holiday's time
 * instead of the usual closing time.
 */
final class OpeningHours {

    /** One day's opening hours. */
    record Day(LocalTime open, LocalTime close) {}

    /** A holiday on the day being checked; {@code closeTime} is set for a half-day. */
    record Holiday(String name, boolean halfDay, LocalTime closeTime) {}

    /** Why issuance is refused, with the reason code and the details the API returns for it. */
    record Refusal(String reason, Map<String, Object> details) {}

    static final String OUTSIDE_HOURS = "outside_hours";
    static final String HOLIDAY = "holiday";
    static final String PAST_CUTOFF = "past_cutoff";

    private static final DateTimeFormatter OFFSET_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private OpeningHours() {}

    /**
     * @param local the moment, in the Site's time zone
     * @param week the hours in force, by weekday
     * @param holiday the holiday on that date, or null
     * @param cutoffMinutes minutes before closing at which the channel stops issuing; 0 for none
     */
    static Optional<Refusal> check(ZonedDateTime local, Map<DayOfWeek, Day> week, Holiday holiday, int cutoffMinutes) {
        if (holiday != null && !holiday.halfDay()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", HOLIDAY);
            details.put("holiday", holiday.name());
            return Optional.of(new Refusal(HOLIDAY, details));
        }

        LocalTime open = LocalTime.MIN;
        LocalTime close = null;
        if (!week.isEmpty()) {
            Day today = week.get(local.getDayOfWeek());
            if (today == null) return Optional.of(outside());
            open = today.open();
            close = today.close();
        }
        if (holiday != null && holiday.closeTime() != null && (close == null || holiday.closeTime().isBefore(close))) {
            close = holiday.closeTime();
        }
        if (close == null) return Optional.empty();

        LocalTime now = local.toLocalTime();
        if (now.isBefore(open) || !now.isBefore(close)) return Optional.of(outside());

        int cutoffAt = close.getHour() * 60 + close.getMinute() - cutoffMinutes;
        if (cutoffMinutes > 0 && now.getHour() * 60 + now.getMinute() >= cutoffAt) {
            // Before opening the cut-off would fall on the previous day, so the reported instant never precedes the opening time.
            LocalTime cutoff = cutoffAt <= open.getHour() * 60 + open.getMinute() ? open : LocalTime.of(cutoffAt / 60, cutoffAt % 60);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", PAST_CUTOFF);
            details.put("cutoff_at", OFFSET_TIME.format(local.with(cutoff).withSecond(0).withNano(0)));
            return Optional.of(new Refusal(PAST_CUTOFF, details));
        }
        return Optional.empty();
    }

    private static Refusal outside() {
        return new Refusal(OUTSIDE_HOURS, Map.of("reason", OUTSIDE_HOURS));
    }
}
