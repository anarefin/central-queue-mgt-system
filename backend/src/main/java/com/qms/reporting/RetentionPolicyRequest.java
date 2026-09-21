package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code PUT /retention/policies/{dataClass}}'s body (ticket 53, FR-SEC-032). {@code mode} is optional: omitted,
 * the policy's current mode is kept; given for a data class other than {@code ticket_detail}, it must still be
 * {@code purge} (the only mode those classes ever have) or the request is refused. */
record RetentionPolicyRequest(@JsonProperty("retention_months") Integer retentionMonths, String mode) {}
