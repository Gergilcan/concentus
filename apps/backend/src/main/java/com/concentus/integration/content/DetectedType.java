package com.concentus.integration.content;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a byte array actually is, decided from its contents.
 *
 * <p>The filename and the {@code Content-Type} on an attachment are written by the sender. An
 * unauthenticated stranger controls both, so neither is evidence: {@code invoice.pdf} can be a
 * Windows executable and {@code application/pdf} can be a macro-enabled workbook. Every routing
 * decision downstream is made from the magic bytes read here, and the declared name is used only
 * to break ties between formats that genuinely share a signature.
 */
public enum DetectedType {

    PDF, DOCX, XLSX, PPTX, LEGACY_OFFICE, IMAGE, TEXT, CSV, ARCHIVE, EXECUTABLE, UNKNOWN;

    /** Anything the extractors can read. */
    public boolean isExtractable() {
        return this == PDF || this == DOCX || this == XLSX || this == TEXT || this == CSV || this == IMAGE;
    }

    /**
     * Formats that are refused outright.
     *
     * <p>An executable or an archive is never a quote request. Nothing here would run them — no
     * code in this project executes an attachment, and no macro is ever evaluated — but accepting
     * them means storing and passing around attacker-supplied binaries for no benefit, and an
     * archive additionally invites decompression-bomb handling that is not worth writing.
     */
    public boolean isRejected() {
        return this == EXECUTABLE || this == ARCHIVE;
    }

    /**
     * Sniffs the type from the leading bytes, using {@code declaredName} only to separate formats
     * whose containers are identical.
     */
    public static DetectedType detect(byte[] bytes, String declaredName) {
        if (bytes == null || bytes.length < 4) return UNKNOWN;
        String name = declaredName == null ? "" : declaredName.toLowerCase(Locale.ROOT);

        if (starts(bytes, 0x25, 0x50, 0x44, 0x46)) return PDF;                 // %PDF
        if (starts(bytes, 0x4D, 0x5A)) return EXECUTABLE;                      // MZ — PE/DOS
        if (starts(bytes, 0x7F, 0x45, 0x4C, 0x46)) return EXECUTABLE;          // ELF
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) return IMAGE;                     // JPEG
        if (starts(bytes, 0x89, 0x50, 0x4E, 0x47)) return IMAGE;               // PNG
        if (starts(bytes, 0x47, 0x49, 0x46, 0x38)) return IMAGE;               // GIF
        if (starts(bytes, 0x42, 0x4D)) return IMAGE;                           // BMP
        if (starts(bytes, 0x49, 0x49, 0x2A, 0x00) || starts(bytes, 0x4D, 0x4D, 0x00, 0x2A)) return IMAGE; // TIFF
        // Legacy OLE2 container: .doc, .xls, .ppt and .msg all share it.
        if (starts(bytes, 0xD0, 0xCF, 0x11, 0xE0)) return LEGACY_OFFICE;

        // OOXML formats are ZIP containers, and so is a plain .zip. The distinction has to come
        // from what is inside, with the extension only as a hint of what to expect.
        if (starts(bytes, 0x50, 0x4B, 0x03, 0x04) || starts(bytes, 0x50, 0x4B, 0x05, 0x06)) {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            // A macro-enabled document is refused whatever it claims to be: its whole point is
            // carrying code, and no legitimate quote request needs one.
            if (head.contains("vbaProject.bin") || name.endsWith(".docm") || name.endsWith(".xlsm")
                    || name.endsWith(".pptm")) {
                return EXECUTABLE;
            }
            if (head.contains("word/") || name.endsWith(".docx")) return DOCX;
            if (head.contains("xl/") || name.endsWith(".xlsx")) return XLSX;
            if (head.contains("ppt/") || name.endsWith(".pptx")) return PPTX;
            return ARCHIVE;
        }

        if (looksLikeText(bytes)) {
            return name.endsWith(".csv") || looksLikeCsv(bytes) ? CSV : TEXT;
        }
        return UNKNOWN;
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    /** Text if the sample decodes without control characters other than the usual whitespace. */
    private static boolean looksLikeText(byte[] bytes) {
        int limit = Math.min(bytes.length, 2048);
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return false; // a NUL byte means binary
            if (b >= 0x20 || b == '\n' || b == '\r' || b == '\t') printable++;
        }
        return printable > limit * 0.9;
    }

    private static boolean looksLikeCsv(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.UTF_8);
        int firstLineEnd = head.indexOf('\n');
        String firstLine = firstLineEnd > 0 ? head.substring(0, firstLineEnd) : head;
        return firstLine.chars().filter(c -> c == ';' || c == ',').count() >= 2;
    }
}
