package com.qms.configuration.versioning;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The warning FR-CFG-041 asks for, ahead of a change: how many Tickets already waiting (waiting, paused or remote)
 * sit under the scope about to change. Nothing is renumbered or reprioritised retroactively either way (FR-CFG-041);
 * this is informational, so the admin can choose to go ahead having seen it.
 */
public record ConfigImpact(@JsonProperty("affected_waiting_tickets") int affectedWaitingTickets) {}
