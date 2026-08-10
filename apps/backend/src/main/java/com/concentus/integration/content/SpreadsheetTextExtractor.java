package com.concentus.integration.content;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel workbooks (.xlsx), via POI.
 *
 * <p>Rendered as one pipe-delimited table per sheet. Cell values go through {@link DataFormatter},
 * which returns what the sheet <em>displays</em> rather than the raw double — so a cell showing
 * "1.234,56 €" does not arrive as {@code 1234.56000000001}, and a date stays a date instead of
 * becoming a serial number.
 *
 * <p>Formulas are read as their last cached result and never evaluated. Evaluating them would mean
 * running expressions written by an unauthenticated sender, and the cached value is what the sender
 * saw anyway.
 */
@Component
public class SpreadsheetTextExtractor implements AttachmentTextExtractor {

    private static final int MAX_CHARS = 150_000;

    private final int maxRowsPerSheet;

    public SpreadsheetTextExtractor(
            @Value("${integration.attachments.max-spreadsheet-rows:2000}") int maxRowsPerSheet) {
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    @Override
    public String id() {
        return "xlsx";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.XLSX;
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        StringBuilder out = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                out.append("--- sheet: ").append(sheet.getSheetName()).append(" ---\n");
                int rows = 0;
                for (Row row : sheet) {
                    if (rows++ >= maxRowsPerSheet) {
                        out.append("…(stopped after ").append(maxRowsPerSheet).append(" rows)\n");
                        break;
                    }
                    List<String> values = new ArrayList<>();
                    boolean anyValue = false;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                        if (!value.isEmpty()) anyValue = true;
                        values.add(value.replace("|", "/"));
                    }
                    // Spreadsheets are full of blank spacer rows; emitting them would be noise the
                    // model has to read past.
                    if (anyValue) {
                        out.append("| ").append(String.join(" | ", values)).append(" |\n");
                    }
                }
                out.append('\n');
            }
        }
        String text = out.toString().trim();
        return ExtractedText.clamp(text, MAX_CHARS);
    }
}
