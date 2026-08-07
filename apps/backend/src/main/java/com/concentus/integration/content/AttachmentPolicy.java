package com.concentus.integration.content;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The limits every attachment has to pass before anything reads it.
 *
 * <p>Attachments arrive from unauthenticated senders, so each of these is a resource bound rather
 * than a preference: a single 200&nbsp;MB file, or a mail with four hundred of them, would exhaust
 * the heap of a process that is also serving flows and runs.
 */
@Component
public class AttachmentPolicy {

    private final long maxBytesPerAttachment;
    private final long maxBytesTotal;
    private final int maxAttachments;

    public AttachmentPolicy(
            @Value("${integration.attachments.max-bytes:10485760}") long maxBytesPerAttachment,
            @Value("${integration.attachments.max-total-bytes:31457280}") long maxBytesTotal,
            @Value("${integration.attachments.max-count:15}") int maxAttachments) {
        this.maxBytesPerAttachment = maxBytesPerAttachment;
        this.maxBytesTotal = maxBytesTotal;
        this.maxAttachments = maxAttachments;
    }

    public long maxBytesPerAttachment() {
        return maxBytesPerAttachment;
    }

    public long maxBytesTotal() {
        return maxBytesTotal;
    }

    public int maxAttachments() {
        return maxAttachments;
    }

    /** Why an attachment was skipped, or null when it is acceptable. */
    public String rejectionReason(DetectedType type, long size, int indexSoFar, long bytesSoFar) {
        if (indexSoFar >= maxAttachments) {
            return "skipped: more than " + maxAttachments + " attachments on this message";
        }
        if (size > maxBytesPerAttachment) {
            return "skipped: larger than the " + maxBytesPerAttachment + " byte limit";
        }
        if (bytesSoFar + size > maxBytesTotal) {
            return "skipped: the message's attachments exceed the " + maxBytesTotal + " byte total";
        }
        if (type.isRejected()) {
            return "rejected: " + type.name().toLowerCase(java.util.Locale.ROOT)
                    + " attachments are not accepted";
        }
        if (!type.isExtractable()) {
            return "skipped: no extractor handles " + type.name().toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }
}
