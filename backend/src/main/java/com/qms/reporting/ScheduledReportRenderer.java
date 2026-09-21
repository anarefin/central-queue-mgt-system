package com.qms.reporting;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Renders any report key's own {@code POST /reports/{key}/run} answer (ticket 48/50/51) to CSV, XLSX or PDF for a
 * scheduled delivery (ticket 52, FR-RPT-005). Unlike {@link DetailedTokenReportCsvWriter} and its XLSX/PDF siblings
 * (ticket 49), which are written against {@code detailed-token}'s own strongly-typed row, this one has to work
 * across the whole catalogue without knowing any given key's answer shape ahead of time. It flattens whatever JSON
 * object the catalogue answered with: the first field, in document order, whose value is an array of objects
 * becomes the table — every report key's own answer carries its row grain in exactly one such field ("rows",
 * "items" or "cells"), so this never needs to know that field's actual name — and those objects' own keys, in
 * first-seen order, become the column headers. A nested object or array cell (a localised name, the catalogue's own
 * "extra" aggregate) is rendered as its own compact JSON text rather than expanded further, since its shape varies
 * by key and cannot be tabulated generically.
 */
@Component
class ScheduledReportRenderer {

    private static final float MARGIN = 24f;
    private static final float FONT_SIZE = 7f;
    private static final float LEADING = 10f;
    private static final float HEADER_FONT_SIZE = 11f;

    private final JsonMapper mapper;

    ScheduledReportRenderer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    record Rendered(byte[] content, long rowCount) {}

