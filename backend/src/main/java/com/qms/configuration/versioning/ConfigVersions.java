package com.qms.configuration.versioning;

import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one way a config area (Priority classes and routing strategy, numbering rules, business hours) records and
 * reads its own history (FR-CFG-040). A write only ever appends here after the area's own service has already applied
 * and validated the change and written its audit entry, the same "record what actually happened" order every service
 * already uses for {@code AuditWriter}.
 */
@Component
public class ConfigVersions {

    private final ConfigVersionRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    ConfigVersions(ConfigVersionRepository repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Appends the state a scope is in right after a change; the author is the caller's token, or none for a system change. */
    public void record(String entity, UUID entityId, java.util.Map<String, Object> payload) {
        UUID actor = currentUser.get().map(u -> u.userId()).orElse(null);
        repository.insert(new ConfigVersion(UUID.randomUUID(), entity, entityId, payload, actor, clock.instant()));
    }

    /** Newest first. */
    public List<ConfigVersionView> history(String entity, UUID entityId) {
        return repository.history(entity, entityId).stream().map(ConfigVersionView::of).toList();
    }

    /** The one version a revert applies; empty when {@code id} does not belong to this {@code entity} at all. */
    public Optional<ConfigVersion> find(String entity, UUID id) {
        return repository.get(entity, id);
    }
}
