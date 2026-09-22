package com.qms.audit.diagnostics;

import com.qms.audit.AuditEntry;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditFilter;
import com.qms.audit.AuditPage;
import com.qms.audit.AuditQueryService;
import com.qms.audit.AuditWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Assembles the one-click support diagnostics bundle (FR-OPS-040, §26.5): versions, a redacted configuration
 * snapshot and recent events, zipped together. Config is an explicit allow-list, never a dump of every property, so a
 * secret can never leak here even by accident (NFR-SEC-013).
 */
@Service
public class DiagnosticsService {

    /** label -> property key. Deliberately short: anything not named here never reaches the bundle. */
    private static final Map<String, String> CONFIG_ALLOWLIST = Map.of(
            "qms.version", "qms.ops.version",
            "spring.flyway.enabled", "spring.flyway.enabled",
            "qms.security.issuer", "qms.security.issuer",
            "qms.i18n.system-default-language", "qms.i18n.system-default-language",
            "qms.i18n.languages", "qms.i18n.languages",
            "qms.queue.call-timeout-seconds", "qms.queue.call-timeout-seconds",
            "qms.queue.miss-limit", "qms.queue.miss-limit",
            "qms.connectivity.probe-targets", "qms.connectivity.probe-targets");

    private static final int RECENT_EVENTS_LIMIT = 200;

    private final AuditQueryService auditQueryService;
    private final AuditWriter auditWriter;
    private final Environment environment;
    private final Clock clock;

    DiagnosticsService(AuditQueryService auditQueryService, AuditWriter auditWriter, Environment environment, Clock clock) {
        this.auditQueryService = auditQueryService;
        this.auditWriter = auditWriter;
        this.environment = environment;
        this.clock = clock;
    }

    /** Builds the zip and records the export itself in the audit log (FR-SEC-040: exporting is an audited action). */
    public byte[] build() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                writeEntry(zip, "versions.txt", versions(now));
                writeEntry(zip, "config.txt", config());
                writeEntry(zip, "recent-events.txt", recentEvents());
            }
            auditWriter.record(AuditEvent.of("ops.diagnostics_exported", "diagnostics", null));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String filename() {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        return "qms-diagnostics-" + stamp + ".zip";
    }

    private String versions(OffsetDateTime now) {
        StringBuilder out = new StringBuilder();
        out.append("qms-backend ").append(environment.getProperty("qms.ops.version", "unknown")).append('\n');
        out.append("java ").append(System.getProperty("java.version", "unknown")).append('\n');
        out.append("os ").append(System.getProperty("os.name", "unknown")).append(' ').append(System.getProperty("os.version", "")).append('\n');
        out.append("generated_at ").append(now).append('\n');
        return out.toString();
    }

    private String config() {
        StringBuilder out = new StringBuilder();
        CONFIG_ALLOWLIST.keySet().stream().sorted().forEach(label -> {
            String key = CONFIG_ALLOWLIST.get(label);
            String value = environment.getProperty(key);
            out.append(label).append('=').append(value == null ? "" : value).append('\n');
        });
        return out.toString();
    }

    private String recentEvents() {
        AuditPage page = auditQueryService.search(new AuditFilter(null, null, null, null, null, null), null, RECENT_EVENTS_LIMIT);
        List<AuditEntry> items = page.items();
        if (items.isEmpty()) {
            return "(no recent events)\n";
        }
        StringBuilder out = new StringBuilder();
        for (AuditEntry entry : items) {
            out.append(entry.occurredAt())
                    .append(" | actor=")
                    .append(entry.actorRole() == null ? "-" : entry.actorRole())
                    .append(" | action=")
                    .append(entry.action())
                    .append(" | entity=")
                    .append(entry.entity() == null ? "-" : entry.entity())
                    .append('\n');
        }
        return out.toString();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
