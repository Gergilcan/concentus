package com.concentus.mail;

import com.concentus.model.FlowNode;

import java.util.Map;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;

/**
 * A Send mail node, read from the canvas: who gets the mail, and through which account.
 *
 * <p>SMTP, where the trigger is IMAP, and for the mirror-image reason: submitting a message is
 * all this node does, and SMTP is the protocol that does exactly that and nothing else. The
 * connection details live on the node and only the password in a stored credential — the shape
 * the IMAP trigger settled on, because every save snapshots the flow's JSON and a secret on the
 * node would fan out into every revision.
 *
 * @param subject      may carry {@code {{flow}}} and {@code {{status}}}, filled in when the mail
 *                     is sent — the flow's name, and which of the block's outputs fired
 * @param credentialId the id of a stored credential, never the password itself
 */
public record MailHandOffSpec(String nodeId, String label, String to, String subject,
                              String smtpHost, int smtpPort, boolean smtpStarttls,
                              String smtpUsername, String from, String credentialId) {

    /** Submission with STARTTLS, which is what nearly every provider documents. */
    public static final int DEFAULT_STARTTLS_PORT = 587;
    /** Implicit TLS, for providers that still prefer it. */
    public static final int DEFAULT_TLS_PORT = 465;

    public static MailHandOffSpec from(FlowNode node) {
        Map<String, Object> d = node.dataOrEmpty();
        boolean starttls = bool(d, "smtpStarttls", true);
        return new MailHandOffSpec(
                node.id(),
                str(d, "label", node.id()),
                str(d, "to", "").trim(),
                str(d, "subject", "").trim(),
                str(d, "smtpHost", "").trim(),
                (int) lng(d, "smtpPort", starttls ? DEFAULT_STARTTLS_PORT : DEFAULT_TLS_PORT),
                starttls,
                str(d, "smtpUsername", "").trim(),
                str(d, "from", "").trim(),
                str(d, "credentialId", "").trim());
    }

    /** Enough to attempt a submission. */
    public boolean isConfigured() {
        return !to.isBlank() && !smtpHost.isBlank() && !credentialId.isBlank() && !sender().isBlank();
    }

    /** What is missing, for a log line an operator can act on rather than a bare refusal. */
    public String missingFields() {
        StringBuilder missing = new StringBuilder();
        if (to.isBlank()) missing.append("recipient, ");
        if (smtpHost.isBlank()) missing.append("SMTP host, ");
        if (sender().isBlank()) missing.append("from address, ");
        if (credentialId.isBlank()) missing.append("credential, ");
        return missing.isEmpty() ? "" : missing.substring(0, missing.length() - 2);
    }

    /**
     * The address the mail goes out as. The login when no From was given, because for nearly
     * every provider they are the same mailbox and asking for it twice is a form field too many.
     */
    public String sender() {
        return from.isBlank() ? smtpUsername : from;
    }

    /** The account with its password, for the length of one submission. */
    public MailAccount account(String password) {
        String login = smtpUsername.isBlank() ? from : smtpUsername;
        return new MailAccount(smtpHost, smtpPort, smtpStarttls, login, sender(), password);
    }
}
