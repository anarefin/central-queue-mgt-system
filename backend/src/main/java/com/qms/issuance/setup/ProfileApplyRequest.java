package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Which shipped vertical profile to apply or reset to (SRS §3.4). */
public record ProfileApplyRequest(@JsonProperty("profile_id") String profileId) {}
