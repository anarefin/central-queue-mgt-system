package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * An Agent's own current day (FR-AGT-040): what they have served, how many wait for the Services of their session, how long a ticket
 * took them on average, and how long they have spent on break. It is about the caller alone and carries no other Agent's figure and no
 * ranking, so nothing on the Agent's screen can compare them with a colleague. "Day" is the day at each ticket's or break's own Site, in the
 * Site's time zone (FR-CFG-002). {@code average_service_seconds} is null until a ticket has been served today. A break still running counts up to {@code as_of}.
 */
public record AgentDay(
        int served,
        @JsonProperty("in_queue") int inQueue,
        @JsonProperty("average_service_seconds") Integer averageServiceSeconds,
        @JsonProperty("break_seconds") long breakSeconds,
        @JsonProperty("as_of") Instant asOf) {}
