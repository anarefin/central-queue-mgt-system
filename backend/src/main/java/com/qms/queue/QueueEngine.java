package com.qms.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place a queue's order is computed (SRS §10). It is a pure function of the waiting tickets, the strategy and
 * the time: it reads no database, holds no state and knows no transport, so a site edge node can embed it later
 * (ADR-0001). Every term is in minutes, so every waiting ticket's score grows at the same rate and a Priority class
 * takes effect on arrival (ADR-0003).
 *
 * <p>{@code weighted_wait} orders by score, highest first. {@code strict_priority} and {@code fifo} do not use the
 * score to order; the terms are still computed so the dry-run shows the same breakdown under every strategy.
 * Escalation (FR-QUE-022) is part of {@code weighted_wait} and {@code strict_priority}; {@code fifo} is creation order
 * and nothing else. Ties always break by earliest creation time, then by lowest id (FR-QUE-020).
 */
public final class QueueEngine {

    /**
     * Added to an escalated ticket so that it is served before every ticket that is not escalated, whatever the class
     * or the Score adjustment of the others: a score below this needs more than a year of waiting.
     */
    public static final double ESCALATION_TIER_MINUTES = 1_000_000;

    private static final double SECONDS_PER_MINUTE = 60.0;

    private QueueEngine() {}

    /**
     * A waiting ticket as the engine sees it. {@code createdAt} is when the ticket was issued; {@code waitingSince} is
     * when its real wait started (for a checked-in appointment the later of slot and check-in). {@code maxWaitMinutes}
     * is null for a class without a maximum wait.
     */
    public record Candidate(
            UUID id,
            Instant createdAt,
            Instant waitingSince,
            int headstartMinutes,
            Integer maxWaitMinutes,
            double appointmentBonusMinutes,
            int scoreAdjustmentMinutes) {}

    /**
     * The terms of one ticket's score. {@code scoreAdjustmentMinutes} is what was applied: an escalated ticket's
     * negative adjustment is overridden to 0 ({@code adjustmentOverridden}); its {@code escalationBonusMinutes} is the
     * escalation tier plus the minutes it has waited beyond its maximum.
     */
    public record Terms(
            double effectiveWaitMinutes,
            double headstartMinutes,
            double appointmentBonusMinutes,
            double escalationBonusMinutes,
            double scoreAdjustmentMinutes,
            boolean escalated,
            boolean adjustmentOverridden) {

        public double score() {
            return effectiveWaitMinutes + headstartMinutes + appointmentBonusMinutes + escalationBonusMinutes + scoreAdjustmentMinutes;
        }
    }

    /** A candidate with its terms and its 1-based place in the order. */
    public record Scored(Candidate ticket, Terms terms, int position) {}

    /** The terms of one ticket at {@code now}. */
    public static Terms terms(Candidate ticket, Instant now) {
        double wait = Math.max(0, Duration.between(ticket.waitingSince(), now).toMillis() / 1000.0 / SECONDS_PER_MINUTE);
        Integer max = ticket.maxWaitMinutes();
        boolean escalated = max != null && wait > max;
        double escalation = escalated ? ESCALATION_TIER_MINUTES + (wait - max) : 0;
        int adjustment = ticket.scoreAdjustmentMinutes();
        boolean overridden = escalated && adjustment < 0;
        return new Terms(wait, ticket.headstartMinutes(), ticket.appointmentBonusMinutes(), escalation, overridden ? 0 : adjustment, escalated, overridden);
    }

