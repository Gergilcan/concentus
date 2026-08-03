package com.concentus.integration.content;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PDF text, via PDFBox.
 *
 * <p>A PDF that yields almost no text is usually a scan — a photograph of a page wrapped in a PDF
 * container. Those are common for quote requests forwarded from a customer's own printer, so when
 * the text layer is empty and OCR is available, the pages are rendered and read instead.
 */
@Component
public class PdfTextExtractor implements AttachmentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** Below this many characters, treat the document as having no usable text layer. */
    private static final int SCANNED_THRESHOLD = 40;
    private static final int MAX_CHARS = 150_000;

    private final ImageOcrExtractor ocr;
    private final int maxOcrPages;

    public PdfTextExtractor(ImageOcrExtractor ocr,
                            @Value("${integration.attachments.max-ocr-pages:10}") int maxOcrPages) {
        this.ocr = ocr;
        this.maxOcrPages = maxOcrPages;
    }

    @Override
    public String id() {
        return "pdf";
    }

    @Override
    public boolean supports(DetectedType type) {
        return type == DetectedType.PDF;
    }

    @Override
    public String extract(byte[] bytes, String filename) throws Exception {
        // Loader.loadPDF over the byte array, never a file path: nothing writes attachment bytes
        // to disk, so a malformed PDF can't leave anything behind.
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                // PDFBox opens some encrypted documents with an empty password. If text still
                // comes out, use it; if not, say so rather than returning a confusing blank.
                log.debug("PDF '{}' is encrypted; extracting what is readable.", filename);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (text != null && text.strip().length() >= SCANNED_THRESHOLD) {
                return truncate(text);
            }
            if (!ocr.isAvailable()) {
                log.info("PDF '{}' has no text layer and OCR is unavailable.", filename);
                return text == null ? "" : text;
            }
            log.info("PDF '{}' has no text layer; running OCR over up to {} page(s).", filename, maxOcrPages);
            return truncate(ocr.extractFromPdf(document, maxOcrPages));
        }
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n…(truncated)" : text;
    }
}
