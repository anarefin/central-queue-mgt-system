package com.qms.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.support.PostgresContainerConfig;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ticket 59, ADR-0010: a scheduled job runs at most once cluster-wide. {@link JobLock} takes a PostgreSQL advisory
 * lock scoped to its own transaction — its own database session — so two nodes are, for this purpose, exactly two
 * independent sessions against the same database: here, two {@link JobLock} instances, each its own unpooled
 * connection (see {@link DriverManagerDataSource}, never the same physical connection), against the one real
 * PostgreSQL both nodes of a cluster would share. No schema is needed: {@code pg_try_advisory_xact_lock} is a
 * built-in function, not a table this ticket's migrations would have to add.
 */
@Testcontainers
class JobLockIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    private static final String LOCK = "ticket-59-two-node-lock";

    @Test
    void onlyOneOfTwoNodesRunsTheSameLockAtOnceAndItIsFreeAgainOnceThatNodeIsDone() throws Exception {
        JobLock nodeA = node();
        JobLock nodeB = node();

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> onNodeA = () -> nodeA.runExclusively(LOCK, () -> {
                holding.countDown();
                await(release);
            });
            Future<Boolean> ranOnA = pool.submit(onNodeA);
            assertThat(holding.await(5, TimeUnit.SECONDS)).as("node A is inside its transaction, holding the lock").isTrue();

            assertThat(nodeB.runExclusively(LOCK, () -> {
                throw new AssertionError("node B must never run while node A holds the cluster-wide lock");
            })).as("node B sees the lock already taken and skips the run").isFalse();

            release.countDown();
            assertThat(ranOnA.get(5, TimeUnit.SECONDS)).as("node A's run completed").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Node A's transaction committed, releasing the lock: whichever node asks next may run.
        boolean[] ranOnB = new boolean[1];
        assertThat(nodeB.runExclusively(LOCK, () -> ranOnB[0] = true)).as("free again").isTrue();
        assertThat(ranOnB[0]).isTrue();
    }

    private static JobLock node() {
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JobLock(new JdbcTemplate(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("released in time").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
