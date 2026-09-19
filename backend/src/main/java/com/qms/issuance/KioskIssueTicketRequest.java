package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** The body of {@code POST /kiosk/tickets}: the visitor's chosen Service. The channel, actor and time are the device's own. */
public record KioskIssueTicketRequest(@JsonProperty("service_id") UUID serviceId) {}
