package com.qms.issuance.bundle;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** How many rows of each kind a bundle import applied (CFG-004), for the admin to confirm the clone landed. */
public record ConfigBundleImportResult(@JsonProperty("applied_counts") Map<String, Integer> appliedCounts) {}
