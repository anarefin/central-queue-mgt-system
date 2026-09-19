package com.qms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record UpdateUserRequest(
        @JsonProperty("display_name") @Size(max = 200) String displayName,
        @JsonProperty("preferred_language") @Pattern(regexp = "[a-z]{2,3}") String preferredLanguage) {}
