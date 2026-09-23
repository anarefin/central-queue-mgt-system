package com.qms.issuance.setup;

import com.qms.platform.featureflags.FeatureFlagKey;
import com.qms.platform.featureflags.FeatureFlags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The one implementation of {@link FeatureFlags} (ticket 68), backed by the {@code feature_flag} table {@link
 * FeatureFlagRepository} reads and {@link FeatureFlagController} writes. Reads are cached for a short TTL, since a
 * gate check happens on every request a flag protects and the flag table rarely changes; {@link #invalidate()},
 * called by {@link FeatureFlagController#update} right after a write commits, makes a change visible to the very
 * next request rather than waiting out the TTL. A key with no row yet (no vertical profile applied, ticket 56/67)
 * reads as enabled — the six features this build shipped with before this ticket existed were never gated at all,
 * so an installation that has not yet touched setup keeps working exactly as before.
 */
@Component
class FeatureFlagsService implements FeatureFlags {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final FeatureFlagRepository repository;
    private final Clock clock;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    FeatureFlagsService(FeatureFlagRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public boolean isEnabled(FeatureFlagKey key) {
        return snapshot().getOrDefault(key.wire(), true);
    }

    @Override
    public Map<String, Boolean> all() {
        return snapshot();
    }

    /** Called by {@link FeatureFlagController#update} right after a write commits, so the next read is never stale. */
    void invalidate() {
        cache.set(null);
    }

    private Map<String, Boolean> snapshot() {
        Instant now = clock.instant();
        CacheEntry entry = cache.get();
        if (entry != null && entry.expiresAt().isAfter(now)) return entry.flags();
        Map<String, Boolean> fresh = repository.all();
        cache.set(new CacheEntry(fresh, now.plus(TTL)));
        return fresh;
    }

    private record CacheEntry(Map<String, Boolean> flags, Instant expiresAt) {}
}
