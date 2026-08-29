package com.concentus.mail;

import com.concentus.integration.Redact;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Reads and files mail over IMAP.
 *
 * <p>Conditions are pushed into an IMAP {@code SEARCH} wherever the protocol supports it, so the
 * server does the filtering and only matching messages cross the network. The two that IMAP cannot
 * express — "has attachments", and the message-id exclusion for idempotency — are applied locally
 * afterwards, which is stated here because the difference matters for how much a poll costs on a
 * large folder.
 *
 * <p>Each call opens a connection and closes it in a finally block. An IMAP connection held open
 * across an agent run — which can take minutes — is a connection the server will drop from under
 * us, so the trade is a little reconnect cost for not having to handle that.
 */
@Component
public class ImapMailReader {

    private static final Logger log = LoggerFactory.getLogger(ImapMailReader.class);

    private final long maxAttachmentBytes;
    private final int maxAttachments;
    private final int connectTimeoutMs;

    public ImapMailReader(
            @Value("${integration.attachments.max-bytes:10485760}") long maxAttachmentBytes,
            @Value("${integration.attachments.max-count:15}") int maxAttachments,
            @Value("${mail.connect-timeout-ms:20000}") int connectTimeoutMs) {
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachments = maxAttachments;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** A mail operation that failed. Always carries a message an operator can act on. */
    public static class MailException extends RuntimeException {
        public MailException(String message, Throwable cause) {
            super(message, cause);
        }

        public MailException(String message) {
            super(message);
        }
    }

    /**
     * One poll's worth of results, with enough context to explain an empty one.
     *
     * <p>"Nothing to do" has three quite different causes — an empty folder, conditions that match
     * nothing, and everything already processed — and they are indistinguishable from the returned
     * list alone. Someone asking "is this working?" needs to tell them apart.
     *
     * @param mails      messages that matched and are not already processed
     * @param matchedSearch how many the server's SEARCH returned, before local filtering
     * @param inFolder   total messages in the folder
     */
    public record Fetched(List<FetchedMail> mails, int matchedSearch, int inFolder) {
    }

    /**
     * Fetches messages matching the spec's conditions.
     *
     * @param excludeIdentities message identities already processed, filtered out locally because
     *                          IMAP has no way to express "not these Message-IDs"
     * @param limit             hard cap on how many are returned
     */
    public Fetched fetch(MailTriggerSpec spec, MailAuthProvider.MailAuth auth,
                         Set<String> excludeIdentities, int limit) {
        Store store = null;
        Folder folder = null;
        try {
            store = connect(spec, auth);
            folder = store.getFolder(spec.folder());
            if (!folder.exists()) {
                throw new MailException("No IMAP folder named '" + spec.folder() + "' exists.");
            }
            // READ_WRITE because the poller sets \Seen and moves messages; READ_ONLY would make
            // every message match again on the next tick.
            folder.open(Folder.READ_WRITE);

            // Not a lambda over `folder`: it is reassigned in this method, so capturing it would
            // not compile — and an if reads more plainly here anyway.
            Optional<SearchTerm> term = searchTerm(spec);
            Message[] found = term.isPresent() ? search(folder, term.get()) : messages(folder);
            int inFolder = folder.getMessageCount();

            List<FetchedMail> out = new ArrayList<>();
            // Newest first: on a backlog, the mail someone is waiting on is the recent one.
            for (int i = found.length - 1; i >= 0 && out.size() < limit; i--) {
                FetchedMail mail = read(folder, found[i]);
                if (mail == null) continue;
                if (excludeIdentities.contains(mail.identity())) continue;
                if (spec.withAttachmentsOnly() && !mail.hasAttachments()) continue;
                out.add(mail);
            }
            return new Fetched(out, found.length, inFolder);
        } catch (MessagingException e) {
            throw new MailException("IMAP fetch from " + spec.host() + " failed: "
                    + Redact.secrets(String.valueOf(e.getMessage())), e);
        } finally {
            close(folder, store);
        }
    }

    /**
     * Files a message once its run has been started: flags, read state and folder move.
     *
     * <p>Reopened in a fresh connection rather than kept from the fetch, and located by
     * {@code Message-ID} rather than by UID, because a UID is only meaningful while UIDVALIDITY
     * holds and the message may have been touched by a human in between.
     */
    public void fileMessage(MailTriggerSpec spec, MailAuthProvider.MailAuth auth, String messageId) {
        Store store = null;
        Folder folder = null;
        try {
            store = connect(spec, auth);
            folder = store.getFolder(spec.folder());
            folder.open(Folder.READ_WRITE);

            Message message = findByMessageId(folder, messageId);
            if (message == null) {
                // Someone moved or deleted it while the agent was running. Not an error.
                log.debug("Message {} is no longer in {}; nothing to file.",
                        Redact.personalData(messageId), spec.folder());
                return;
            }
            if (spec.markSeen()) message.setFlag(Flags.Flag.SEEN, true);
            if (spec.flagAfter()) message.setFlag(Flags.Flag.FLAGGED, true);

            if (spec.movesProcessedMail()) {
                Folder destination = store.getFolder(spec.moveToFolder());
                if (!destination.exists()) {
                    log.warn("Cannot move mail: no IMAP folder named '{}'.", spec.moveToFolder());
                } else {
                    // copy + delete + expunge: the classic move, used because IMAP's MOVE
                    // extension is not universally available and Jakarta Mail exposes no
                    // portable wrapper for it.
                    folder.copyMessages(new Message[]{message}, destination);
                    message.setFlag(Flags.Flag.DELETED, true);
                    folder.expunge();
                }
            }
        } catch (MessagingException e) {
            // Filing failures must not fail the run: it has already started, and the mail being
            // in the wrong folder is far less bad than processing it twice because we threw.
            log.warn("Could not file message in {}: {}", spec.folder(),
                    Redact.secrets(String.valueOf(e.getMessage())));
        } finally {
            close(folder, store);
        }
    }

    // ---- conditions ----

    /**
     * Builds the IMAP SEARCH from the configured conditions.
     *
     * <p>Empty when nothing is set, in which case the caller lists the folder instead — a SEARCH
     * with no terms is not something to construct.
     */
    private static Optional<SearchTerm> searchTerm(MailTriggerSpec spec) {
        List<SearchTerm> terms = new ArrayList<>();
        if (spec.unseenOnly()) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        if (spec.flaggedOnly()) terms.add(new FlagTerm(new Flags(Flags.Flag.FLAGGED), true));
        if (!spec.from().isBlank()) terms.add(new FromStringTerm(spec.from()));
        if (!spec.subjectContains().isBlank()) terms.add(new SubjectTerm(spec.subjectContains()));
        if (!spec.bodyContains().isBlank()) terms.add(new BodyTerm(spec.bodyContains()));

        if (terms.isEmpty()) return Optional.empty();
        return Optional.of(terms.size() == 1
                ? terms.get(0)
                : new AndTerm(terms.toArray(new SearchTerm[0])));
    }

    private static Message[] search(Folder folder, SearchTerm term) {
        try {
            return folder.search(term);
        } catch (MessagingException e) {
            throw new MailException("IMAP SEARCH failed: " + e.getMessage(), e);
        }
    }

    private static Message[] messages(Folder folder) {
        try {
            return folder.getMessages();
        } catch (MessagingException e) {
            throw new MailException("Could not list the folder: " + e.getMessage(), e);
        }
    }

    private static Message findByMessageId(Folder folder, String messageId) throws MessagingException {
        for (Message m : folder.getMessages()) {
            if (m instanceof MimeMessage mime) {
                String id = mime.getMessageID();
                if (id != null && FetchedMail.normalize(id).equals(FetchedMail.normalize(messageId))) {
                    return m;
                }
            }
        }
        return null;
    }

    // ---- reading ----

    private FetchedMail read(Folder folder, Message message) {
        try {
            MimeMessage mime = message instanceof MimeMessage m ? m : null;
            long uid = folder instanceof UIDFolder uidFolder ? uidFolder.getUID(message) : -1;

            StringBuilder body = new StringBuilder();
            List<FetchedMail.Attachment> attachments = new ArrayList<>();
            walk(message, body, attachments);

            return new FetchedMail(
                    mime == null ? null : mime.getMessageID(),
                    uid,
                    message.getSubject(),
                    addresses(message.getFrom()),
                    addresses(message.getRecipients(Message.RecipientType.TO)),
                    message.getReceivedDate() == null
                            ? System.currentTimeMillis() : message.getReceivedDate().getTime(),
                    message.isSet(Flags.Flag.SEEN),
                    message.isSet(Flags.Flag.FLAGGED),
                    body.toString(),
                    attachments);
        } catch (Exception e) {
            // One unreadable message must not stop the poll from handling the rest.
            log.warn("Skipping a message that could not be read: {}", e.toString());
            return null;
        }
    }

    /**
     * Walks a MIME tree, collecting text and attachment bytes.
     *
     * <p>Recursive because {@code multipart/mixed} wrapping {@code multipart/alternative} is the
     * normal shape of a real message with an attachment, and a flat scan of the top level would
     * find the attachment but no body.
     */
    private void walk(Part part, StringBuilder body, List<FetchedMail.Attachment> attachments)
            throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), body, attachments);
            }
            return;
        }

        String disposition = part.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null && !part.getFileName().isBlank());

        if (isAttachment) {
            if (attachments.size() >= maxAttachments) return;
            byte[] bytes = readCapped(part);
            if (bytes != null) {
                attachments.add(new FetchedMail.Attachment(
                        part.getFileName(), part.getContentType(), bytes));
            }
            return;
        }

        if (part.isMimeType("text/*")) {
            Object content = part.getContent();
            if (content != null) {
                // HTML is kept as-is here; converting it is the renderer's job, so this class
                // stays about IMAP and nothing else.
                if (!body.isEmpty()) body.append('\n');
                body.append(String.valueOf(content));
            }
        }
    }

    /**
     * Reads a part's bytes, stopping at the configured ceiling.
     *
     * <p>Streamed with a cap rather than {@code readAllBytes}: attachments come from
     * unauthenticated senders, and a single large one would otherwise be enough to exhaust the
     * heap of a process that is also serving flows and runs.
     */
    private byte[] readCapped(Part part) throws Exception {
        try (InputStream in = part.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxAttachmentBytes) {
                    log.info("Skipping attachment '{}': larger than the {}-byte limit.",
                            part.getFileName(), maxAttachmentBytes);
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        StringBuilder out = new StringBuilder();
        for (Address a : addresses) {
            if (!out.isEmpty()) out.append(", ");
            out.append(a.toString());
        }
        return out.toString();
    }

    // ---- connection ----

    private Store connect(MailTriggerSpec spec, MailAuthProvider.MailAuth auth) throws MessagingException {
        Properties props = new Properties();
        String protocol = spec.ssl() ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", spec.host());
        props.put("mail." + protocol + ".port", String.valueOf(spec.port()));
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put("mail." + protocol + ".timeout", String.valueOf(connectTimeoutMs));
        if (spec.ssl()) {
            props.put("mail.imaps.ssl.enable", "true");
        } else {
            // Plain 143 is allowed but STARTTLS is required, so a password is never sent in the
            // clear just because someone left the SSL box unticked.
            props.put("mail.imap.starttls.enable", "true");
            props.put("mail.imap.starttls.required", "true");
        }
        if (auth.useXoauth2()) {
            // Microsoft 365 retired Basic authentication for IMAP, so a password is refused before
            // it is even evaluated — which is why a correct one returns "AUTHENTICATE failed".
            // The access token travels where the password would, under the XOAUTH2 mechanism.
            props.put("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
            // Belt and braces: without these, a failed XOAUTH2 can fall back to sending the token
            // as a plaintext password, which would neither work nor be a good thing to attempt.
            props.put("mail." + protocol + ".auth.login.disable", "true");
            props.put("mail." + protocol + ".auth.plain.disable", "true");
        }
        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(spec.host(), spec.port(), spec.username(), auth.secret());
        return store;
    }

    private static void close(Folder folder, Store store) {
        try {
            // expunge=false: deletions are expunged explicitly where they are made, so closing
            // never silently destroys a message someone else flagged.
            if (folder != null && folder.isOpen()) folder.close(false);
        } catch (Exception ignored) {
            // Closing failures are not actionable and must not mask the real error.
        }
        try {
            if (store != null && store.isConnected()) store.close();
        } catch (Exception ignored) {
            // As above.
        }
    }
}
