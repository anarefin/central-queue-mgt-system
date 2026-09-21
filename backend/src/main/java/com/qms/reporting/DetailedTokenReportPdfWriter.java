package com.qms.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * PDF export (FR-RPT-003): a formatted, paginated document — FR-RPT-006's header block on the first page, then the
 * §16.1 table with its column headers repeated at the top of every page. Landscape A4 and a small fixed-width font
 * are used to fit all eighteen columns; a PDF is meant to be read, so unlike the CSV/XLSX writers it does not carry
 * the same "raw values" requirement (that is FR-RPT-003's own rule for CSV and XLSX specifically).
 *
 * <p>Unlike the CSV and XLSX writers, this one is not a true constant-memory stream: {@link PDDocument} keeps its
 * built pages in memory until {@link PDDocument#save} at the end. That is fine at the scale a formatted, printable
 * document is actually used at; NFR-PERF-006's 1,000,000-row target is realistically a CSV or XLSX pull, not a
 * PDF one, so this writer is not exercised at that scale.
 */
@Component
class DetailedTokenReportPdfWriter {

    private static final float MARGIN = 24f;
    private static final float FONT_SIZE = 6f;
    private static final float LEADING = 9f;
    private static final float HEADER_FONT_SIZE = 11f;

    long write(OutputStream out, List<String[]> headerLines, List<String> columnHeaders, ReportRowSource source, String language) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDRectangle pageSize = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float columnWidth = (pageSize.getWidth() - 2 * MARGIN) / columnHeaders.size();

            Cursor cursor = new Cursor(document, pageSize, bold, plain);
            cursor.newPage();
            for (String[] line : headerLines) {
                cursor.text(HEADER_FONT_SIZE, bold, line[0] + ": ", 0);
                cursor.sameLine(HEADER_FONT_SIZE, plain, line[1]);
                cursor.newLine();
            }
            cursor.newLine();
            cursor.tableHeader(columnHeaders, columnWidth, bold);

            AtomicLong count = new AtomicLong();
            IOException[] failure = new IOException[1];
            source.forEach(row -> {
                if (failure[0] != null) return;
                try {
                    List<Object> cells = DetailedTokenReportColumns.cells(row, language);
                    cursor.tableRow(cells, columnWidth, plain, columnHeaders);
                    count.incrementAndGet();
                } catch (IOException e) {
                    failure[0] = e;
                }
            });
            if (failure[0] != null) throw failure[0];
            cursor.close();
            document.save(out);
            return count.get();
        }
    }

    /** Tracks the current page and Y position, starting a fresh page (with the table header repeated) once content
     * would run past the bottom margin. */
    private static final class Cursor {
        private final PDDocument document;
        private final PDRectangle pageSize;
        private final PDFont bold;
        private final PDFont plain;
        private PDPageContentStream stream;
        private float y;
        private List<String> repeatHeader;
        private float repeatColumnWidth;

        Cursor(PDDocument document, PDRectangle pageSize, PDFont bold, PDFont plain) {
            this.document = document;
            this.pageSize = pageSize;
            this.bold = bold;
            this.plain = plain;
        }

        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = pageSize.getHeight() - MARGIN;
            if (repeatHeader != null) tableHeader(repeatHeader, repeatColumnWidth, bold);
        }

        void text(float size, PDFont font, String value, float x) throws IOException {
            ensureRoom();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN + x, y);
            stream.showText(sanitise(value));
            stream.endText();
        }

        /** Continues on the same line the last {@link #text} started, at a fixed offset past it. */
        void sameLine(float size, PDFont font, String value) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN + 90, y);
            stream.showText(sanitise(value));
            stream.endText();
        }

        void newLine() {
            y -= LEADING;
        }

        void tableHeader(List<String> headers, float columnWidth, PDFont font) throws IOException {
            this.repeatHeader = headers;
            this.repeatColumnWidth = columnWidth;
            ensureRoom();
            stream.beginText();
            stream.setFont(font, FONT_SIZE);
            stream.newLineAtOffset(MARGIN, y);
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) stream.newLineAtOffset(columnWidth, 0);
                stream.showText(truncate(headers.get(i), columnWidth));
            }
            stream.endText();
            y -= LEADING;
        }

        void tableRow(List<Object> cells, float columnWidth, PDFont font, List<String> headers) throws IOException {
            if (y < MARGIN + LEADING) newPage();
            stream.beginText();
            stream.setFont(font, FONT_SIZE);
            stream.newLineAtOffset(MARGIN, y);
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) stream.newLineAtOffset(columnWidth, 0);
                stream.showText(truncate(String.valueOf(cells.get(i)), columnWidth));
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
         * outside it is dropped rather than left to throw mid-export. */
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
