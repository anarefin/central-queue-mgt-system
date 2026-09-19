package com.qms.identity;

import java.util.UUID;

/**
 * Port for the internal {@code principal.changed(sub)} event (ADR-0009). Disabling a user or changing their roles or
 * scopes raises it so the realtime hub can drop that subject's sockets at once. The hub arrives with ticket 12; until
 * then the only implementation logs.
 */
public interface PrincipalChangedPublisher {

    void principalChanged(UUID userId);
}
