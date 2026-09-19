package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** The outcome is chosen from the ticket's Service's list; it is required whenever that list is not empty (FR-AGT-032). */
public record CompleteRequest(@JsonProperty("outcome_code_id") UUID outcomeCodeId, String note) {}
