package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * What a staff action on a ticket leaves behind: the ticket's state, the Priority class it now queues under (the default
 * class is named too) and its place in the queue, which is null once it has left the queue. {@code version} is the ticket's
 * version after the change, for the next {@code If-Match}.
 */
public record TicketChange(
        UUID id,
        @JsonProperty("token_number") String tokenNumber,
        String state,
        @JsonProperty("priority_class_id") UUID priorityClassId,
        Integer position,
        int version) {}
