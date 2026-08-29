package com.concentus.mail;

import java.util.List;
import java.util.Locale;

/**
 * One message, read out of IMAP and detached from the connection.
 *
 * <p>Detached on purpose: Jakarta Mail's {@code Message} is a live handle onto an open folder, and
 * touching one after the folder closes throws. Everything a run needs is copied out while the
 * connection is held, so the poller can close the store immediately and the agent can take as long
 * as it likes.
 *
 * @param messageId the RFC 5322 {@code Message-ID}. Stable across folder moves — which this
 *                  trigger performs — so it, not the IMAP UID, is what idempotency keys on
 * @param uid       the IMAP UID, valid only within this folder and only while UIDVALIDITY holds
 */
public record FetchedMail(String messageId, long uid, String subject, String from, String to,
                          long receivedAt, boolean seen, boolean flagged,
                          String bodyText, List<Attachment> attachments) {

    /** An attachment's bytes, already size-checked, with the name the sender chose. */
    public record Attachment(String filename, String declaredContentType, byte[] bytes) {
    }

    public List<Attachment> attachmentsOrEmpty() {
        return attachments == null ? List.of() : attachments;
    }

    public boolean hasAttachments() {
        return !attachmentsOrEmpty().isEmpty();
    }

    /** A stable identity even for the rare message that arrives with no {@code Message-ID}. */
    public String identity() {
        return messageId != null && !messageId.isBlank() ? normalize(messageId) : "uid:" + uid;
    }

    /**
     * A {@code Message-ID} as it is compared: angle brackets off, lower-cased. Servers and clients
     * differ on both, and the same message must read as itself whichever spelling comes back.
     */
    static String normalize(String messageId) {
        return messageId == null ? ""
                : messageId.trim().replaceAll("^<|>$", "").toLowerCase(Locale.ROOT);
    }
}
