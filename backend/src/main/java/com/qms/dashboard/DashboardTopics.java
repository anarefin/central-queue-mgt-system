package com.qms.dashboard;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.Permission;
import com.qms.platform.security.ScopeGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@code site:{id}:dashboard} (ticket 46, §21.2, FR-MON-001): who may watch it is exactly who may already watch that
 * Site's staff alerts ({@link com.qms.notification.NotificationTopics}, the same {@code dashboard:view_*} reach) —
 * this class mirrors that one's own authorisation shape rather than delegate to it, the same package-own-its-topics
 * precedent {@code com.qms.session.ConsoleTopics} sets. What a subscriber is shown, first and on every delta, is a
 * refresh signal only: the tile data itself always comes back over the subscriber's own authenticated {@code GET
 * /dashboard/live}, so a caller whose reach is only their own groups (FR-CFG-105) is never handed a broadcast
 * payload scoped wider than that.
 */
@Component
class DashboardTopics implements TopicSource {

    private static final List<Permission> VIEWERS = List.of(Permission.DASHBOARD_VIEW_ALL, Permission.DASHBOARD_VIEW_OWN_GROUPS);

    private final DashboardReadService reads;
    private final ScopeGuard scope;

    DashboardTopics(DashboardReadService reads, ScopeGuard scope) {
        this.reads = reads;
        this.scope = scope;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.SITE_PREFIX) && topic.endsWith(Topics.DASHBOARD_SUFFIX);
    }

    @Override
    public void authorize(String topic) {
        UUID siteId = id(topic);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new ApiException(ErrorCode.UNAUTHENTICATED);
        List<String> granted = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        boolean allowed = VIEWERS.stream().anyMatch(p -> granted.contains(p.authority()) || granted.contains(p.ownAuthority()));
        if (!allowed) throw new ApiException(ErrorCode.FORBIDDEN);
        scope.requireSite(siteId);
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        return reads.snapshotForSubscriber(id(topic));
    }

    private static UUID id(String topic) {
        try {
            return UUID.fromString(topic.substring(Topics.SITE_PREFIX.length(), topic.length() - Topics.DASHBOARD_SUFFIX.length()));
        } catch (RuntimeException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
