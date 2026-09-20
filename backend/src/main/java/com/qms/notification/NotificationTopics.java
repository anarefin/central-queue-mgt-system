package com.qms.notification;

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
 * Who may subscribe to a Site's {@code staff_alert} topic (FR-QUE-080, ticket 38): whoever watches that Site's live
 * dashboard, the same reach {@code com.qms.session.ConsoleTopics} already grants a queue or counter's own topic.
 */
@Component
class NotificationTopics implements TopicSource {

    private static final List<Permission> VIEWERS = List.of(Permission.DASHBOARD_VIEW_ALL, Permission.DASHBOARD_VIEW_OWN_GROUPS);

    private final ScopeGuard scope;

    NotificationTopics(ScopeGuard scope) {
        this.scope = scope;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.STAFF_ALERT);
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
        return Map.of("site_id", id(topic).toString());
    }

    private static UUID id(String topic) {
        try {
            return UUID.fromString(topic.substring(Topics.STAFF_ALERT.length()));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