    /** The tickets in the order they will be called, each with the terms that put it there. */
    public static List<Scored> order(List<Candidate> candidates, QueueStrategy strategy, Instant now) {
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) scored.add(new Scored(candidate, terms(candidate, now), 0));
        scored.sort(comparator(strategy));
        List<Scored> ordered = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) ordered.add(new Scored(scored.get(i).ticket(), scored.get(i).terms(), i + 1));
        return ordered;
    }

    /**
     * The head of one Service's queue as a Counter sees it when it asks for its next ticket: the ticket, its score and
     * the preference weight of the Counter's link to that Service (1 is primary, a higher weight is a fallback,
     * FR-CFG-011).
     */
    public record Contender(UUID serviceId, int preferenceWeight, UUID ticketId, Instant queuedAt, double score) {}

    /**
     * FR-QUE-030: among the heads of every queue a Counter serves, the one with the highest score, except that a
     * lower-weight (primary) link wins over a fallback link whose head is not better by more than {@code
     * toleranceMinutes}. Among those within the tolerance of the best, the lowest weight wins, then the higher score, then
     * the earlier arrival, then the lower id, so the choice is deterministic.
     */
    public static Optional<Contender> pick(List<Contender> heads, double toleranceMinutes) {
        if (heads.isEmpty()) return Optional.empty();
        double best = heads.stream().mapToDouble(Contender::score).max().orElseThrow();
        Comparator<Contender> preference = Comparator.comparingInt(Contender::preferenceWeight)
                .thenComparing(Comparator.comparingDouble(Contender::score).reversed())
                .thenComparing(Contender::queuedAt)
                .thenComparing(Contender::ticketId);
        return heads.stream().filter(c -> c.score() >= best - toleranceMinutes).min(preference);
    }

    /**
     * The Score adjustment that puts a missed ticket where FR-QUE-051 says (ADR-0004). {@code self} is the terms of the
     * missed ticket with no adjustment, as if it were waiting now with its own class and its original wait;
     * {@code others} are the tickets already waiting, in the order they will be called. Whole minutes, because that is what
     * a ticket stores: the adjustment lands the ticket just ahead of the front, just behind the {@code after}-th ticket, or
     * just behind the last, and every waiting score grows at the same rate, so it keeps that place.
     *
     * <p>An escalated ticket stays ahead of every ticket that is not, whatever the adjustment (FR-QUE-022): the place is
     * worked out among the tickets that are not escalated, a missed ticket that is escalated itself needs no adjustment,
     * and "after N" that reaches into the escalated tickets means the front of the rest. Two tickets less than a minute of
     * score apart cannot be separated by a whole-minute adjustment; then the earlier ticket goes first (FR-QUE-020).
     */
    public static int reentryAdjustment(Terms self, List<Terms> others, ReentryPosition position, int after) {
        if (self.escalated()) return 0;
        List<Terms> ordinary = others.stream().filter(t -> !t.escalated()).toList();
        if (ordinary.isEmpty()) return 0;
        ReentryPosition where = position;
        if (where == ReentryPosition.AFTER_N) {
            if (others.size() <= after) where = ReentryPosition.BACK;
            else if (others.get(after - 1).escalated()) where = ReentryPosition.FRONT;
        }
        return switch (where) {
            case FRONT -> {
                double top = ordinary.stream().mapToDouble(Terms::score).max().orElseThrow();
                yield Math.max(0, (int) Math.floor(top - self.score()) + 1);
            }
            case BACK -> {
                double bottom = ordinary.stream().mapToDouble(Terms::score).min().orElseThrow();
                yield Math.min(0, (int) Math.ceil(bottom - self.score()) - 1);
            }
            case AFTER_N -> (int) Math.ceil(others.get(after - 1).score() - self.score()) - 1;
        };
    }

    private static Comparator<Scored> comparator(QueueStrategy strategy) {
        Comparator<Scored> creation = Comparator.<Scored, Instant>comparing(s -> s.ticket().createdAt()).thenComparing(s -> s.ticket().id());
        return switch (strategy) {
            case WEIGHTED_WAIT -> Comparator.<Scored>comparingDouble(s -> s.terms().score()).reversed().thenComparing(creation);
            case STRICT_PRIORITY -> Comparator.<Scored, Boolean>comparing(s -> !s.terms().escalated())
                    .thenComparing(Comparator.<Scored>comparingInt(s -> s.ticket().headstartMinutes()).reversed())
                    .thenComparing(creation);
            case FIFO -> creation;
        };
    }
}
