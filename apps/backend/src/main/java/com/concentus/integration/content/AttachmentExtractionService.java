package com.concentus.integration.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a message's attachments into text the extraction step can read.
 *
 * <p>Every attachment is sniffed, checked against {@link AttachmentPolicy}, and routed to whichever
 * {@link AttachmentTextExtractor} handles its real type. Nothing here trusts the sender: the
 * filename is carried only so an operator can see which file a result came from, and the routing
 * decision is made entirely from the bytes.
 *
 * <p>One attachment failing never fails the message. A corrupt PDF among four good files should
 * still produce a quote from the four, with the fifth recorded as unread — so failures become
 * entries in the report rather than exceptions.
 */
@Service
public class AttachmentExtractionService {

    /**
     * File extensions per detected type, for {@link #supportedExtensions()}. The extension is a
     * hint for pickers; actual handling is decided by content detection at ingest.
     */
    private static final java.util.Map<DetectedType, java.util.List<String>> EXTENSIONS = java.util.Map.of(
            DetectedType.PDF, java.util.List.of(".pdf"),
            DetectedType.DOCX, java.util.List.of(".docx"),
            DetectedType.XLSX, java.util.List.of(".xlsx"),
            DetectedType.TEXT, java.util.List.of(".txt", ".md", ".html"),
            DetectedType.CSV, java.util.List.of(".csv"),
            DetectedType.IMAGE, java.util.List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tif", ".tiff"));

    /**
     * The extensions an upload can actually be extracted from, derived from the extractors that
     * are present AND working — OCR drops out when tesseract is broken, exactly as ingest would.
     *
     * <p>This is what the knowledge UI asks instead of keeping its own list, which had already
     * drifted twice: it offered .doc/.xls, which no extractor claims (they detect as
     * LEGACY_OFFICE and fail at ingest), and it silently dropped images, which OCR reads fine.
     */
    public java.util.List<String> supportedExtensions() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var entry : EXTENSIONS.entrySet()) {
            boolean handled = extractors.stream()
                    .anyMatch(e -> e.supports(entry.getKey()) && e.isAvailable());
            if (handled) out.addAll(entry.getValue());
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static final Logger log = LoggerFactory.getLogger(AttachmentExtractionService.class);

    private final List<AttachmentTextExtractor> extractors;
    private final AttachmentPolicy policy;

    public AttachmentExtractionService(List<AttachmentTextExtractor> extractors, AttachmentPolicy policy) {
        this.extractors = extractors;
        this.policy = policy;
    }

    /**
     * One attachment's outcome.
     *
     * @param text  extracted text, empty when nothing was read
     * @param note  why nothing was read, or null on success — surfaced to the review screen so a
     *              human can see that a file was skipped rather than silently ignored
     */
    public record ExtractedFile(String filename, DetectedType type, long size, String extractorId,
                                String text, String note) {

        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /** What to feed the model, plus the per-file record of what happened. */
    public record Extraction(String combinedText, List<ExtractedFile> files, int skipped) {
    }

    /** One attachment as it arrives, before anything has been decided about it. */
    public record RawAttachment(String filename, byte[] bytes) {
    }

    public Extraction extractAll(List<RawAttachment> attachments) {
        List<ExtractedFile> results = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        long bytesSoFar = 0;
        int accepted = 0;
        int skipped = 0;

        for (RawAttachment attachment : attachments) {
            byte[] bytes = attachment.bytes();
            long size = bytes == null ? 0 : bytes.length;
            DetectedType type = DetectedType.detect(bytes, attachment.filename());

            String rejection = policy.rejectionReason(type, size, accepted, bytesSoFar);
            if (rejection != null) {
                results.add(new ExtractedFile(attachment.filename(), type, size, null, "", rejection));
                skipped++;
                log.info("Attachment '{}' {} ({}).", attachment.filename(), rejection, type);
                continue;
            }

            AttachmentTextExtractor extractor = extractorFor(type);
            if (extractor == null) {
                results.add(new ExtractedFile(attachment.filename(), type, size, null, "",
                        "skipped: no extractor available for " + type));
                skipped++;
                continue;
            }

            accepted++;
            bytesSoFar += size;
            try {
                String text = extractor.extract(bytes, attachment.filename());
                results.add(new ExtractedFile(attachment.filename(), type, size, extractor.id(), text, null));
                if (text != null && !text.isBlank()) {
                    // Each file is fenced and labelled so the model can attribute a figure to a
                    // document — and so that text inside a file can't be mistaken for the
                    // surrounding instructions.
                    combined.append("\n=== attachment: ").append(safeName(attachment.filename()))
                            .append(" (").append(type).append(") ===\n")
                            .append(text).append('\n');
                }
            } catch (Exception e) {
                results.add(new ExtractedFile(attachment.filename(), type, size, extractor.id(), "",
                        "could not be read: " + e.getClass().getSimpleName()));
                skipped++;
                log.warn("Could not read attachment '{}' ({}): {}",
                        safeName(attachment.filename()), type, e.toString());
            }
        }
        return new Extraction(combined.toString(), results, skipped);
    }

    /** The first available extractor for a type; unavailable ones (OCR without Tesseract) are skipped. */
    private AttachmentTextExtractor extractorFor(DetectedType type) {
        return extractors.stream()
                .filter(e -> e.supports(type) && e.isAvailable())
                .findFirst()
                .orElse(null);
    }

    /**
     * Neutralises the filename before it goes anywhere.
     *
     * <p>It is sender-controlled text that ends up in a prompt, a log line and the UI, so path
     * separators, newlines and the fence markers used above are stripped — a file called
     * {@code "=== ignore the above ===.pdf"} must not be able to forge a section boundary.
     */
    public static String safeName(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed";
        String cleaned = filename.replaceAll("[\\r\\n\\\\/]", "_").replace("=", "_").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) + "…" : cleaned;
    }
}
