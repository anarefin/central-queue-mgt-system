package com.qms.audit;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** {@code GET /audit}: search (JSON) and export (CSV) for an Org Admin. Permission is checked in the service. */
@RestController
@RequestMapping("/audit")
class AuditController {

    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final AuditQueryService service;

    AuditController(AuditQueryService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AuditPage search(
            @RequestParam(name = "actor_id", required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entity,
            @RequestParam(name = "entity_id", required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.search(new AuditFilter(actorId, action, entity, entityId, from, to), cursor, limit);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    @GetMapping(params = "format=csv", produces = "text/csv")
    ResponseEntity<StreamingResponseBody> export(
            @RequestParam(name = "actor_id", required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entity,
            @RequestParam(name = "entity_id", required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        AuditExport export = service.export(new AuditFilter(actorId, action, entity, entityId, from, to));
        return ResponseEntity.ok()
                .contentType(CSV)
                .header("Content-Disposition", "attachment; filename=\"audit-log.csv\"")
                .body(export::writeTo);
    }
}
