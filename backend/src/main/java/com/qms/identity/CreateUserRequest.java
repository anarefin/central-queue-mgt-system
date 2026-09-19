package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "[A-Za-z0-9._@-]+") String username,
        @NotBlank @Size(max = 1000) String password,
        @JsonProperty("display_name") @Size(max = 200) String displayName,
        @JsonProperty("preferred_language") @Pattern(regexp = "[a-z]{2,3}") String preferredLanguage,
        @Valid List<RoleAssignmentRequest> roles) {}
