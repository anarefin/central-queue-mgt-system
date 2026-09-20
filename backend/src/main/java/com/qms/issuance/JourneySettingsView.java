package com.qms.issuance;

/** The Journey feature flag as the admin API reads and writes it (ticket 31: "feature flag per profile"). */
public record JourneySettingsView(boolean enabled) {}
