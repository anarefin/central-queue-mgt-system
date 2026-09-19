package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Break time per agent and break type (FR-AGT-022, SRS §16.1 "Break report": count, total and average duration, overruns).
 * Only breaks that have ended count; a break still running has no duration yet.
 */
public record BreakReport(Instant from, Instant to, List<Row> rows) {

    public record Row(
            @JsonProperty("agent_id") UUID agentId,
            @JsonProperty("agent_name") String agentName,
            @JsonProperty("break_type") Type breakType,
            int count,
            @JsonProperty("total_seconds") long totalSeconds,
            @JsonProperty("average_seconds") long averageSeconds,
            int overruns) {}

    public record Type(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("max_minutes") Integer maxMinutes) {}

    /** One ended break, as the report reads it. */
    record Taken(UUID agentId, String agentName, UUID typeId, Map<String, String> typeNames, Integer maxMinutes, Instant startedAt, Instant endedAt) {}

    /** Groups ended breaks by agent and type, ordered by agent name then type, with the average rounded to whole seconds. */
    static List<Row> summarise(List<Taken> breaks) {
        record Key(UUID agent, UUID type) {}
        Map<Key, List<Taken>> grouped = new LinkedHashMap<>();
        for (Taken taken : breaks) grouped.computeIfAbsent(new Key(taken.agentId(), taken.typeId()), k -> new ArrayList<>()).add(taken);
        List<Row> rows = new ArrayList<>();
        for (List<Taken> group : grouped.values()) {
            Taken first = group.getFirst();
            long total = 0;
            int overruns = 0;
            for (Taken taken : group) {
                int seconds = BreakRules.seconds(taken.startedAt(), taken.endedAt());
                total += seconds;
                if (BreakRules.overran(seconds, taken.maxMinutes())) overruns++;
            }
            rows.add(new Row(first.agentId(), first.agentName(), new Type(first.typeId(), first.typeNames(), first.maxMinutes()), group.size(), total, Math.round((double) total / group.size()), overruns));
        }
        rows.sort(java.util.Comparator.comparing((Row r) -> r.agentName() == null ? "" : r.agentName()).thenComparing(r -> r.breakType().id()));
        return rows;
    }
}
