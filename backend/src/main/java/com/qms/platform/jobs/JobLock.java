package com.qms.platform.jobs;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a scheduled job on one node of the cluster at a time (ADR-0010). The lock is a PostgreSQL advisory lock scoped
 * to the transaction the job runs in, so it needs no table or extension, is released by commit, rollback or a crashed
 * node's dropped connection, and never leaves a stale lock behind. A node that finds the lock taken skips the run: the
 * node holding it is doing the work, and jobs are written to be safe to run again.
 */
@Component
@Profile(Profiles.SERVING)
public class JobLock {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JobLock(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Runs {@code task} in one transaction if no other node holds {@code name}; returns whether it ran. */
    public boolean runExclusively(String name, Runnable task) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(('x' || substr(md5(?), 1, 16))::bit(64)::bigint)", Boolean.class, name);
            if (!Boolean.TRUE.equals(acquired)) return false;
            task.run();
            return true;
        }));
    }
}
