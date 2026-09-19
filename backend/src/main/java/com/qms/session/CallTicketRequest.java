package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** A call of a specific waiting ticket out of order; the reason is mandatory (FR-AGT-012). */
record CallTicketRequest(@JsonProperty("ticket_id") UUID ticketId, String reason) {}
