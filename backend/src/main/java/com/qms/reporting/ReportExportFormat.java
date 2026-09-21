package com.qms.reporting;

import java.util.Arrays;
import java.util.Optional;

/** The three export formats FR-RPT-003 requires. CSV and XLSX carry raw values, never a formatted display string
 * (§16's own "raw values, not formatted display strings"); PDF is a formatted document, the one format meant to be
 * read rather than re-imported. */
enum ReportExportFormat {
    CSV("csv", "csv", "text/csv"),
    XLSX("xlsx", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "pdf", "application/pdf");

    private final String wire;
    private final String extension;
    private final String contentType;

    ReportExportFormat(String wire, String extension, String contentType) {
        this.wire = wire;
        this.extension = extension;
        this.contentType = contentType;
    }

    String wire() {
        return wire;
    }

    String extension() {
        return extension;
    }

    String contentType() {
        return contentType;
    }

    static Optional<ReportExportFormat> fromWire(String wire) {
        return Arrays.stream(values()).filter(f -> f.wire.equals(wire)).findFirst();
    }
}
