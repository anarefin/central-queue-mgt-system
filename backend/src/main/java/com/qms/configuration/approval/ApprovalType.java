package com.qms.configuration.approval;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Permission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * The Team Admin actions that need Org Admin approval (FR-CFG-102). Each names the permission needed to ask, the one
 * needed to decide, and the payload fields that must be present.
 */
public enum ApprovalType {
    TEAM_MEMBER("team_member", Permission.TEAM_MEMBER_REQUEST, Permission.TEAM_MEMBER_APPROVE, Set.of("group_id", "user_id")),
    COUNTER_ALLOCATION("counter_allocation", Permission.COUNTER_ALLOCATION_REQUEST, Permission.COUNTER_ALLOCATION_APPROVE, Set.of("group_id", "counter_id"));

    private final String wire;
    private final Permission requestPermission;
    private final Permission approvePermission;
    private final Set<String> requiredPayloadFields;

    ApprovalType(String wire, Permission requestPermission, Permission approvePermission, Set<String> requiredPayloadFields) {
        this.wire = wire;
        this.requestPermission = requestPermission;
        this.approvePermission = approvePermission;
        this.requiredPayloadFields = requiredPayloadFields;
    }

    public String wire() {
        return wire;
    }

    public Permission requestPermission() {
        return requestPermission;
    }

    public Permission approvePermission() {
        return approvePermission;
    }

    public Set<String> requiredPayloadFields() {
        return requiredPayloadFields;
    }

    public static ApprovalType fromWire(String wire) {
        return Arrays.stream(values())
                .filter(t -> t.wire.equals(wire))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "type", "value", String.valueOf(wire))));
    }
}
