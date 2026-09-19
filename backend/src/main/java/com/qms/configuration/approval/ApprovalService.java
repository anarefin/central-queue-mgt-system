package com.qms.configuration.approval;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team Admin requests that need an Org Admin's decision (FR-CFG-102). A request is a pending row an approver acts on;
 * it is never a temporary role elevation (FR-CFG-107), and it does nothing until it is approved.
 */
@Service
@Profile(Profiles.SERVING)
public class ApprovalService {

    static final int MAX_PAYLOAD_FIELDS = 20;

    private final ApprovalRepository repository;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final Authz authz;
    private final ScopeGuard scope;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ApprovalService(
            ApprovalRepository repository,
            AuditWriter audit,
            CurrentUser currentUser,
            Authz authz,
            ScopeGuard scope,
            ApplicationEventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
        this.authz = authz;
        this.scope = scope;
        this.events = events;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_REQUEST, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_REQUEST)")
    @Transactional
    public ApprovalView request(ApprovalType type, Map<String, Object> payload) {
        authz.require(type.requestPermission());
        UUID groupId = validatePayload(type, payload);
        scope.requireGroup(groupId); // a client-supplied group id is checked against the caller's claims (FR-CFG-106)

        UUID id = repository.insert(type, currentUser.require().userId(), payload, clock.instant());
        audit.record(AuditEvent.of("approval.requested", "approval_request", id).withAfter(Map.of("type", type.wire(), "payload", payload)));
        return repository.find(id).orElseThrow();
    }

    /** Pending (or other status) requests of the types the caller may decide. */
    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_APPROVE)")
    @Transactional(readOnly = true)
    public List<ApprovalView> listForApprover(String status) {
        String effective = status == null ? "pending" : status;
        if (!List.of("pending", "approved", "rejected").contains(effective)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "status"));
        }
        List<String> types = Arrays.stream(ApprovalType.values()).filter(t -> authz.has(t.approvePermission())).map(ApprovalType::wire).toList();
        return repository.byStatus(effective, types);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<ApprovalView> mine() {
        return repository.byRequester(currentUser.require().userId());
    }

    @PreAuthorize("hasAnyAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE, T(com.qms.platform.security.Authorities).COUNTER_ALLOCATION_APPROVE)")
    @Transactional
    public ApprovalView decide(UUID id, boolean approve, String reason) {
        ApprovalView request = repository.lock(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        ApprovalType type = ApprovalType.fromWire(request.type());
        authz.require(type.approvePermission());
        scope.requireGroup(UUID.fromString(String.valueOf(request.payload().get("group_id"))));
        if (!"pending".equals(request.status())) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("status", request.status()));
        }

        String status = approve ? "approved" : "rejected";
        repository.decide(id, status, currentUser.require().userId(), clock.instant(), reason);
        Map<String, Object> before = Map.of("status", "pending");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("type", type.wire());
        audit.record(AuditEvent.of("approval." + status, "approval_request", id).withBefore(before).withAfter(after).withReason(reason));
        events.publishEvent(new ApprovalDecided(id, type, approve, request.payload()));
        return repository.find(id).orElseThrow();
    }

    private static UUID validatePayload(ApprovalType type, Map<String, Object> payload) {
        if (payload == null || payload.size() > MAX_PAYLOAD_FIELDS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "payload"));
        }
        for (String required : type.requiredPayloadFields()) {
            Object value = payload.get(required);
            try {
                UUID.fromString(String.valueOf(value));
            } catch (IllegalArgumentException notAUuid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "payload." + required));
            }
        }
        return UUID.fromString(String.valueOf(payload.get("group_id")));
    }
}
