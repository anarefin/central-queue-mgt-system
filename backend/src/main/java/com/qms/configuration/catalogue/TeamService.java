package com.qms.configuration.catalogue;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.approval.ApprovalDecided;
import com.qms.configuration.approval.ApprovalType;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The team of a service group and who is in it. An Org Admin changes membership directly; a Team Admin only asks, and
 * the change is applied when an Org Admin approves the request (FR-CFG-102), never before, and never by lending the
 * Team Admin a higher role (FR-CFG-107).
 */
@Service
@Profile(Profiles.SERVING)
public class TeamService {

    private static final String READ = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";
    private static final String CHANGE = "hasAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE)";

    private final TeamRepository repository;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;

    TeamService(TeamRepository repository, AuditWriter audit, CurrentUser currentUser, ScopeGuard scope) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
    }

    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public Team team(UUID groupId) {
        scope.requireSite(repository.siteOfGroup(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
        return load(groupId);
    }

    @PreAuthorize(CHANGE)
    @Transactional
    public Team addMember(UUID groupId, UUID userId) {
        scope.requireSite(repository.siteOfGroup(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
        add(groupId, userId, null);
        return load(groupId);
    }

    @PreAuthorize(CHANGE)
    @Transactional
    public Team removeMember(UUID groupId, UUID userId) {
        scope.requireSite(repository.siteOfGroup(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
        if (!remove(groupId, userId, null)) throw new ApiException(ErrorCode.NOT_FOUND);
        return load(groupId);
    }

    /**
     * Applies an approved Team Admin request. It runs inside the approver's transaction, so a change that cannot be
     * applied (for example, the user was disabled since) rolls the decision back and tells the approver why. A payload
     * {@code action} of {@code remove} removes the user; anything else adds them.
     */
    @EventListener
    public void onApprovalDecided(ApprovalDecided decided) {
        if (!decided.approved() || decided.type() != ApprovalType.TEAM_MEMBER) return;
        UUID groupId = UUID.fromString(String.valueOf(decided.payload().get("group_id")));
        UUID userId = UUID.fromString(String.valueOf(decided.payload().get("user_id")));
        if ("remove".equals(decided.payload().get("action"))) {
            remove(groupId, userId, decided.approvalId());
        } else {
            add(groupId, userId, decided.approvalId());
        }
    }

    private void add(UUID groupId, UUID userId, UUID approvalId) {
        Team team = load(groupId);
        boolean active = repository.userActive(userId).orElseThrow(() -> CatalogueRules.invalid("user_id", "unknown_user"));
        if (!active) throw CatalogueRules.invalid("user_id", "user_inactive");
        if (repository.addMember(team.id(), userId, currentUser.get().map(u -> u.userId()).orElse(null))) {
            audit.record(AuditEvent.of("team.member_added", "team", team.id()).withAfter(change(groupId, userId, approvalId)));
        }
    }

    private boolean remove(UUID groupId, UUID userId, UUID approvalId) {
        Team team = load(groupId);
        boolean removed = repository.removeMember(team.id(), userId);
        if (removed) {
            audit.record(AuditEvent.of("team.member_removed", "team", team.id()).withBefore(change(groupId, userId, approvalId)));
        }
        return removed || approvalId != null;
    }

    private static Map<String, Object> change(UUID groupId, UUID userId, UUID approvalId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service_group_id", groupId.toString());
        values.put("user_id", userId.toString());
        if (approvalId != null) values.put("approval_id", approvalId.toString());
        return values;
    }

    private Team load(UUID groupId) {
        var row = repository.ofGroup(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new Team(row.id(), row.serviceGroupId(), row.name(), repository.members(row.id()));
    }
}
