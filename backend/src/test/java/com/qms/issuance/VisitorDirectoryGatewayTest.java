package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * FR-INT-012 (hard timeout, fallback to the next directory) and FR-INT-013 (no caller ever waits past it). No Spring
 * context and no Docker: {@link VisitorDirectory} is faked directly.
 */
class VisitorDirectoryGatewayTest {

    private static final VisitorDirectory.Match MATCH = new VisitorDirectory.Match(UUID.randomUUID(), "V-1", "Amina", "general", "01700000000", Map.of());

    /** A directory that never returns within the test's lifetime, to prove the gateway does not wait for it. */
    private static VisitorDirectory foreverSlow(CountDownLatch started) {
        return query -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(MATCH);
        };
    }

    @Test
    void aDirectoryThatNeverAnswersIsAbandonedAtTheTimeoutAndTheCallerGetsAnEmptyResultPromptly() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        VisitorDirectoryGateway gateway =
                new VisitorDirectoryGateway(List.of(foreverSlow(started)), Duration.ofMillis(50), Executors.newVirtualThreadPerTaskExecutor());

        long before = System.nanoTime();
        Optional<VisitorDirectory.Match> result = gateway.lookup("01700000000");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - before).toMillis();

        assertThat(started.await(1, TimeUnit.SECONDS)).as("the slow directory was actually called").isTrue();
        assertThat(result).isEmpty();
        // Nowhere near the directory's 30 s answer: the timeout, not the directory, decided when this returned.
        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void onceASlowDirectoryTimesOutTheGatewayFallsThroughToTheNextDirectoryInsteadOfGivingUp() {
        CountDownLatch started = new CountDownLatch(1);
        VisitorDirectory remote = foreverSlow(started);
        VisitorDirectory local = query -> Optional.of(MATCH);
        VisitorDirectoryGateway gateway = new VisitorDirectoryGateway(List.of(remote, local), Duration.ofMillis(50), Executors.newVirtualThreadPerTaskExecutor());

        Optional<VisitorDirectory.Match> result = gateway.lookup("V-1");

        assertThat(result).contains(MATCH);
    }

    @Test
    void aDirectoryThatThrowsIsSkippedJustLikeOneThatTimesOut() {
        VisitorDirectory broken = query -> {
            throw new IllegalStateException("directory unreachable");
        };
        VisitorDirectory local = query -> Optional.of(MATCH);
        VisitorDirectoryGateway gateway = new VisitorDirectoryGateway(List.of(broken, local), Duration.ofSeconds(2), Executors.newVirtualThreadPerTaskExecutor());

        assertThat(gateway.lookup("V-1")).contains(MATCH);
    }

    @Test
    void aFastDirectoryAnswersWellWithinTheTimeoutAndNoFallbackIsNeeded() {
        VisitorDirectory fast = query -> Optional.of(MATCH);
        VisitorDirectoryGateway gateway = new VisitorDirectoryGateway(List.of(fast), Duration.ofMillis(1500), Executors.newVirtualThreadPerTaskExecutor());

        assertThat(gateway.lookup("V-1")).contains(MATCH);
    }

    @Test
    void noDirectoryMatchingIsAnEmptyResultNotAnError() {
        VisitorDirectory local = query -> Optional.empty();
        VisitorDirectoryGateway gateway = new VisitorDirectoryGateway(List.of(local), Duration.ofSeconds(1), Executors.newVirtualThreadPerTaskExecutor());

        assertThat(gateway.lookup("unknown")).isEmpty();
    }
}
