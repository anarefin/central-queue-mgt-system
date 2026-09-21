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
 * {@code site:{id}:alerts} (ticket 47, §21.2, §21.4): {@code alert.raised} and {@code alert.acknowledged}, watched
 * by whoever already watches that Site's live dashboard ({@link DashboardTopics}, the same {@code dashboard:view_*}
 * reach {@link AlertReadService} itself checks). Unlike {@code staff-alert:{site_id}} ({@code
 * com.qms.notification.NotificationTopics}), a persisted alert has a current state worth a real snapshot: a
 * reconnecting subscriber is shown every open alert, not merely the Site id (§21.1).
 */
@Component
class AlertTopics implements TopicSource {

    private static final List<Permission> VIEWERS = List.of(Permission.DASHBOARD_VIEW_ALL, Permission.DASHBOARD_VIEW_OWN_GROUPS);

    private final AlertReadService alerts;
    private final ScopeGuard scope;

    AlertTopics(AlertReadService alerts, ScopeGuard scope) {
        this.alerts = alerts;
        this.scope = scope;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.SITE_PREFIX) && topic.endsWith(Topics.ALERTS_SUFFIX);
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
        UUID siteId = id(topic);
        return Map.of("site_id", siteId.toString(), "alerts", alerts.snapshotForSubscriber(siteId));
    }

    private static UUID id(String topic) {
        try {
            return UUID.fromString(topic.substring(Topics.SITE_PREFIX.length(), topic.length() - Topics.ALERTS_SUFFIX.length()));
        } catch (RuntimeException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
