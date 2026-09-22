package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** The setup wizard's own read model (SRS §26.2): every step's completion, computed live off the real data. */
public record SetupState(
        @JsonProperty("profile_applied") boolean profileApplied,
        @JsonProperty("active_profile") ActiveProfile activeProfile,
        @JsonProperty("org_and_sites") boolean orgAndSites,
        @JsonProperty("zones_and_counters") boolean zonesAndCounters,
        @JsonProperty("services_and_numbering") boolean servicesAndNumbering,
        @JsonProperty("users_and_roles") boolean usersAndRoles,
        @JsonProperty("devices_registered") boolean devicesRegistered,
        @JsonProperty("test_token") TestTokenState testToken,
        @JsonProperty("go_live_ready") boolean goLiveReady,
        @JsonProperty("go_live_at") Instant goLiveAt) {

    public record TestTokenState(
            boolean issued, boolean printed, boolean called, boolean announced, @JsonProperty("ticket_id") String ticketId, @JsonProperty("token_number") String tokenNumber) {
        static final TestTokenState NONE = new TestTokenState(false, false, false, false, null, null);
    }
}
