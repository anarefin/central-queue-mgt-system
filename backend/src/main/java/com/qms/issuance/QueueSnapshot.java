package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.queue.WaitEstimate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A service's queue right now: how many are waiting and the first few in order (SRS §20.4, §21.2). */
public record QueueSnapshot(
        NameRef service,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("waiting_count") int waitingCount,
        @JsonProperty("estimated_wait_minutes") WaitEstimate estimatedWait,
        List<Entry> tickets) {

    /** {@code escalated} flags a ticket past its class's maximum wait, for the dashboard (FR-QUE-022). */
    public record Entry(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            int position,
            @JsonProperty("origin_channel") String originChannel,
            @JsonProperty("queued_at") Instant queuedAt,
            @JsonProperty("priority_class") NameRef priorityClass,
            boolean escalated) {}
}
