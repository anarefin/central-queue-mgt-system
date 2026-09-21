package com.qms.reporting;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * FR-RPT-005's three cadences: daily, weekly and monthly. Both "how far back does this delivery's own report
 * window reach" and "when does this schedule next fire" come from the same cadence, always computed in UTC — SRS
 * §16 gives no per-schedule time zone or time-of-day, only the cadence itself, so a schedule simply fires one
 * cadence period after it last did (or after it was created, for its first run).
 */
enum ReportScheduleCadence {
    DAILY("daily") {
        @Override
        Instant windowStart(Instant to) {
            return to.minus(Duration.ofDays(1));
        }

        @Override
        Instant next(Instant from) {
            return from.plus(Duration.ofDays(1));
        }
    },
    WEEKLY("weekly") {
        @Override
        Instant windowStart(Instant to) {
            return to.minus(Duration.ofDays(7));
        }

        @Override
        Instant next(Instant from) {
            return from.plus(Duration.ofDays(7));
        }
    },
    MONTHLY("monthly") {
        @Override
        Instant windowStart(Instant to) {
            return ZonedDateTime.ofInstant(to, ZoneOffset.UTC).minusMonths(1).toInstant();
        }

        @Override
        Instant next(Instant from) {
            return ZonedDateTime.ofInstant(from, ZoneOffset.UTC).plusMonths(1).toInstant();
        }
    };

    private final String wire;

    ReportScheduleCadence(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    /** The report window this cadence covers, ending at {@code to} (the tick's own fire time). */
    abstract Instant windowStart(Instant to);

    /** The next time this cadence fires, one period after {@code from}. */
    abstract Instant next(Instant from);

    static Optional<ReportScheduleCadence> fromWire(String wire) {
        return Arrays.stream(values()).filter(c -> c.wire.equals(wire)).findFirst();
    }
}
