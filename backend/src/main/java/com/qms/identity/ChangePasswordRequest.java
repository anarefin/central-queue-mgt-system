package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ChangePasswordRequest(
        @JsonProperty("current_password") @NotBlank @Size(max = 1000) String currentPassword,
        @JsonProperty("new_password") @NotBlank @Size(max = 1000) String newPassword) {}
