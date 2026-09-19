package com.qms.issuance;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;

/**
 * The numbering rule (SRS §4.4, FR-CFG-018): {@code {prefix}{separator}{sequence, zero-padded}}, with a sequence that
 * restarts at a reset boundary. The built-in default, used where no rule is configured, is prefix from the service,
 * separator {@code -}, padding 3, reset daily at 00:00 site-local time. Digits are always Western Arabic
 * (FR-I18N-020), whatever the language, so this uses plain formatting and never a locale.
 *
 * <p>The reset period a ticket belongs to is a pure function of the clock, the site's time zone and the rule. A
 * sequence therefore restarts at the reset time whether or not anything was running at that moment; the scheduled
 * reset (FR-CFG-019) only records and pre-opens the period.
 */
public final class TokenNumbering {

    public static final String SEPARATOR = "-";
    public static final int PADDING = 3;

    private TokenNumbering() {}

    /** When a sequence starts over (FR-CFG-018). Weeks begin on Monday, months on the first. */
    public enum ResetBoundary {
        DAILY("daily"),
        WEEKLY("weekly"),
        MONTHLY("monthly"),
        NEVER("never");

        private final String wire;

        ResetBoundary(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static ResetBoundary fromWire(String wire) {
            return Arrays.stream(values()).filter(b -> b.wire.equals(wire)).findFirst().orElse(null);
        }
    }

    /**
     * One reset period: the {@code key} that names it, the instant it {@code start}s and the instant it ends ({@code
     * null} when it never does). The key is the period's start date for a daily reset, the ISO week for a weekly one,
     * the month for a monthly one and {@code never} otherwise, so keys of different boundaries cannot be mistaken.
     */
    public record Period(String key, Instant start, Instant end) {}

    /** The period an instant belongs to under a rule's boundary and reset time, in the site's zone. */
    public static Period period(Instant at, ZoneId zone, ResetBoundary boundary, LocalTime resetTime) {
        LocalDate today = at.atZone(zone).toLocalDate();
        return switch (boundary) {
            case NEVER -> new Period("never", Instant.EPOCH, null);
            case DAILY -> {
                LocalDate start = latest(today, at, zone, resetTime, ChronoUnit.DAYS);
                yield new Period(start.toString(), instant(start, zone, resetTime), instant(start.plusDays(1), zone, resetTime));
            }
            case WEEKLY -> {
                LocalDate start = latest(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), at, zone, resetTime, ChronoUnit.WEEKS);
                String key = start.get(IsoFields.WEEK_BASED_YEAR) + "-W" + twoDigits(start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                yield new Period(key, instant(start, zone, resetTime), instant(start.plusWeeks(1), zone, resetTime));
            }
            case MONTHLY -> {
                LocalDate start = latest(today.withDayOfMonth(1), at, zone, resetTime, ChronoUnit.MONTHS);
                String key = start.getYear() + "-" + twoDigits(start.getMonthValue());
                yield new Period(key, instant(start, zone, resetTime), instant(start.plusMonths(1), zone, resetTime));
            }
        };
    }

    /** The candidate boundary date, or the one before it when this one's reset time is still ahead. */
    private static LocalDate latest(LocalDate candidate, Instant at, ZoneId zone, LocalTime resetTime, ChronoUnit unit) {
        return instant(candidate, zone, resetTime).isAfter(at) ? candidate.minus(1, unit) : candidate;
    }

    private static String twoDigits(int value) {
        return (value < 10 ? "0" : "") + value;
    }

    private static Instant instant(LocalDate date, ZoneId zone, LocalTime time) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }

    /** The default rule's reset key: the site-local calendar day, for example {@code 2026-09-19}. */
    public static String resetKey(Instant at, ZoneId siteZone) {
        return period(at, siteZone, ResetBoundary.DAILY, LocalTime.MIDNIGHT).key();
    }

    public static String format(String prefix, long sequence) {
        return format(prefix, SEPARATOR, PADDING, sequence);
    }

    /** A longer sequence is never truncated: padding is a minimum width. */
    public static String format(String prefix, String separator, int padding, long sequence) {
        String digits = Long.toString(sequence);
        return prefix + separator + "0".repeat(Math.max(0, padding - digits.length())) + digits;
    }
}
