package com.concentus.mail;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;

import java.util.Map;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;

/**
 * How a mail-triggered flow finds the messages it should run on, read from its {@code input} node.
 *
 * <p>IMAP rather than SMTP, and the difference is not cosmetic. SMTP is a transport: it hands over
 * a message and forgets it. Folders, flags and read state live in the mail <em>store</em>, so
 * "when a flagged mail lands in Presupuestos" is only expressible over IMAP — and so is moving the
 * message to Procesados afterwards.
 *
 * <p>Every condition is optional. Blank means "don't filter on this", so a node with only a folder
 * set matches everything in that folder.
 *
 * @param credentialId the id of a stored credential, never the password itself. The node holds a
 *                     reference so a flow can be exported, duplicated or version-snapshotted
 *                     without carrying a secret — which matters here because every save snapshots
 *                     the flow's JSON, and a secret on the node would fan out into every revision
 */
public record MailTriggerSpec(String host, int port, boolean ssl, String username, String credentialId,
                              String authMode, String tenantId, String clientId,
                              String folder, String from, String subjectContains, String bodyContains,
                              boolean unseenOnly, boolean flaggedOnly, boolean withAttachmentsOnly,
                              long pollSeconds, int maxPerPoll,
                              String moveToFolder, boolean markSeen, boolean flagAfter) {

    /** IMAP over TLS. Plain 143 is offered but never the default. */
    public static final int DEFAULT_SSL_PORT = 993;
    public static final int DEFAULT_PLAIN_PORT = 143;

    public static MailTriggerSpec from(FlowGraph flow) {
        for (FlowNode n : flow.nodesOrEmpty()) {
            if ("input".equalsIgnoreCase(n.type())) {
                return from(n.dataOrEmpty());
            }
        }
        return from(Map.of());
    }

    static MailTriggerSpec from(Map<String, Object> d) {
        boolean ssl = bool(d, "mailSsl", true);
        int port = (int) lng(d, "mailPort", ssl ? DEFAULT_SSL_PORT : DEFAULT_PLAIN_PORT);
        return new MailTriggerSpec(
                str(d, "mailHost", ""),
                port,
                ssl,
                str(d, "mailUsername", ""),
                str(d, "mailCredentialId", ""),
                // Microsoft 365 refuses a password over IMAP at all — Basic auth is retired there
                // — so the mode is per node rather than inferred from the host.
                str(d, "mailAuthMode", "password"),
                str(d, "mailTenantId", ""),
                str(d, "mailClientId", ""),
                str(d, "mailFolder", "INBOX"),
                str(d, "mailFrom", ""),
                str(d, "mailSubjectContains", ""),
                str(d, "mailBodyContains", ""),
                bool(d, "mailUnseenOnly", true),
                bool(d, "mailFlaggedOnly", false),
                bool(d, "mailWithAttachmentsOnly", false),
                // A minute is frequent enough for mail and gentle enough that a provider will not
                // start throttling; anything under 15s is almost certainly a mistake.
                Math.max(15, lng(d, "mailPollSeconds", 60)),
                // Caps how much one poll can start at once. A folder with a thousand unread
                // messages should not launch a thousand agent runs on the first tick.
                (int) Math.max(1, lng(d, "mailMaxPerPoll", 10)),
                str(d, "mailMoveToFolder", ""),
                bool(d, "mailMarkSeen", true),
                bool(d, "mailFlagAfter", false));
    }

    /** True when this node signs in with Microsoft OAuth rather than a password. */
    public boolean usesMicrosoftOAuth() {
        return "microsoft-oauth".equalsIgnoreCase(authMode);
    }

    /**
     * Enough to attempt a connection. Conditions may all be blank; these may not.
     *
     * <p>Tenant and client id are deliberately not required here. One Entra app registration
     * serves every mailbox in a deployment, so they are configuration rather than per-node data
     * and are normally left blank — a node that overrode nothing would otherwise report itself
     * unconfigured while being perfectly fine.
     */
    public boolean isConfigured() {
        return !host.isBlank() && !username.isBlank() && !credentialId.isBlank() && !folder.isBlank();
    }

    /** What is missing, for an error an operator can act on rather than a bare connection failure. */
    public String missingFields() {
        StringBuilder missing = new StringBuilder();
        if (host.isBlank()) missing.append("host, ");
        if (username.isBlank()) missing.append("username, ");
        if (credentialId.isBlank()) missing.append(usesMicrosoftOAuth() ? "Microsoft sign-in, " : "credential, ");
        if (folder.isBlank()) missing.append("folder, ");
        return missing.isEmpty() ? "" : missing.substring(0, missing.length() - 2);
    }

    public boolean movesProcessedMail() {
        return !moveToFolder.isBlank();
    }
}
