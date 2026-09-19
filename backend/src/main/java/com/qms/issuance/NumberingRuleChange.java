package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The result of setting or removing a rule. A change never renumbers a ticket that was already issued
 * (FR-CFG-041); {@code affected_waiting_tickets} is the warning: how many tickets are waiting under the scope and keep
 * the numbers they have, while tickets issued from now on follow the new rule. {@code rule} is null once removed.
 */
public record NumberingRuleChange(NumberingRuleView rule, @JsonProperty("affected_waiting_tickets") int affectedWaitingTickets) {}
