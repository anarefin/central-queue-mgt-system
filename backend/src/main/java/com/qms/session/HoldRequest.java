package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code POST /sessions/{id}/hold} holds the ticket being served; naming a {@code ticket_id} resumes that held ticket instead
 * (SRS §20.4, FR-AGT-013).
 */
public record HoldRequest(@JsonProperty("ticket_id") UUID ticketId) {}