    Rendered render(ReportExportFormat format, List<String[]> headerLines, Object result) throws IOException {
        JsonNode root = mapper.valueToTree(result);
        ArrayNode rowsNode = findRows(root);
        List<String> columns = columns(rowsNode);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : rowsNode) rows.add(cells(row, columns));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (format) {
            case CSV -> writeCsv(out, headerLines, columns, rows);
            case XLSX -> writeXlsx(out, headerLines, columns, rows);
            case PDF -> writePdf(out, headerLines, columns, rows);
        }
        return new Rendered(out.toByteArray(), rows.size());
    }

    private static ArrayNode findRows(JsonNode root) {
        if (root == null || !root.isObject()) return emptyArray();
        for (var entry : root.properties()) {
            if (entry.getValue() instanceof ArrayNode array && !array.isEmpty() && array.get(0).isObject()) return array;
        }
        for (var entry : root.properties()) {
            if (entry.getValue() instanceof ArrayNode array) return array;
        }
        return emptyArray();
    }

    private static ArrayNode emptyArray() {
        return JsonNodeFactory.instance.arrayNode();
    }

    private static List<String> columns(ArrayNode rows) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) continue;
            for (var entry : row.properties()) seen.add(entry.getKey());
        }
        return List.copyOf(seen);
    }

    private static List<String> cells(JsonNode row, List<String> columns) {
        List<String> cells = new ArrayList<>(columns.size());
        for (String column : columns) cells.add(cellText(row.path(column)));
        return cells;
    }

    private static String cellText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isObject() || value.isArray()) return value.toString();
        return value.asText();
    }

    // ---- CSV: the same header-block / blank-line / column-header / data-row layout ticket 49's own writer uses,
    // and the same formula-injection defusing (a leading = + - @ TAB CR), since this file leaves the system too. --

    private static void writeCsv(OutputStream out, List<String[]> headerLines, List<String> columns, List<List<String>> rows) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        for (String[] line : headerLines) {
            writer.write(cell(line[0]) + "," + cell(line[1]));
            writer.write("\n");
        }
        writer.write("\n");
        writer.write(columns.stream().map(ScheduledReportRenderer::cell).collect(Collectors.joining(",")));
        writer.write("\n");
        for (List<String> row : rows) {
            writer.write(row.stream().map(ScheduledReportRenderer::cell).collect(Collectors.joining(",")));
            writer.write("\n");
        }
        writer.flush();
    }

    private static String cell(String value) {
        if (value == null) return "";
        String safe = switch (value.isEmpty() ? ' ' : value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    // ---- XLSX -------------------------------------------------------------------------------------------------

    private static void writeXlsx(OutputStream out, List<String[]> headerLines, List<String> columns, List<List<String>> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(1000)) {
            SXSSFSheet sheet = workbook.createSheet("Report");
            int r = 0;
            for (String[] line : headerLines) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(line[0]);
                row.createCell(1).setCellValue(line[1]);
            }
            r++; // blank row between the header block and the table, matching the CSV layout
            Row header = sheet.createRow(r++);
            for (int c = 0; c < columns.size(); c++) header.createCell(c).setCellValue(columns.get(c));
            for (List<String> dataRow : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < dataRow.size(); c++) row.createCell(c).setCellValue(dataRow.get(c));
            }
            workbook.write(out);
            workbook.dispose();
        }
    }

    // ---- PDF: a single simple table; unlike ticket 49's own writer this never has to page a 1,000,000-row export
    // (a schedule's own window keeps it small), so the only page-break bookkeeping needed is starting a fresh page
    // once the current one runs out of room. --------------------------------------------------------------------

    private static void writePdf(OutputStream out, List<String[]> headerLines, List<String> columns, List<List<String>> rows) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDRectangle pageSize = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float columnWidth = (pageSize.getWidth() - 2 * MARGIN) / Math.max(1, columns.size());

            PdfCursor cursor = new PdfCursor(document, pageSize);
            cursor.newPage();
            for (String[] line : headerLines) cursor.text(HEADER_FONT_SIZE, bold, line[0] + ": " + line[1]);
            cursor.newLine();
            if (!columns.isEmpty()) cursor.row(columns, columnWidth, bold);
            for (List<String> row : rows) cursor.row(row, columnWidth, plain);
            cursor.close();
            document.save(out);
        }
    }

    /** Tracks the current page and Y position, starting a fresh page once content would run past the bottom
     * margin. Unlike {@code DetailedTokenReportPdfWriter}'s own cursor, the table header is not repeated on later
     * pages — a scheduled delivery's own window is small enough that this rarely spans more than one or two. */
    private static final class PdfCursor {
        private final PDDocument document;
        private final PDRectangle pageSize;
        private PDPageContentStream stream;
        private float y;

        PdfCursor(PDDocument document, PDRectangle pageSize) {
            this.document = document;
            this.pageSize = pageSize;
        }

        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = pageSize.getHeight() - MARGIN;
        }

        void text(float size, PDFont font, String value) throws IOException {
            ensureRoom();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(sanitise(value));
            stream.endText();
            y -= LEADING;
        }

        void newLine() {
            y -= LEADING;
        }

        void row(List<String> cells, float columnWidth, PDFont font) throws IOException {
            ensureRoom();
            stream.beginText();
            stream.setFont(font, FONT_SIZE);
            stream.newLineAtOffset(MARGIN, y);
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) stream.newLineAtOffset(columnWidth, 0);
                stream.showText(truncate(cells.get(i), columnWidth));
            }
            stream.endText();
            y -= LEADING;
        }

        private void ensureRoom() throws IOException {
            if (stream == null || y < MARGIN + LEADING) newPage();
        }

        private static String truncate(String value, float columnWidth) {
            String sanitised = sanitise(value);
            int maxChars = Math.max(3, (int) (columnWidth / (FONT_SIZE * 0.55f)));
            return sanitised.length() > maxChars ? sanitised.substring(0, maxChars - 1) + "…" : sanitised;
        }

        /** Helvetica (WinAnsiEncoding) cannot show every Unicode character (Bangla script, for one); anything
         * outside it is dropped rather than left to throw mid-render. */
        private static String sanitise(String value) {
            if (value == null) return "";
            StringBuilder safe = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 256) safe.append(c);
            }
            return safe.toString();
        }

        void close() throws IOException {
            if (stream != null) stream.close();
        }
    }
}
