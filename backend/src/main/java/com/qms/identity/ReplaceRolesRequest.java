package com.qms.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

record ReplaceRolesRequest(@NotNull @Valid List<RoleAssignmentRequest> roles, @Size(max = 500) String reason) {}
