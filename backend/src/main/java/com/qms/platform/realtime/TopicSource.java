package com.qms.platform.realtime;

import java.util.Map;

/**
 * What a bounded context contributes for the topics it owns: who may subscribe (FR-QUE-080) and what the subscriber is
 * shown first (§21.1). Both run with the subscriber's authentication as the current security context, so the checks a REST
 * handler uses ({@code CurrentUser}, {@code ScopeGuard}, the granted authorities) work here unchanged.
 */
public interface TopicSource {

    /** Whether this source owns the topic name. */
    boolean handles(String topic);

    /**
     * Refuses the subscriber, or returns. Throws {@code ApiException} with {@code forbidden}, {@code not_found} or
     * {@code validation_failed}, or Spring Security's {@code AccessDeniedException}.
     */
    void authorize(String topic);

    /** The state of the topic now: what a subscriber, or a client that is polling, applies before any delta. */
    Map<String, Object> snapshot(String topic);
}
