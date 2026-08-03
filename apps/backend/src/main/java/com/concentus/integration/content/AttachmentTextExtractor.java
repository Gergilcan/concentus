package com.concentus.integration.content;

/**
 * Reads the text out of one kind of attachment.
 *
 * <p>An interface with per-format implementations, registered as beans and selected by
 * {@link AttachmentExtractionService} — the same shape as the LLM providers and execution backends
 * in this project. Adding a format is a new bean, not an edit to a switch statement, and a format
 * whose dependency is missing simply reports itself unavailable instead of breaking startup.
 */
public interface AttachmentTextExtractor {

    /** Stable id, used in logs and in the extraction report. */
    String id();

    /** Whether this extractor handles the given sniffed type. */
    boolean supports(DetectedType type);

    /**
     * Whether it can run right now. OCR is the case this exists for: it needs a native library
     * that a container may not have, and the workflow must degrade rather than fail.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Extracts text.
     *
     * @param bytes    the file; already size-checked by {@link AttachmentPolicy}
     * @param filename the sender-supplied name, for logging only — never for routing
     * @return the text, or an empty string when there is none to find
     * @throws Exception on a malformed file; the caller records it and carries on with the rest
     */
    String extract(byte[] bytes, String filename) throws Exception;
}
