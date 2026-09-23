package com.qms.platform.security;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * A minimal in-memory fixed-window rate limiter for a {@code @PublicEndpoint} read: nothing identifies the caller
 * (no bearer token, no ticket secret) to key a database-backed limit off, unlike issuance's or visitor auth's own
 * per-visitor limits. State is per-JVM and does not survive a restart, which is enough for a low-stakes,
 * non-sensitive read (the public branding theme, ticket 62) rather than something security-critical.
 */
@Component
public class PublicReadRateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    public PublicReadRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** True if {@code key} (typically a bucket name plus the caller's address) may proceed; false once it has made {@code limit} calls within the last {@code windowSeconds}. */
    public boolean allow(String key, int limit, long windowSeconds) {
        Instant now = clock.instant();
        windows.computeIfAbsent(key, k -> new AtomicReference<>(new Window(now, 0)));
        AtomicReference<Window> ref = windows.get(key);
        while (true) {
            Window current = ref.get();
            Window next;
            boolean allowed;
            if (now.isAfter(current.windowStart().plusSeconds(windowSeconds))) {
                next = new Window(now, 1);
                allowed = true;
            } else if (current.count() >= limit) {
                next = current;
                allowed = false;
            } else {
                next = new Window(current.windowStart(), current.count() + 1);
                allowed = true;
            }
            if (ref.compareAndSet(current, next)) return allowed;
        }
    }

    private record Window(Instant windowStart, int count) {}
}
