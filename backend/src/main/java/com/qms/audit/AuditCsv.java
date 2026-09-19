package com.qms.audit;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** CSV rendering for the audit export. Cells are quoted and spreadsheet-formula prefixes are neutralised. */
@Component
class AuditCsv {

    static final String HEADER = "id,actor_id,actor_role,action,entity,entity_id,before,after,ip,device,reason,trace_id,occurred_at";

    private final JsonMapper mapper;

    AuditCsv(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String render(List<AuditEntry> entries) {
        return HEADER + "\n" + entries.stream().map(this::row).collect(Collectors.joining("\n"));
    }

    String row(AuditEntry e) {
        return String.join(
                ",",
                cell(e.id()),
                cell(e.actorId()),
                cell(e.actorRole()),
                cell(e.action()),
                cell(e.entity()),
                cell(e.entityId()),
                cell(json(e.before())),
                cell(json(e.after())),
                cell(e.ip()),
                cell(e.device()),
                cell(e.reason()),
                cell(e.traceId()),
                cell(e.occurredAt() == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(e.occurredAt())));
    }

    private String json(Map<String, Object> value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    private static String cell(UUID value) {
        return cell(value == null ? null : value.toString());
    }

    /** A leading {@code = + - @ TAB CR} would be executed as a formula by spreadsheet software, so it is defused. */
    static String cell(String value) {
        if (value == null) return "";
        String safe = switch (value.isEmpty() ? ' ' : value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
