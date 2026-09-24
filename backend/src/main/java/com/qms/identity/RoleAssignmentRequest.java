package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Both id lists empty means organisation-wide. */
record RoleAssignmentRequest(
        @NotBlank String role,
        @JsonProperty("site_ids") Set<UUID> siteIds,
        @JsonProperty("group_ids") Set<UUID> groupIds) {

    RoleAssignment toAssignment() {
        if ((siteIds != null && siteIds.contains(null)) || (groupIds != null && groupIds.contains(null))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", siteIds != null && siteIds.contains(null) ? "site_ids" : "group_ids"));
        }
        Role parsed = Role.tryFromWire(role).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "role", "value", role)));
        return new RoleAssignment(parsed, siteIds == null ? Set.of() : siteIds, groupIds == null ? Set.of() : groupIds);
    }
}
