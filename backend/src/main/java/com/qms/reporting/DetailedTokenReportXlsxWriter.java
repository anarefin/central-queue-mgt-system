package com.qms.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * XLSX export (FR-RPT-003): the same header block, blank row, column header row and data rows as the CSV export,
 * one sheet, raw values (a number stays a number cell, never a formatted string). {@link SXSSFWorkbook} is POI's own
 * streaming writer — it keeps only a window of rows in memory and spills the rest to a temp file as they are
 * written, so a 1,000,000-row export (NFR-PERF-006) stays in bounded memory the same way the CSV writer's one-row-
 * at-a-time stream does.
 */
@Component
class DetailedTokenReportXlsxWriter {

    /** Rows kept in memory before POI flushes older ones to its own temp file. */
    private static final int WINDOW_SIZE = 1_000;

    long write(OutputStream out, List<String[]> headerLines, List<String> columnHeaders, ReportRowSource source, String language) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE)) {
            SXSSFSheet sheet = workbook.createSheet("Report");
            int r = 0;
            for (String[] line : headerLines) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(line[0]);
                row.createCell(1).setCellValue(line[1]);
            }
            r++; // blank row between the header block and the table, matching the CSV export's own layout
            Row header = sheet.createRow(r++);
            for (int c = 0; c < columnHeaders.size(); c++) header.createCell(c).setCellValue(columnHeaders.get(c));

            AtomicLong count = new AtomicLong();
            int[] nextRow = {r};
            source.forEach(dataRow -> {
                Row row = sheet.createRow(nextRow[0]++);
                List<Object> cells = DetailedTokenReportColumns.cells(dataRow, language);
                for (int c = 0; c < cells.size(); c++) setCell(row.createCell(c), cells.get(c));
                count.incrementAndGet();
            });

            workbook.write(out);
            workbook.dispose(); // removes SXSSFWorkbook's own backing temp file, now that it has been written out
            return count.get();
        }
    }

    private static void setCell(Cell cell, Object value) {
        if (value instanceof Integer i) {
            cell.setCellValue(i);
        } else if (value instanceof Long l) {
            cell.setCellValue(l);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }
}
