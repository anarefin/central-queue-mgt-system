package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The hand-rolled RFC 4180 reader {@link VisitorImportService} parses every CSV file with. */
class VisitorCsvParserTest {

    @Test
    void plainCommaSeparatedRowsMapEachCellToItsHeaderName() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name,phone\nE1,Amina,0170\nE2,Karim,0180\n");

        assertThat(parsed.header()).containsExactly("external_code", "name", "phone");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).lineNumber()).isEqualTo(2);
        assertThat(parsed.rows().get(0).cells()).containsEntry("external_code", "E1").containsEntry("name", "Amina").containsEntry("phone", "0170");
        assertThat(parsed.rows().get(1).lineNumber()).isEqualTo(3);
        assertThat(parsed.rows().get(1).cells()).containsEntry("external_code", "E2");
    }

    @Test
    void aQuotedFieldMayContainACommaOrAnEscapedQuote() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name\nE1,\"Rahman, Amina \"\"the visitor\"\"\"\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).cells().get("name")).isEqualTo("Rahman, Amina \"the visitor\"");
    }

    @Test
    void crlfLineEndingsAreAcceptedJustLikeLf() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name\r\nE1,Amina\r\nE2,Karim\r\n");

        assertThat(parsed.rows()).hasSize(2);
    }

    @Test
    void aRowShorterThanTheHeaderFillsTheMissingCellsWithAnEmptyString() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name,phone\nE1,Amina\n");

        assertThat(parsed.rows().get(0).cells()).containsEntry("phone", "");
    }

    @Test
    void trailingBlankLinesAreNotCountedAsRows() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name\nE1,Amina\n\n");

        assertThat(parsed.rows()).hasSize(1);
    }

    @Test
    void emptyContentParsesToNoHeaderAndNoRows() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("");

        assertThat(parsed.header()).isEqualTo(List.of());
        assertThat(parsed.rows()).isEmpty();
    }

    @Test
    void aFileWithOnlyAHeaderHasNoRows() {
        VisitorCsvParser.ParsedCsv parsed = VisitorCsvParser.parse("external_code,name\n");

        assertThat(parsed.header()).containsExactly("external_code", "name");
        assertThat(parsed.rows()).isEmpty();
    }
}
