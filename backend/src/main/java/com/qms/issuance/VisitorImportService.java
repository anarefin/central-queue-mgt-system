package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visitor master-data CSV import (SRS §22.2, FR-INT-010, FR-INT-011): the admin-set column mapping, a manual
 * upload or a scheduled folder pickup's own file, both run through the same {@link #importCsv} — the only
 * difference is {@code source} and who, if anyone, triggered it. Every row is upserted into the same {@code visitor}
 * table a walk-in's registration writes (by {@code external_code}, V18's unique index), which is what makes an
 * imported visitor resolvable through {@link VisitorDirectory} exactly like a walk-in is (FR-INT-010): both are rows
 * {@link LocalVisitorDirectory} reads. A row that fails validation is skipped, not fatal to the rest of the file.
 */
@Service
@Profile(Profiles.SERVING)
class VisitorImportService {

    /** A defensive cap so a badly mapped file with thousands of bad rows cannot make the report itself unbounded. */
    private static final int MAX_ERRORS = 500;

    private final VisitorImportMappingRepository mappingRepository;
    private final VisitorImportRunRepository runRepository;
    private final VisitorRepository visitors;
    private final AuditWriter audit;
    private final Clock clock;

    VisitorImportService(
            VisitorImportMappingRepository mappingRepository, VisitorImportRunRepository runRepository, VisitorRepository visitors, AuditWriter audit, Clock clock) {
        this.mappingRepository = mappingRepository;
        this.runRepository = runRepository;
        this.visitors = visitors;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code GET /visitors/import/mapping}. */
    VisitorImportMapping mapping() {
        return mappingRepository.get();
    }

    /** {@code PUT /visitors/import/mapping}. */
    @Transactional
    VisitorImportMapping updateMapping(VisitorImportMapping request, UUID updatedBy) {
        VisitorImportMapping mapping = validate(request);
        mappingRepository.save(mapping, updatedBy, clock.instant());
        audit.record(AuditEvent.of("visitor.import.mapping_updated", "visitor_import_mapping", null)
                .withAfter(Map.of(
                        "external_code_column", mapping.externalCodeColumn(),
                        "name_column", mapping.nameColumn())));
        return mapping;
    }

    /**
     * {@code POST /visitors/import} for a manual upload ({@code triggeredBy} the caller), or the scheduled folder
     * pickup for one picked-up file ({@code triggeredBy} {@code null} — nobody is signed in for that).
     */
    @Transactional
    VisitorImportReport importCsv(String filename, String content, String source, UUID triggeredBy) {
        if (content == null || content.isBlank()) throw invalid("content", "required");
        Instant startedAt = clock.instant();
        VisitorImportMapping mapping = mappingRepository.get();
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse(content);
        if (!parsed.header().contains(mapping.externalCodeColumn()) || !parsed.header().contains(mapping.nameColumn())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "mapping", "code", "column_not_found"))));
        }

        List<VisitorImportError> errors = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int failed = 0;
        for (VisitorCsvParser.Row row : parsed.rows()) {
            String externalCode = blank(row.cells().get(mapping.externalCodeColumn()));
            String name = blank(row.cells().get(mapping.nameColumn()));
            if (externalCode == null) {
                failed++;
                addError(errors, row.lineNumber(), "external_code", "required");
                continue;
            }
            if (name == null) {
                failed++;
                addError(errors, row.lineNumber(), "name", "required");
                continue;
            }
            String phone = column(row, mapping.phoneColumn());
            String email = column(row, mapping.emailColumn());
            String category = column(row, mapping.categoryColumn());
            VisitorRepository.UpsertResult result = visitors.upsertByExternalCode(externalCode, name, phone, email, category, clock.instant());
            if (result.inserted()) inserted++; else updated++;
        }

        Instant completedAt = clock.instant();
        VisitorImportReport report =
                new VisitorImportReport(UUID.randomUUID(), source, filename, startedAt, completedAt, parsed.rows().size(), inserted, updated, failed, "completed", List.copyOf(errors));
        runRepository.insert(report, triggeredBy);
        AuditEvent event = AuditEvent.of("visitor.import.completed", "visitor_import_run", report.id())
                .withAfter(Map.of(
                        "source", source,
                        "total_rows", report.totalRows(),
                        "inserted_count", inserted,
                        "updated_count", updated,
                        "failed_count", failed));
        // A manual upload's actor comes from the caller's token, as usual; a scheduled pickup has no one signed in,
        // so it is labelled explicitly rather than left blank.
        audit.record(triggeredBy == null ? event.withActor(null, "scheduled") : event);
        return report;
    }

    /** {@code GET /visitors/import/runs}, most recent first. */
    List<VisitorImportReport> recentRuns() {
        return runRepository.recent(50);
    }

    /** {@code GET /visitors/import/runs/{id}}. */
    VisitorImportReport run(UUID id) {
        return runRepository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static String column(VisitorCsvParser.Row row, String columnName) {
        return columnName == null ? null : blank(row.cells().get(columnName));
    }

    private static void addError(List<VisitorImportError> errors, int line, String field, String code) {
        if (errors.size() < MAX_ERRORS) errors.add(new VisitorImportError(line, field, code));
    }

    private static VisitorImportMapping validate(VisitorImportMapping request) {
        if (request == null) throw invalid("mapping", "required");
        String externalCode = blank(request.externalCodeColumn());
        String name = blank(request.nameColumn());
        if (externalCode == null) throw invalid("external_code_column", "required");
        if (name == null) throw invalid("name_column", "required");
        return new VisitorImportMapping(externalCode, name, blank(request.phoneColumn()), blank(request.emailColumn()), blank(request.categoryColumn()));
    }

    private static String blank(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
