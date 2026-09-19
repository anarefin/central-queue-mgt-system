package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the ticket in service may be transferred (F7): the active Services of the session's site, and the counters and agents
 * that can take each one (FR-QUE-052). Transfers are intra-site (ADR-0002), so nothing from another site is listed.
 */
public record TransferTargets(List<Service> services, List<CounterTarget> counters, List<AgentTarget> agents) {

    public record Service(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}

    public record CounterTarget(UUID id, String label, @JsonProperty("zone_name") String zoneName, @JsonProperty("service_ids") List<UUID> serviceIds) {}

    public record AgentTarget(UUID id, String name, @JsonProperty("service_ids") List<UUID> serviceIds) {}
}
