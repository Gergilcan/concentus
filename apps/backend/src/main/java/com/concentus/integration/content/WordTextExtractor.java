package com.concentus.integration.content;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Word documents (.docx), via POI.
 *
 * <p>Paragraphs and tables are walked separately rather than through POI's convenience extractor,
 * because a quote request in Word is nearly always a table — and the convenience extractor
 * flattens it, losing which price belongs to which line. Tables come out pipe-delimited, matching
 * {@link HtmlToText} so the model sees one consistent shape whatever the source document was.
 *
 * <p>Only the OOXML format is handled. The older binary {@code .doc} needs {@code poi-scratchpad},
 * a further dependency for a format that is rare in current correspondence; such a file is
 * detected as {@link DetectedType#LEGACY_OFFICE} and reported as unread rather than mis-parsed.
 */
@Component
public class WordTextExtractor implements AttachmentTextExtractor {

    private static final int MAX_CHARS = 150_000;

    @Override
    public String id() {
        return "docx";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.DOCX;
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        StringBuilder out = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) out.append(text.trim()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                out.append('\n');
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText() == null ? "" : cell.getText().replace("|", "/").trim());
                    }
                    out.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
                out.append('\n');
            }
        }
        String text = out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return ExtractedText.clamp(text, MAX_CHARS);
    }
}
