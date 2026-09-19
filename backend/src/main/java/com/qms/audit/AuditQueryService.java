package com.qms.audit;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Search and export for an Org Admin (FR-SEC-042). Authorisation is enforced here, at the service layer, and not only
 * in the controller (API-016).
 */
@Service
public class AuditQueryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final int EXPORT_PAGE = 500;

    private final AuditRepository repository;
    private final AuditWriter writer;
    private final AuditCsv csv;
    private final AuditProperties properties;

    AuditQueryService(AuditRepository repository, AuditWriter writer, AuditCsv csv, AuditProperties properties) {
        this.repository = repository;
        this.writer = writer;
        this.csv = csv;
        this.properties = properties;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    public AuditPage search(AuditFilter filter, String cursor, Integer limit) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "limit"));
        }
        return repository.search(filter, cursor == null ? null : AuditCursor.decode(cursor), Math.min(effective, MAX_LIMIT));
    }

    /** Exporting personal data such as source addresses is itself an audited action (FR-SEC-040). */
    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    public AuditExport export(AuditFilter filter) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("actor_id", filter.actorId() == null ? null : filter.actorId().toString());
        criteria.put("action", filter.action());
        criteria.put("entity", filter.entity());
        criteria.put("entity_id", filter.entityId() == null ? null : filter.entityId().toString());
        criteria.put("from", filter.from() == null ? null : filter.from().toString());
        criteria.put("to", filter.to() == null ? null : filter.to().toString());
        writer.record(AuditEvent.of("audit.exported", "audit_log", null).withAfter(criteria));

        return out -> {
            out.write((AuditCsv.HEADER + "\n").getBytes(StandardCharsets.UTF_8));
            AuditCursor cursor = null;
            int written = 0;
            while (written < properties.exportMaxRows()) {
                int batch = Math.min(EXPORT_PAGE, properties.exportMaxRows() - written);
                AuditPage page = repository.search(filter, cursor, batch);
                for (AuditEntry entry : page.items()) {
                    out.write((csv.row(entry) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                written += page.items().size();
                if (page.nextCursor() == null) break;
                cursor = AuditCursor.decode(page.nextCursor());
            }
        };
    }
}
