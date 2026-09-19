package com.qms.issuance;

import com.qms.platform.Profiles;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Enforces FR-INT-012's hard timeout and FR-INT-013's "no queue operation blocks on a directory" rule across however
 * many {@link VisitorDirectory} beans are wired: one, {@link LocalVisitorDirectory}, in v1. A future remote adapter
 * would be wired ahead of it, since it is the one that can be slow; the local directory is always the last, fastest
 * fallback. Each directory gets its own bounded time budget (default 1.5 s, {@code qms.visitor.directory-timeout});
 * one that times out or throws is abandoned, never retried, and the next directory in the list is tried instead. If
 * every directory fails or times out, {@code lookup} answers empty rather than waiting any longer, and the caller
 * (Reception) is free to issue the ticket without visitor details.
 */
@Component
@Profile(Profiles.SERVING)
class VisitorDirectoryGateway {

    private final List<VisitorDirectory> directories;
    private final Duration timeout;
    private final ExecutorService executor;

    @Autowired
    VisitorDirectoryGateway(List<VisitorDirectory> directories, VisitorProperties properties) {
        this(directories, properties.directoryTimeout(), Executors.newVirtualThreadPerTaskExecutor());
    }

    VisitorDirectoryGateway(List<VisitorDirectory> directories, Duration timeout, ExecutorService executor) {
        this.directories = directories;
        this.timeout = timeout;
        this.executor = executor;
    }

    Optional<VisitorDirectory.Match> lookup(String query) {
        for (VisitorDirectory directory : directories) {
            Optional<VisitorDirectory.Match> match = tryWithTimeout(directory, query);
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    private Optional<VisitorDirectory.Match> tryWithTimeout(VisitorDirectory directory, String query) {
        Callable<Optional<VisitorDirectory.Match>> call = () -> directory.lookup(query);
        Future<Optional<VisitorDirectory.Match>> future = executor.submit(call);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Optional.empty();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            // A directory that throws is treated exactly like one that times out: skipped, not surfaced (FR-INT-013).
            return Optional.empty();
        }
    }
}
