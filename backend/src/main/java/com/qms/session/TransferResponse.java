package com.qms.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * What a transfer did (SRS §20.4): the predecessor, closed as {@code transferred}, and the successor now waiting in the target
 * queue with the same token number and visit (ADR-0006), plus the session as it stands so a console can redraw itself.
 * {@code head_start_minutes} is the transfer Head start the successor was given (FR-QUE-053).
 */
public record TransferResponse(Predecessor predecessor, Successor successor, SessionResponse session) {

    public record Predecessor(UUID id, @JsonProperty("token_number") String tokenNumber, String state) {}

    public record Successor(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            SessionResponse.Named service,
            @JsonProperty("visit_id") UUID visitId,
            @JsonProperty("predecessor_ticket_id") UUID predecessorTicketId,
            @JsonProperty("counter_id") @JsonInclude(JsonInclude.Include.NON_NULL) UUID counterId,
            @JsonProperty("agent_id") @JsonInclude(JsonInclude.Include.NON_NULL) UUID agentId,
            @JsonProperty("head_start_minutes") int headStartMinutes,
            Integer position) {}
}
