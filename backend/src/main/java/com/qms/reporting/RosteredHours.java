package com.qms.reporting;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * The denominator of the agent KPI "login adherence" (§15.2: "Session open time ÷ rostered time"). Staff rostering
 * and shift planning is explicitly out of scope for v1 (§28.3), so there is no rostered-hours record to divide by;
 * a Site's configured {@code business_hours} (FR-CFG-020, the hours it takes tickets) is the closest thing this
 * system models to "when an agent was expected to be at a counter", and is the proxy used here, documented rather
 * than silently assumed. A Site with no {@code business_hours} rows at all takes tickets at any time (the same
 * "unrestricted" reading {@code IssuanceRulesRepository} gives that emptiness), so every day counts as a full
 * 24 hours; a Site that has rows but none for a given weekday is closed that day, contributing zero. Pure and
 * timezone-free (UTC calendar days, like every other instant this system stores, §18.1): a real per-Site timezone
 * would shift day boundaries by at most a few hours, not change the shape of this KPI.
 */
final class RosteredHours {

    private static final long SECONDS_PER_DAY = 24 * 3600L;

    private RosteredHours() {}

    /**
     * @param weeklyOpenSeconds ISO weekday (1 Monday .. 7 Sunday) to that day's open seconds; a weekday absent from
     *     the map is closed. Empty means the Site has no configured hours at all (unrestricted, always open).
     */
    static long seconds(Map<Integer, Long> weeklyOpenSeconds, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) return 0;
        LocalDate start = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate endExclusive = LocalDate.ofInstant(to, ZoneOffset.UTC);
        // A `to` that falls mid-day still counts that partial day as a whole calendar day of roster: this is a
        // proxy denominator, not a per-second reconciliation, so the same coarse-grain rounding applies to both ends.
        if (to.isAfter(endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())) endExclusive = endExclusive.plusDays(1);

        boolean unrestricted = weeklyOpenSeconds.isEmpty();
        long total = 0;
        for (LocalDate day = start; day.isBefore(endExclusive); day = day.plusDays(1)) {
            if (unrestricted) {
                total += SECONDS_PER_DAY;
                continue;
            }
            DayOfWeek iso = day.getDayOfWeek();
            total += weeklyOpenSeconds.getOrDefault(iso.getValue(), 0L);
        }
        return total;
    }
}
