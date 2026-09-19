package com.qms.queue;

import java.util.List;

/**
 * The wait estimate of SRS §10.5, as arithmetic only: no clock, no database.
 *
 * <p>{@code estimate = (tickets_ahead / max(open_counters, 1)) * rolling_average_handling_time} (FR-QUE-040). The rolling
 * average is taken over the trailing 20 completed tickets of the service and falls back to the service's expected handling
 * time while fewer than 5 samples exist (FR-QUE-041). The result is shown as a range of {@link #BUCKET_MINUTES} minutes that
 * holds the figure, so it is never read as a promise (FR-QUE-042, FR-ISS-005).
 */
public final class WaitEstimator {

    /** How many of the latest completed tickets make up the rolling average. */
    public static final int WINDOW = 20;
    /** Fewer samples than this and the expected handling time is used instead. */
    public static final int MIN_SAMPLES = 5;
    /** The width, and the step, of the range shown. */
    public static final int BUCKET_MINUTES = 5;

    private WaitEstimator() {}

    /**
     * @param recentServiceSeconds service time of completed tickets, newest first; only the first {@link #WINDOW} count
     * @param expectedMinutes the service's configured expected handling time
     * @return minutes
     */
    public static double handlingMinutes(List<Integer> recentServiceSeconds, int expectedMinutes) {
        List<Integer> sample = recentServiceSeconds.size() > WINDOW ? recentServiceSeconds.subList(0, WINDOW) : recentServiceSeconds;
        if (sample.size() < MIN_SAMPLES) return expectedMinutes;
        return sample.stream().mapToInt(Integer::intValue).average().orElse(expectedMinutes * 60.0) / 60.0;
    }

    /** The exact figure in minutes, before it is rounded into a range. */
    public static double minutes(int ticketsAhead, int openCounters, double handlingMinutes) {
        return Math.max(0, ticketsAhead) / (double) Math.max(openCounters, 1) * handlingMinutes;
    }

    /** The range: the multiple of {@link #BUCKET_MINUTES} at or below the figure, and the next one up. */
    public static WaitEstimate estimate(int ticketsAhead, int openCounters, double handlingMinutes) {
        int low = (int) Math.floor(minutes(ticketsAhead, openCounters, handlingMinutes) / BUCKET_MINUTES) * BUCKET_MINUTES;
        return new WaitEstimate(low, low + BUCKET_MINUTES);
    }
}
