package com.concentus.integration;

import java.util.regex.Pattern;

/**
 * Strips credentials and personal data out of anything on its way to a log line or an error
 * message stored in the database.
 *
 * <p>Integration code logs a lot of provider responses, and those responses carry bearer tokens,
 * refresh tokens, client secrets and — for a mail workflow — the customer's own address and phone
 * number. Redaction happens here, once, rather than being remembered at every call site.
 */
public final class Redact {

    /** JSON fields whose value is always a credential. */
    private static final Pattern SECRET_FIELDS = Pattern.compile(
            "(?i)\"(access_token|refresh_token|id_token|client_secret|clientState|client_assertion|"
                    + "password|apiKey|api_key|authorization)\"\\s*:\\s*\"[^\"]*\"");

    /** {@code Authorization: Bearer …} in a header dump, and bare JWTs pasted into a message. */
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/\\-]+=*");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9._\\-]{20,}");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private static final int MAX_LENGTH = 2000;

    private Redact() {
    }

    /** Removes credentials but keeps the message diagnosable. Use for provider error bodies. */
    public static String secrets(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = SECRET_FIELDS.matcher(text).replaceAll(m -> quotedFieldName(m.group()) + ":\"[redacted]\"");
        out = BEARER.matcher(out).replaceAll("Bearer [redacted]");
        out = JWT.matcher(out).replaceAll("[redacted-jwt]");
        return out.length() > MAX_LENGTH ? out.substring(0, MAX_LENGTH) + "…" : out;
    }

    /**
     * Everything {@link #secrets} removes, plus email addresses.
     *
     * <p>Used for anything derived from a message body: the audit trail records that a mail was
     * processed, not what the customer wrote in it.
     */
    public static String personalData(String text) {
        if (text == null || text.isEmpty()) return text;
        return EMAIL.matcher(secrets(text)).replaceAll(m -> email(m.group()));
    }

    /** Keeps an address recognisable to an operator without storing it: {@code a…a@example.com}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) return email;
        int at = email.indexOf('@');
        if (at <= 0) return "[redacted]";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "…" + domain;
        return local.charAt(0) + "…" + local.charAt(local.length() - 1) + domain;
    }

    private static String quotedFieldName(String matched) {
        int end = matched.indexOf('"', 1);
        return matched.substring(0, end + 1);
    }
}
