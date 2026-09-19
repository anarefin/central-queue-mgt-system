package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.NumberingRepository.ActiveService;
import com.qms.issuance.TokenNumbering.Period;
import com.qms.issuance.TokenNumbering.ResetBoundary;
import com.qms.platform.Profiles;
import com.qms.platform.jobs.JobLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled sequence resets (FR-CFG-019, ADR-0010). At a numbering scope's site-local reset time a new reset period
 * begins and its sequence starts over at the rule's start value; the earlier periods' sequences, tickets and reports
 * are untouched.
 *
 * <p>Correctness does not depend on this job: the period a ticket belongs to is computed from the clock, so the first
 * ticket after a reset time opens the new period itself even if the backend was down at that moment. The job is what
 * opens the period at the reset time, records it in the ledger and audits it. A period is recorded once, so running
 * the job twice, on two nodes, or hours late (a replay of a missed reset) never restarts a sequence twice.
 */
@Service
@Profile(Profiles.SERVING)
public class NumberingResets {

    static final String LOCK = "numbering-reset";
    static final String ISSUANCE = "issuance";
    static final String SCHEDULED = "scheduled";
    static final String REPLAYED = "replayed";
    /** A reset opened within this long of its reset time is on schedule; later than that it is a replay. */
    static final Duration ON_SCHEDULE = Duration.ofMinutes(5);
    static final String RESET_ACTION = "numbering.reset";

    private static final Logger LOG = LoggerFactory.getLogger(NumberingResets.class);

    private final NumberingRepository repository;
    private final SequenceBlocks sequences;
    private final AuditWriter audit;
    private final JobLock lock;
    private final Clock clock;

    NumberingResets(NumberingRepository repository, SequenceBlocks sequences, AuditWriter audit, JobLock lock, Clock clock) {
        this.repository = repository;
        this.sequences = sequences;
        this.audit = audit;
        this.lock = lock;
        this.clock = clock;
    }

    /**
     * Opens the reset period of a numbering scope if it is not open yet: records it in the ledger, starts its first
     * sequence block and writes an audit entry. Returns whether this call opened it. Joins the caller's transaction.
     *
     * @param origin {@link #ISSUANCE} when the first ticket of the period opens it; anything else is the scheduled job,
     *     whose openings are classed {@link #SCHEDULED} or {@link #REPLAYED} by how late they are
     */
    boolean open(UUID siteId, String prefix, Period period, NumberingSpec spec, String origin, Instant now) {
        String scope = SequenceBlocks.scope(siteId, prefix);
        if (repository.resetRecorded(scope, period.key())) return false;
        String triggeredBy = ISSUANCE.equals(origin) ? ISSUANCE : classify(scope, period, now);
        UUID id = UUID.randomUUID();
        if (!repository.recordReset(id, siteId, scope, period.key(), period.start(), triggeredBy, now)) return false;
        sequences.open(siteId, prefix, period.key(), firstValue(scope, period, spec));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("site_id", siteId.toString());
        after.put("prefix", prefix);
        after.put("reset_key", period.key());
        after.put("period_start", period.start().toString());
        after.put("triggered_by", triggeredBy);
        audit.record(AuditEvent.of(RESET_ACTION, "numbering_reset", id).withAfter(after));
        return true;
    }

    /**
     * Where a period's first block begins: the rule's start value, or after any number already reserved for the scope
     * inside the period under a different reset key (a rule that changed part-way through it).
     */
    long firstValue(String scope, Period period, NumberingSpec spec) {
        return repository.highestReservedSince(scope, period.key(), period.start()).map(highest -> Math.max(spec.start(), highest + 1)).orElse(spec.start());
    }

    private String classify(String scope, Period period, Instant now) {
        boolean late = Duration.between(period.start(), now).compareTo(ON_SCHEDULE) > 0;
        return late && repository.hasEarlierReset(scope, period.start()) ? REPLAYED : SCHEDULED;
    }

    /**
     * The job body: opens the current period of every numbering scope that resets and has not been opened yet, at the
     * site-local reset time of each. A reset that was missed because nothing was running is replayed the next time
     * this runs. Returns how many periods it opened. Joins the caller's transaction.
     */
    @Transactional
    public int runDue(Instant now) {
        Map<String, NumberingSpec> rules = repository.allRules();
        Set<String> seen = new HashSet<>();
        int opened = 0;
        for (ActiveService service : repository.activeServices()) {
            NumberingSpec spec = rules.getOrDefault("service/" + service.serviceId(), rules.getOrDefault("service_group/" + service.groupId(), NumberingSpec.DEFAULT));
            if (spec.boundary() == ResetBoundary.NEVER) continue;
            String prefix = spec.prefix(service.servicePrefix(), service.groupPrefix());
            Period period = TokenNumbering.period(now, ZoneId.of(service.timezone()), spec.boundary(), spec.resetTime());
            if (!seen.add(service.siteId() + "/" + prefix + "|" + period.key())) continue;
            if (open(service.siteId(), prefix, period, spec, SCHEDULED, now)) opened++;
        }
        return opened;
    }

    /**
     * Runs {@link #runDue} on this node unless another node already holds the cluster-wide lock. Returns empty when the
     * lock was taken, otherwise how many periods this run opened.
     */
    public Optional<Integer> runScheduled() {
        int[] opened = new int[1];
        boolean ran = lock.runExclusively(LOCK, () -> opened[0] = runDue(clock.instant()));
        if (ran && opened[0] > 0) LOG.info("Numbering reset opened {} period(s)", opened[0]);
        return ran ? Optional.of(opened[0]) : Optional.empty();
    }
}
