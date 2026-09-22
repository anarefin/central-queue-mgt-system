package com.qms.issuance.setup;

/** One feature-flag key's new state (CFG-003: any value a profile sets stays editable afterwards). */
public record FeatureFlagUpdateRequest(boolean enabled) {}
