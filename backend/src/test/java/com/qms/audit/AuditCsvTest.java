package com.qms.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-SEC-042: the export must be safe to open in a spreadsheet, since audit rows contain user-influenced text. */
class AuditCsvTest {

    @Test
    void cellsAreQuotedAndQuotesDoubled() {
        assertThat(AuditCsv.cell("plain")).isEqualTo("\"plain\"");
        assertThat(AuditCsv.cell("say \"hi\", ok\nnext")).isEqualTo("\"say \"\"hi\"\", ok\nnext\"");
        assertThat(AuditCsv.cell(null)).isEqualTo("");
    }

    @Test
    void leadingFormulaCharactersAreNeutralised() {
        for (String dangerous : new String[] {"=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd"}) {
            assertThat(AuditCsv.cell(dangerous)).as(dangerous).startsWith("\"'");
        }
        assertThat(AuditCsv.cell("=HYPERLINK(\"http://evil\")")).startsWith("\"'=");
    }

    @Test
    void headerAndRowsUseSnakeCaseColumnsAndIsoTimestamps() {
        var entry = new AuditEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "org_admin",
                "user.disabled",
                "user",
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                Map.of("active", true),
                Map.of("active", false),
                "10.0.0.5",
                "Mozilla/5.0",
                "left the company",
                "trace-1",
                OffsetDateTime.of(2026, 9, 19, 12, 0, 0, 0, ZoneOffset.ofHours(6)));

        String csv = new AuditCsv(tools.jackson.databind.json.JsonMapper.builder().build()).render(List.of(entry));
        String[] lines = csv.split("\n");

        assertThat(lines[0])
                .isEqualTo("id,actor_id,actor_role,action,entity,entity_id,before,after,ip,device,reason,trace_id,occurred_at");
        assertThat(lines[1]).contains("\"user.disabled\"").contains("2026-09-19T12:00:00+06:00").contains("left the company");
        assertThat(lines[1]).contains("{\"\"active\"\":false}");
    }
}
