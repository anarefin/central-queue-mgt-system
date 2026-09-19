package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** {@code POST /visitors}: FR-ISS-021's minimum record plus the pass reference Reception hands the walk-in. */
public record VisitorRegistrationResponse(
        UUID id,
        @JsonProperty("pass_reference") String passReference,
        String name,
        String phone,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) String category,
        @JsonInclude(JsonInclude.Include.NON_NULL) String purpose) {}
